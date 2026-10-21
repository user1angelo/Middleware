#!/usr/bin/env python3
"""
Deep-dive, multi-run analysis of the SDK's end-to-end pipeline latency.

Fires the full `simulate_all_sources.py --source all` alert batch N times (default 10)
against an already-running pipeline (RabbitMQ + modules + WorkflowEngine + ModuleRegistry,
started per TESTING_GUIDE.md chapter 3.1-3.2), and produces a Markdown report with:

  1. A per-run table (one per run) -- same shape as the existing per-stage summary table,
     scoped to only that run's alerts.
  2. A per-stage "deep-dive" table (one per stage, e.g. consume_deserialize gets its own
     table) -- one row per run showing that stage's n/mean/median/p95/max for that run,
     plus a "Pooled (all runs)" row (every individual duration from all runs combined) and
     an "Across-run" row (distribution of the 10 per-run means -- run-to-run variability).
  3. The same two-row treatment (per-run table + pooled/across-run deep-dive) for the
     end-to-end per-alert total (sum of all stages for one traceId), the number behind a
     claim like "end-to-end takes ~1672ms".

Isolating each run's data: WorkflowEngine and ModuleRegistryLifecycleManager are long-running
JVMs that resolve/cache their StageTimer CSV output path once, so redirecting output per run
is not possible without restarting them. Instead this script "watermarks" the row count of
every benchmark_run_*.csv in both watched directories before firing a run, waits for the CSV
growth to settle (no new rows for --settle-seconds, up to --max-wait), then treats only the
newly appended rows as that run's data.

Usage:
    python3 scripts/deep_benchmark_analysis.py
    python3 scripts/deep_benchmark_analysis.py --runs 10 --settle-seconds 4 --max-wait 60

Requires the pipeline already running (see TESTING_GUIDE.md 3.1-3.2) before starting this
script -- it only automates firing alerts + capturing + analyzing timing data (3.3-3.4),
repeated N times.
"""

import argparse
import csv
import glob
import os
import statistics
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXPECTED_STAGES = [
    "consume_deserialize",
    "workflow_load",
    "policy_match",
    "command_dispatch",
    "registry_route_dispatch",
]


# ---------------------------------------------------------------------------
# CSV watching / watermarking
# ---------------------------------------------------------------------------

def read_all_rows(csv_path):
    """Return the list of data rows (as dicts) in a benchmark CSV, or [] if it doesn't exist."""
    if not os.path.isfile(csv_path):
        return []
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = []
        for row in reader:
            try:
                row["durationMs"] = int(row["durationMs"])
            except (KeyError, ValueError, TypeError):
                continue
            row["_sourceFile"] = csv_path
            rows.append(row)
        return rows


def discover_csv_files(watch_dirs):
    files = []
    for d in watch_dirs:
        files.extend(sorted(glob.glob(os.path.join(d, "benchmark_run_*.csv"))))
    return files


def snapshot_counts(watch_dirs):
    """{csv_path: number_of_data_rows_currently_in_it}, including files that don't exist yet
    (as 0) won't be known in advance -- only existing files are snapshotted; a brand-new file
    that appears during a run is entirely "new" data."""
    counts = {}
    for path in discover_csv_files(watch_dirs):
        counts[path] = len(read_all_rows(path))
    return counts


def collect_new_rows(watch_dirs, watermark):
    """Rows appended to any watched CSV since `watermark` (a snapshot_counts() result)."""
    new_rows = []
    for path in discover_csv_files(watch_dirs):
        rows = read_all_rows(path)
        start = watermark.get(path, 0)
        new_rows.extend(rows[start:])
    return new_rows


def total_row_count(watch_dirs):
    return sum(len(read_all_rows(p)) for p in discover_csv_files(watch_dirs))


# ---------------------------------------------------------------------------
# Stats helpers (p95 method matches scripts/summarize_benchmark.py)
# ---------------------------------------------------------------------------

def percentile_95(values):
    if len(values) == 1:
        return values[0]
    quantiles = statistics.quantiles(values, n=100, method="inclusive")
    return quantiles[94]


def stats_row(values):
    return {
        "n": len(values),
        "mean": statistics.mean(values),
        "median": statistics.median(values),
        "p95": percentile_95(values),
        "max": max(values),
    }


def fmt(v):
    return f"{v:.1f}" if isinstance(v, float) else str(v)


def md_table(headers, rows):
    lines = []
    lines.append("| " + " | ".join(headers) + " |")
    lines.append("|" + "|".join(["---"] * len(headers)) + "|")
    for row in rows:
        lines.append("| " + " | ".join(str(c) for c in row) + " |")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# End-to-end per-alert totals (sum of the 5 expected stages per traceId)
# ---------------------------------------------------------------------------

def end_to_end_totals(rows):
    by_trace = defaultdict(lambda: defaultdict(int))
    for row in rows:
        by_trace[row["traceId"]][row["stage"]] += row["durationMs"]

    totals = []
    incomplete = 0
    for trace_id, stage_durations in by_trace.items():
        missing = [s for s in EXPECTED_STAGES if s not in stage_durations]
        if missing:
            incomplete += 1
            continue
        totals.append(sum(stage_durations[s] for s in EXPECTED_STAGES))
    return totals, incomplete


# ---------------------------------------------------------------------------
# Running the alert batch and waiting for it to settle
# ---------------------------------------------------------------------------

def run_one_batch(simulate_script, source, extra_args, watch_dirs, settle_seconds, poll_interval, max_wait, log):
    watermark = snapshot_counts(watch_dirs)

    cmd = [sys.executable, simulate_script, "--source", source] + extra_args
    log(f"  firing: {' '.join(cmd)}")
    result = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        log(f"  WARNING: simulate script exited {result.returncode}")
    if result.stdout.strip():
        for line in result.stdout.strip().splitlines():
            log(f"    {line}")
    if result.stderr.strip():
        for line in result.stderr.strip().splitlines():
            log(f"    [stderr] {line}")

    # Poll until CSV growth settles (no new rows for settle_seconds), capped at max_wait.
    start = time.monotonic()
    last_count = total_row_count(watch_dirs)
    last_change = start
    while True:
        time.sleep(poll_interval)
        now = total_row_count(watch_dirs)
        elapsed = time.monotonic() - start
        if now != last_count:
            last_count = now
            last_change = time.monotonic()
        quiet_for = time.monotonic() - last_change
        if quiet_for >= settle_seconds:
            break
        if elapsed >= max_wait:
            log(f"  WARNING: hit --max-wait ({max_wait}s) before settling; proceeding anyway")
            break

    new_rows = collect_new_rows(watch_dirs, watermark)
    log(f"  captured {len(new_rows)} new row(s)")
    return new_rows


# ---------------------------------------------------------------------------
# Report assembly
# ---------------------------------------------------------------------------

def per_run_stage_table(run_rows):
    by_stage = defaultdict(list)
    for row in run_rows:
        by_stage[row["stage"]].append(row["durationMs"])

    stage_order = EXPECTED_STAGES + sorted(set(by_stage) - set(EXPECTED_STAGES))
    rows = []
    for stage in stage_order:
        if stage not in by_stage:
            rows.append([stage, "(no data)", "", "", "", ""])
            continue
        s = stats_row(by_stage[stage])
        rows.append([stage, s["n"], fmt(s["mean"]), fmt(s["median"]), fmt(s["p95"]), fmt(s["max"])])
    return md_table(["Stage", "n", "Mean (ms)", "Median (ms)", "p95 (ms)", "Max (ms)"], rows)


def stage_deep_dive_table(stage, per_run_rows_list):
    """per_run_rows_list: list of (run_index, list_of_durationMs_for_this_stage_in_that_run)."""
    rows = []
    pooled = []
    per_run_means = []
    for run_idx, durations in per_run_rows_list:
        if not durations:
            rows.append([f"Run {run_idx}", "(no data)", "", "", "", ""])
            continue
        s = stats_row(durations)
        rows.append([f"Run {run_idx}", s["n"], fmt(s["mean"]), fmt(s["median"]), fmt(s["p95"]), fmt(s["max"])])
        pooled.extend(durations)
        per_run_means.append(s["mean"])

    if pooled:
        p = stats_row(pooled)
        rows.append(["**Pooled (all runs)**", p["n"], fmt(p["mean"]), fmt(p["median"]), fmt(p["p95"]), fmt(p["max"])])
    if len(per_run_means) >= 1:
        a = stats_row(per_run_means)
        rows.append(["**Across-run (mean of means)**", a["n"], fmt(a["mean"]), fmt(a["median"]), fmt(a["p95"]), fmt(a["max"])])

    return md_table(["Run", "n", "Mean (ms)", "Median (ms)", "p95 (ms)", "Max (ms)"], rows)


def build_report(all_run_rows, args):
    lines = []
    lines.append("# Deep-Dive SDK Pipeline Latency Analysis")
    lines.append("")
    lines.append(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"Runs: {len(all_run_rows)} x `simulate_all_sources.py --source {args.source}`")
    lines.append(f"Settle window: {args.settle_seconds}s quiet, max wait {args.max_wait}s, poll every {args.poll_interval}s")
    lines.append("")

    # --- Section 1: per-run tables ---
    lines.append("## 1. Per-Run Tables")
    lines.append("")
    lines.append("Each table below uses only the rows produced by that single run's alert batch.")
    lines.append("")
    for i, run_rows in enumerate(all_run_rows, start=1):
        lines.append(f"### Run {i}")
        lines.append("")
        lines.append(per_run_stage_table(run_rows))
        lines.append("")

    # --- Section 2: per-stage deep-dive tables ---
    lines.append("## 2. Per-Stage Deep-Dive Tables")
    lines.append("")
    lines.append("One table per stage: one row per run (that run's stats for this stage only), "
                 "plus a **Pooled** row (every individual duration across all runs combined) and "
                 "an **Across-run** row (distribution of the 10 per-run means -- i.e. run-to-run "
                 "variability rather than within-run variability).")
    lines.append("")

    all_stages = set()
    for run_rows in all_run_rows:
        all_stages.update(row["stage"] for row in run_rows)
    stage_order = EXPECTED_STAGES + sorted(all_stages - set(EXPECTED_STAGES))

    for stage in stage_order:
        if stage not in all_stages:
            continue
        per_run_rows_list = []
        for i, run_rows in enumerate(all_run_rows, start=1):
            durations = [row["durationMs"] for row in run_rows if row["stage"] == stage]
            per_run_rows_list.append((i, durations))
        lines.append(f"### `{stage}`")
        lines.append("")
        lines.append(stage_deep_dive_table(stage, per_run_rows_list))
        lines.append("")

    # --- Section 3: end-to-end per-alert total, same per-run + deep-dive treatment ---
    lines.append("## 3. End-to-End Per-Alert Total (sum of all 5 stages)")
    lines.append("")
    lines.append("For each run, only alerts (traceIds) with all 5 expected stages present are "
                 "included; incomplete traces are counted but excluded from the stats, same as "
                 "`summarize_benchmark.py`.")
    lines.append("")

    per_run_rows_list = []
    run_summary_rows = []
    for i, run_rows in enumerate(all_run_rows, start=1):
        totals, incomplete = end_to_end_totals(run_rows)
        per_run_rows_list.append((i, totals))
        if totals:
            s = stats_row(totals)
            run_summary_rows.append([f"Run {i}", s["n"], fmt(s["mean"]), fmt(s["median"]), fmt(s["p95"]), fmt(s["max"]), incomplete])
        else:
            run_summary_rows.append([f"Run {i}", "(no complete traces)", "", "", "", "", incomplete])

    lines.append("### Per-run summary")
    lines.append("")
    lines.append(md_table(
        ["Run", "n (complete traces)", "Mean (ms)", "Median (ms)", "p95 (ms)", "Max (ms)", "Incomplete traces"],
        run_summary_rows,
    ))
    lines.append("")

    lines.append("### Deep-dive (pooled + across-run)")
    lines.append("")
    lines.append(stage_deep_dive_table("end_to_end", per_run_rows_list))
    lines.append("")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=10, help="number of end-to-end runs to fire (default 10)")
    parser.add_argument("--source", default="all", help="--source value passed to simulate_all_sources.py (default 'all')")
    parser.add_argument("--simulate-script", default=os.path.join(REPO_ROOT, "scripts", "simulate_all_sources.py"),
                         help="path to simulate_all_sources.py")
    parser.add_argument("--simulate-arg", action="append", default=[], dest="simulate_args",
                         help="extra arg to pass through to simulate_all_sources.py (repeatable), "
                              "e.g. --simulate-arg --suricata-http-url --simulate-arg http://host:8090/suricata/alerts")
    parser.add_argument("--watch-dir", action="append", default=[], dest="watch_dirs",
                         help="benchmark_output directory to watch (repeatable); default: "
                              "WorkflowEngine/benchmark_output and ModuleRegistryLifecycleManager/benchmark_output")
    parser.add_argument("--settle-seconds", type=float, default=4.0,
                         help="consider a run done once no new CSV rows appear for this many seconds (default 4)")
    parser.add_argument("--poll-interval", type=float, default=1.0, help="CSV poll interval in seconds (default 1)")
    parser.add_argument("--max-wait", type=float, default=60.0,
                         help="give up waiting for a run to settle after this many seconds (default 60)")
    parser.add_argument("--pause-between-runs", type=float, default=2.0,
                         help="extra pause (seconds) after one run settles before firing the next (default 2)")
    parser.add_argument("--out-dir", default=None,
                         help="directory for the report + per-run raw CSVs "
                              "(default: deep_benchmark_output/<timestamp>/ under repo root)")
    args = parser.parse_args()

    watch_dirs = args.watch_dirs or [
        os.path.join(REPO_ROOT, "WorkflowEngine", "benchmark_output"),
        os.path.join(REPO_ROOT, "ModuleRegistryLifecycleManager", "benchmark_output"),
    ]
    for d in watch_dirs:
        if not os.path.isdir(d):
            print(f"warning: watch dir does not exist yet (ok if the pipeline hasn't logged anything yet): {d}",
                  file=sys.stderr)

    out_dir = args.out_dir or os.path.join(REPO_ROOT, "deep_benchmark_output", datetime.now().strftime("%Y%m%d_%H%M%S"))
    os.makedirs(out_dir, exist_ok=True)

    def log(msg):
        print(msg)

    log(f"Watching: {watch_dirs}")
    log(f"Output dir: {out_dir}")
    log(f"Running {args.runs} run(s) of `simulate_all_sources.py --source {args.source}`")
    log("Make sure RabbitMQ + modules + WorkflowEngine + ModuleRegistry are already running "
        "(TESTING_GUIDE.md 3.1-3.2) before this proceeds.")
    log("")

    all_run_rows = []
    for i in range(1, args.runs + 1):
        log(f"[Run {i}/{args.runs}]")
        new_rows = run_one_batch(
            simulate_script=args.simulate_script,
            source=args.source,
            extra_args=args.simulate_args,
            watch_dirs=watch_dirs,
            settle_seconds=args.settle_seconds,
            poll_interval=args.poll_interval,
            max_wait=args.max_wait,
            log=log,
        )
        all_run_rows.append(new_rows)

        run_csv_path = os.path.join(out_dir, f"run_{i:02d}.csv")
        with open(run_csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["traceId", "stage", "startTime", "endTime", "durationMs", "sourceFile"])
            for row in new_rows:
                writer.writerow([row.get("traceId", ""), row.get("stage", ""), row.get("startTime", ""),
                                  row.get("endTime", ""), row.get("durationMs", ""), row.get("_sourceFile", "")])
        log(f"  saved raw rows to {run_csv_path}")

        if i < args.runs and args.pause_between_runs > 0:
            time.sleep(args.pause_between_runs)
        log("")

    report = build_report(all_run_rows, args)
    report_path = os.path.join(out_dir, "deep_analysis_report.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)

    log(f"Report written to {report_path}")


if __name__ == "__main__":
    main()
