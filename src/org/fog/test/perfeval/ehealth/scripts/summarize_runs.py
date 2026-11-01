#!/usr/bin/env python3
"""Summarises all runs in results/E*/ (500 runs, E1-E5) in one file, one row per run. Standard library only.
v1.2.0: all metrics refer to the measurement window [warm-up, end of sample generation]; latencies include every
generated sample (the runs drain); deadline metrics count undelivered critical tuples as misses.
Usage (from the iFogSim root):  python3 src/org/fog/test/perfeval/ehealth/scripts/summarize_runs.py
Output: revision/results_summary.csv and revision/latency_hist.csv
"""
import csv, glob, os, math

def q(sorted_vals, p):
    if not sorted_vals: return float('nan')
    k = (len(sorted_vals) - 1) * p; f = math.floor(k); c = math.ceil(k)
    return sorted_vals[f] if f == c else sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)

def stats(vals, prefix):
    v = sorted(vals); n = len(v)
    return {f"{prefix}_n": n, f"{prefix}_mean": (sum(v) / n if n else float('nan')),
            f"{prefix}_p50": q(v, .5), f"{prefix}_p95": q(v, .95), f"{prefix}_p99": q(v, .99),
            f"{prefix}_max": (v[-1] if n else float('nan'))}

BINS = [0, 2, 4, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 300, 500, 1000, 1e9]
DEADLINES_MS = [20, 50, 100]
rows, hist = [], []
for runf in sorted(glob.glob("results/E*/*_run.csv")):
    p = runf[:-len("_run.csv")]; exp = runf.split(os.sep)[1]
    info = {r["key"]: r["value"] for r in csv.DictReader(open(runf))}
    dev = list(csv.DictReader(open(p + "_devices.csv")))
    mips_of = {int(d["patient"]): d["mips"] for d in dev if d["role"] == "EDGE"}
    crit, crit_off, crit_loc, by_mips = [], [], [], {}
    for r in csv.DictReader(open(p + "_critical.csv")):
        x = float(r["latency_ms"]); crit.append(x)
        (crit_off if r["offloaded"] == "1" else crit_loc).append(x)
        by_mips.setdefault(mips_of.get(int(r["patient"]), "?"), []).append(x)
    norm = [float(r["latency_ms"]) for r in csv.DictReader(open(p + "_normal.csv"))]
    edges = [d for d in dev if d["role"] == "EDGE"]; fog = [d for d in dev if d["role"] == "FOG"][0]
    T = float(info["measure_window_ms"]) / 1000.0   # seconds of the measurement window
    row = {"exp": exp, "file": os.path.basename(p)}; row.update(info)
    row.update(stats(crit, "crit")); row.update(stats(norm, "norm"))
    row["crit_offloaded_mean"] = sum(crit_off) / len(crit_off) if crit_off else float('nan')
    row["crit_local_mean"] = sum(crit_loc) / len(crit_loc) if crit_loc else float('nan')
    seen = sum(int(d["critical_seen"]) for d in edges); off = sum(int(d["critical_offloaded"]) for d in edges)
    row["offload_frac"] = off / seen if seen else 0.0
    row["edge_util_mean"] = sum(float(d["utilization"]) for d in edges) / len(edges)
    row["edge_util_max"] = max(float(d["utilization"]) for d in edges)
    row["fog_util"] = float(fog["utilization"])
    row["edge_energy_J_mean"] = sum(float(d["energy_Wms"]) for d in edges) / len(edges) / 1000.0
    row["fog_energy_J"] = float(fog["energy_Wms"]) / 1000.0
    row["edge_power_W_mean"] = row["edge_energy_J_mean"] / T
    row["fog_power_W"] = row["fog_energy_J"] / T
    gen = int(info["crit_generated"])
    for D in DEADLINES_MS:   # fraction of generated critical tuples delivered within D ms (pending = miss)
        row[f"crit_within_{D}ms"] = sum(1 for x in crit if x <= D) / gen if gen else float('nan')
    row["crit_done_by_gen_end_frac"] = int(info["crit_done_by_gen_end"]) / gen if gen else float('nan')
    row["drain_ms"] = float(info["end_ms"]) - float(info["sim_ms"])
    row["edge_to_fog_Bps"] = float(info["network_bytes_edge_to_fog"]) / T
    row["total_Bps"] = float(info["network_bytes_total"]) / T
    for m, v in sorted(by_mips.items()):
        row[f"crit_mean_m{m}"] = sum(v) / len(v)
    rows.append(row)
    counts = [0] * (len(BINS) - 1)
    for x in crit:
        for i in range(len(BINS) - 1):
            if BINS[i] <= x < BINS[i + 1]: counts[i] += 1; break
    for i, c in enumerate(counts):
        hist.append({"exp": exp, "file": row["file"], "policy": info["policy"], "load": info["load"],
                     "anomaly": info["anomaly"], "seed": info["seed"], "bin_lo": BINS[i], "bin_hi": BINS[i + 1], "count": c})

os.makedirs("revision", exist_ok=True)
keys = []
for r in rows:
    for k in r:
        if k not in keys: keys.append(k)
with open("revision/results_summary.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=keys); w.writeheader(); w.writerows(rows)
with open("revision/latency_hist.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(hist[0].keys())); w.writeheader(); w.writerows(hist)
print(f"{len(rows)} runs summarised -> revision/results_summary.csv and revision/latency_hist.csv")
