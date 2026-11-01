#!/usr/bin/env python3
"""Empirical CDF of the end-to-end latency of critical tuples (all ten replications pooled) at load 1.0 and 1.2
(E2, 50 patients, p = 5%). Needs the raw per-tuple files in results/E2. Usage (from the iFogSim root):
    python3 src/org/fog/test/perfeval/ehealth/scripts/make_cdf.py revision/figures"""
import sys, glob, os
import numpy as np, pandas as pd
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
OUT = sys.argv[1] if len(sys.argv) > 1 else "revision/figures"
POL = ["LOCAL", "ALWAYS_FOG", "CRITICALITY_AWARE"]
LAB = {"LOCAL": "Edge-local", "ALWAYS_FOG": "Always-Fog", "CRITICALITY_AWARE": "CriticalityAware (proposed)"}
COL = {"LOCAL": "#2a78d6", "ALWAYS_FOG": "#eb6834", "CRITICALITY_AWARE": "#1baf7a"}
STY = {"LOCAL": "-", "ALWAYS_FOG": "--", "CRITICALITY_AWARE": "-"}
plt.rcParams.update({"font.size": 9, "legend.fontsize": 8.5, "xtick.labelsize": 8.5, "ytick.labelsize": 8.5,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True, "grid.color": "#e6e6e3",
                     "grid.linewidth": 0.6, "legend.frameon": False, "font.family": "DejaVu Sans"})
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.7), sharey=True)
stats = []
for a, load, tag in [(ax[0], "1.0", "(a) Load factor 1.0"), (ax[1], "1.2", "(b) Load factor 1.2")]:
    for p in POL:
        files = glob.glob(f"results/E2/{p}_n50_m3000_p0.05_l{load}_th0.7_s*_critical.csv")
        x = np.sort(np.concatenate([pd.read_csv(f).latency_ms.values for f in files]))
        y = np.arange(1, len(x) + 1) / len(x)
        a.plot(x, y, STY[p], color=COL[p], linewidth=1.6, label=LAB[p])
        stats.append((load, p, len(files), len(x), (x <= 20).mean(), np.percentile(x, 99)))
    for d in (20, 50):
        a.axvline(d, color="#8a8a85", linestyle=":", linewidth=1)
    a.set_xlim(0, 60); a.set_ylim(0, 1.01); a.set_title(tag, fontsize=9); a.set_xlabel("End-to-end latency (ms)")
ax[0].set_ylabel("Fraction of critical tuples")
h, l = ax[0].get_legend_handles_labels()
fig.legend(h, l, loc="upper center", ncol=3, bbox_to_anchor=(0.5, 1.02), fontsize=8.5)
fig.tight_layout(rect=(0, 0, 1, 0.92))
fig.savefig(f"{OUT}/fig_cdf.pdf", bbox_inches="tight")
fig.savefig(f"{OUT}/fig_cdf.png", bbox_inches="tight", dpi=600, facecolor="white")
from PIL import Image
Image.open(f"{OUT}/fig_cdf.png").convert("RGB").save(f"{OUT}/fig_cdf.png", dpi=(600, 600))
for s in stats:
    print("load %s %-18s runs %d tuples %d within20 %.3f p99 %.1f" % s)
