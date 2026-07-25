#!/usr/bin/env python3
"""
Summarize StageTimer benchmark CSVs produced by nis-thesis-sdk's StageTimer utility.

Each input CSV has rows: traceId,stage,startEpochMs,endEpochMs,durationMs

Usage:
    python scripts/summarize_benchmark.py <csv-or-dir> [<csv-or-dir> ...]

Multiple files/directories may be given (e.g. WorkflowEngine's and
ModuleRegistryLifecycleManager's separate benchmark_output/ directories) -
they are concatenated before analysis, since the 5 SDK stages for one alert
can be split across two processes' CSV files.
"""

import argparse
import csv
import glob
import os
import statistics
import sys
from collections import defaultdict

EXPECTED_STAGES = [
    "consume_deserialize",
    "workflow_load",
    "policy_match",
    "command_dispatch",
    "registry_route_dispatch",
]


def collect_csv_paths(inputs):
    paths = []
    for item in inputs:
        if os.path.isdir(item):
            paths.extend(sorted(glob.glob(os.path.join(item, "benchmark_run_*.csv"))))
        elif os.path.isfile(item):
            paths.append(item)
        else:
            print(f"warning: path not found, skipping: {item}", file=sys.stderr)
    return paths


def load_rows(paths):
    rows = []
    for path in paths:
        with open(path, newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    row["durationMs"] = int(row["durationMs"])
                except (KeyError, ValueError):
                    continue
                rows.append(row)
    return rows


def percentile_95(values):
    if len(values) == 1:
        return values[0]
    quantiles = statistics.quantiles(values, n=100, method="inclusive")
    return quantiles[94]


def print_stat_row(label, values):
    count = len(values)
    mean = statistics.mean(values)
    median = statistics.median(values)
    p95 = percentile_95(values)
    worst = max(values)
    print(f"{label:<26}{count:>8}{mean:>12.1f}{median:>12.1f}{p95:>12.1f}{worst:>12.1f}")


def per_stage_summary(rows):
    by_stage = defaultdict(list)
    for row in rows:
        by_stage[row["stage"]].append(row["durationMs"])

    print("\n=== Per-stage latency (ms) ===")
    print(f"{'stage':<26}{'n':>8}{'mean':>12}{'median':>12}{'p95':>12}{'max':>12}")
    for stage in EXPECTED_STAGES:
        if stage in by_stage:
            print_stat_row(stage, by_stage[stage])
        else:
            print(f"{stage:<26}{'(no data)':>8}")

    other_stages = sorted(set(by_stage) - set(EXPECTED_STAGES))
    for stage in other_stages:
        print_stat_row(stage, by_stage[stage])


def per_alert_end_to_end_summary(rows):
    # traceId -> stage -> summed durationMs (sum handles alerts that trigger
    # multiple workflow steps/matches, e.g. multiple command_dispatch rows)
    by_trace = defaultdict(lambda: defaultdict(int))
    for row in rows:
        by_trace[row["traceId"]][row["stage"]] += row["durationMs"]

    complete_totals = []
    incomplete_count = 0
    for trace_id, stage_durations in by_trace.items():
        missing = [s for s in EXPECTED_STAGES if s not in stage_durations]
        if missing:
            incomplete_count += 1
            continue
        total = sum(stage_durations[s] for s in EXPECTED_STAGES)
        complete_totals.append(total)

    print("\n=== Per-alert end-to-end SDK time (sum of all 5 stages, ms) ===")
    if not complete_totals:
        print("No alerts had all 5 stages present - nothing to summarize.")
    else:
        print(f"{'':<26}{'n':>8}{'mean':>12}{'median':>12}{'p95':>12}{'max':>12}")
        print_stat_row("end_to_end_sdk_time", complete_totals)

    print(f"\nComplete traces (all 5 stages): {len(complete_totals)}")
    print(f"Incomplete traces (missing >=1 stage): {incomplete_count}")
    if incomplete_count:
        print("  (excluded from the end-to-end total above; check for dropped/failed alerts,")
        print("   or partial runs captured mid-benchmark)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+", help="CSV file(s) and/or directories containing benchmark_run_*.csv files")
    args = parser.parse_args()

    paths = collect_csv_paths(args.inputs)
    if not paths:
        print("error: no CSV files found in the given inputs", file=sys.stderr)
        sys.exit(1)

    print(f"Loaded {len(paths)} CSV file(s):")
    for p in paths:
        print(f"  {p}")

    rows = load_rows(paths)
    if not rows:
        print("error: no valid rows found in the given CSV files", file=sys.stderr)
        sys.exit(1)

    print(f"\nTotal rows: {len(rows)}")

    per_stage_summary(rows)
    per_alert_end_to_end_summary(rows)


if __name__ == "__main__":
    main()
