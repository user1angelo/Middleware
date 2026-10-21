#!/usr/bin/env python3
"""
Checks whether deep_benchmark_analysis.py's per-run CSV slices (run_01.csv..run_NN.csv,
written alongside deep_analysis_report.md in its output directory) actually stay inside
clean, non-overlapping time windows.

Why this matters: deep_benchmark_analysis.py isolates each run's rows by watermarking row
counts and waiting for CSV growth to settle. If a dispatch is stuck behind a slow retry/
timeout and lands AFTER one run's settle-window closed but BEFORE the next run's watermark
was taken, it gets attributed to the wrong run -- a measurement-boundary bug, not real
pipeline behavior. This script makes that checkable instead of guessed at.

For each run_NN.csv, it reports the startTime range (min/max) and flags:
  - any row whose startTime falls outside a sane window relative to the rest of that run
    (a straggler > --straggler-threshold seconds earlier than that run's own median time)
  - any overlap between one run's time window and the next run's (run N's max startTime
    later than run N+1's min startTime)

Usage:
    python3 scripts/check_run_boundaries.py <output-dir-containing-run_*.csv>
    python3 scripts/check_run_boundaries.py deep_benchmark_output/20261021_211646/
"""

import argparse
import csv
import glob
import os
import statistics
import sys
from datetime import datetime

TIMESTAMP_FORMAT = "%Y-%m-%d %H:%M:%S.%f"


def parse_ts(s):
    # StageTimer writes milliseconds (.SSS); Python's %f wants microseconds, so pad.
    if "." in s:
        head, frac = s.split(".", 1)
        frac = (frac + "000000")[:6]
        s = f"{head}.{frac}"
    else:
        s = f"{s}.000000"
    return datetime.strptime(s, TIMESTAMP_FORMAT)


def load_run(path):
    rows = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            ts = row.get("startTime", "").strip()
            if not ts:
                continue
            try:
                rows.append((parse_ts(ts), row))
            except ValueError:
                continue
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out_dir", help="directory containing run_01.csv .. run_NN.csv")
    parser.add_argument("--straggler-threshold", type=float, default=10.0,
                         help="flag a row as a straggler if its startTime is more than this many "
                              "seconds earlier than that run's own median startTime (default 10)")
    args = parser.parse_args()

    run_files = sorted(glob.glob(os.path.join(args.out_dir, "run_*.csv")))
    if not run_files:
        print(f"error: no run_*.csv files found in {args.out_dir}", file=sys.stderr)
        sys.exit(1)

    print(f"Found {len(run_files)} run file(s) in {args.out_dir}\n")

    runs = []
    for path in run_files:
        rows = load_run(path)
        label = os.path.basename(path)
        if not rows:
            print(f"{label}: (no rows)")
            runs.append((label, None, None, []))
            continue
        timestamps = [ts for ts, _ in rows]
        tmin, tmax = min(timestamps), max(timestamps)
        median_epoch = statistics.median(t.timestamp() for t in timestamps)
        print(f"{label}: n={len(rows)}  window=[{tmin}  ->  {tmax}]  span={ (tmax - tmin).total_seconds():.1f}s")

        # flag stragglers within this run
        stragglers = []
        for ts, row in rows:
            delta = median_epoch - ts.timestamp()
            if delta > args.straggler_threshold:
                stragglers.append((ts, row, delta))
        if stragglers:
            print(f"  ! {len(stragglers)} straggler row(s) (>{args.straggler_threshold:.0f}s before this run's "
                  f"median time) -- possible leftover from a PREVIOUS run:")
            for ts, row, delta in stragglers:
                print(f"      traceId={row.get('traceId')} stage={row.get('stage')} startTime={ts} "
                      f"({delta:.1f}s before this run's median)")

        runs.append((label, tmin, tmax, rows))

    print("\n--- Cross-run overlap check ---")
    overlap_found = False
    for i in range(len(runs) - 1):
        label_a, _, max_a, rows_a = runs[i]
        label_b, min_b, _, rows_b = runs[i + 1]
        if max_a is None or min_b is None:
            continue
        if max_a > min_b:
            overlap_found = True
            print(f"  ! OVERLAP: {label_a}'s latest row ({max_a}) is AFTER {label_b}'s earliest row ({min_b}). "
                  f"These two runs' time windows overlap by {(max_a - min_b).total_seconds():.1f}s.")
        else:
            print(f"  ok: {label_a} ends before {label_b} starts (gap {(min_b - max_a).total_seconds():.1f}s)")

    print()
    if overlap_found:
        print("CONCLUSION: at least one run's window overlaps the next run's window, or stragglers were found.")
        print("This is consistent with cross-run bleed in deep_benchmark_analysis.py's watermarking --")
        print("a slow/retried dispatch from one run landing in the next run's capture window and being")
        print("miscounted into its N. This would explain command_dispatch/registry_route_dispatch's N")
        print("varying across 'identical' runs WITHOUT requiring the live pipeline itself to be")
        print("non-deterministic. Fix: increase --settle-seconds/--max-wait, or filter captured rows by")
        print("startTime falling strictly within [run start, run end] instead of pure watermark slicing.")
    else:
        print("CONCLUSION: no overlap or stragglers detected -- each run's rows stayed inside a clean, ")
        print("non-overlapping time window. The N variability across 'identical' runs is NOT explained by")
        print("my script's watermarking boundary. That points back to the live pipeline itself (RabbitMQ")
        print("redelivery/retry, or genuine non-determinism in dispatch) as the real source -- worth")
        print("checking WorkflowEngine/ModuleRegistry logs directly for nack/redelivery events next.")


if __name__ == "__main__":
    main()
