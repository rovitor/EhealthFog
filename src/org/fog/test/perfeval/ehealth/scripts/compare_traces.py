#!/usr/bin/env python3
"""Checks that the three policies label exactly the same samples as critical (v1.2.0) and compares the latency of
each critical sample across policies (paired per sample). Standard library only.
Usage (from the iFogSim root):  python3 src/org/fog/test/perfeval/ehealth/scripts/compare_traces.py
Output: revision/trace_check.csv (one row per configuration and seed) and a summary on stdout.
"""
import csv, glob, os, re, statistics as st

POL = ["LOCAL", "ALWAYS_FOG", "CRITICALITY_AWARE"]

def labels(prefix):
    with open(prefix + "_labels.csv") as f:
        return {(r["sensor"], r["index"]) for r in csv.DictReader(f)}

def latencies(prefix):
    with open(prefix + "_critical.csv") as f:
        return {(r["patient"], r["emit_ms"]): float(r["latency_ms"]) for r in csv.DictReader(f)}

rows = []
for exp in ["E1", "E2", "E4"]:
    groups = {}
    for runf in glob.glob(f"results/{exp}/*_run.csv"):
        p = runf[:-len("_run.csv")]
        name = os.path.basename(p)
        pol = next(x for x in sorted(POL, key=len, reverse=True) if name.startswith(x + "_"))
        key = name[len(pol) + 1:]
        groups.setdefault(key, {})[pol] = p
    for key, g in sorted(groups.items()):
        if len(g) != 3:
            continue
        L = {pol: labels(g[pol]) for pol in POL}
        same = L["LOCAL"] == L["ALWAYS_FOG"] == L["CRITICALITY_AWARE"]
        lat = {pol: latencies(g[pol]) for pol in POL}
        common = set(lat["LOCAL"]) & set(lat["ALWAYS_FOG"]) & set(lat["CRITICALITY_AWARE"])
        d_loc = [lat["CRITICALITY_AWARE"][k] - lat["LOCAL"][k] for k in common]
        d_af = [lat["CRITICALITY_AWARE"][k] - lat["ALWAYS_FOG"][k] for k in common]
        rows.append({"exp": exp, "config": key, "labels_identical": int(same), "critical_labels": len(L["LOCAL"]),
                     "matched_samples": len(common),
                     "ca_faster_than_local_frac": sum(1 for x in d_loc if x < 0) / len(d_loc),
                     "ca_slower_than_local_frac": sum(1 for x in d_loc if x > 0) / len(d_loc),
                     "ca_minus_local_median_ms": st.median(d_loc), "ca_minus_local_mean_ms": st.mean(d_loc),
                     "ca_faster_than_fog_frac": sum(1 for x in d_af if x < 0) / len(d_af),
                     "ca_minus_fog_median_ms": st.median(d_af), "ca_minus_fog_mean_ms": st.mean(d_af)})

os.makedirs("revision", exist_ok=True)
with open("revision/trace_check.csv", "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
n_ok = sum(r["labels_identical"] for r in rows)
print(f"{n_ok} of {len(rows)} configuration-seed groups have identical critical labels under the three policies")
print(f"critical samples matched per group: min {min(r['matched_samples'] for r in rows)}, "
      f"mean {st.mean(r['matched_samples'] for r in rows):.0f}")
