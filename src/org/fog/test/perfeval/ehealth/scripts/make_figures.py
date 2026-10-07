#!/usr/bin/env python3
"""Figures and tables for the revised manuscript, from revision/results_summary.csv (10 seeds per point)."""
import sys, os
import numpy as np, pandas as pd
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt, matplotlib.ticker as mt
from scipy import stats

SRC = sys.argv[1] if len(sys.argv) > 1 else "results_summary.csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "figures"
os.makedirs(OUT, exist_ok=True)
d = pd.read_csv(SRC)

POL = ["LOCAL", "ALWAYS_FOG", "CRITICALITY_AWARE"]
LAB = {"LOCAL": "Edge-local", "ALWAYS_FOG": "Always-Fog", "CRITICALITY_AWARE": "CriticalityAware (proposed)"}
COL = {"LOCAL": "#2a78d6", "ALWAYS_FOG": "#eb6834", "CRITICALITY_AWARE": "#1baf7a"}
MRK = {"LOCAL": "o", "ALWAYS_FOG": "s", "CRITICALITY_AWARE": "^"}
plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
                     "grid.color": "#e6e6e3", "grid.linewidth": 0.6, "lines.linewidth": 1.8, "lines.markersize": 6,
                     "savefig.dpi": 300, "legend.frameon": False, "font.family": "DejaVu Sans"})

def summ(sub, key, metric):
    g = sub.groupby(key)[metric]
    m, s, n = g.mean(), g.std(ddof=1), g.count()
    ci = stats.t.ppf(0.975, n - 1) * s / np.sqrt(n)
    return m.index.values, m.values, ci.values

def logaxis(ax):
    ax.set_yscale("log")
    ax.yaxis.set_major_locator(mt.LogLocator(subs=(1.0, 2.0, 5.0)))
    ax.yaxis.set_major_formatter(mt.FormatStrFormatter("%g"))
    ax.yaxis.set_minor_formatter(mt.NullFormatter())

def lines(ax, exp, key, metric, scale=1.0, policies=POL, ymax=None):
    """ymax: linear axis clipped at ymax; points above it are drawn at the top edge and labelled."""
    sub = d[d.exp == exp]
    for p in policies:
        x, m, ci = summ(sub[sub.policy == p], key, metric)
        m, ci = m * scale, ci * scale
        if ymax is None:
            ax.errorbar(x, m, yerr=ci, color=COL[p], marker=MRK[p], capsize=3, label=LAB[p]); continue
        top = ymax * 0.97
        mc = np.where(m > ymax, top, m); cc = np.where(m > ymax, 0, ci)
        ax.errorbar(x, mc, yerr=cc, color=COL[p], marker=MRK[p], capsize=3, label=LAB[p])
        for xi, mi in zip(x, m):
            if mi > ymax:
                ax.annotate(f"{mi:.0f} ms \u2191", (xi, top), xytext=(-9, -3), textcoords="offset points",
                            ha="right", va="top", fontsize=7, color=COL[p],
                            bbox=dict(boxstyle="round,pad=0.15", fc="white", ec="none"))
        ax.set_ylim(0, ymax)

def save(fig, name, legend_ax=None):
    if legend_ax is not None:
        h, l = legend_ax.get_legend_handles_labels()
        fig.legend(h, l, loc="upper center", ncol=3, bbox_to_anchor=(0.5, 1.02), fontsize=8)
    fig.tight_layout(rect=(0, 0, 1, 0.93) if legend_ax is not None else None)
    for ext in ("pdf", "png"):
        fig.savefig(f"{OUT}/{name}.{ext}", bbox_inches="tight")
    plt.close(fig)

# Fig. A - latency vs load (E2)
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
lines(ax[0], "E2", "load", "crit_mean", ymax=25)
ax[0].set_title("(a) Critical tuples (off-scale values labelled)", fontsize=9); ax[0].set_xlabel("Load factor"); ax[0].set_ylabel("End-to-end latency (ms)")
lines(ax[1], "E2", "load", "norm_mean")
ax[1].set_title("(b) Normal tuples", fontsize=9); ax[1].set_xlabel("Load factor"); ax[1].set_ylabel("End-to-end latency (ms)")
for a in ax: a.set_xticks([0.8, 1.0, 1.2, 1.4])
save(fig, "fig_latency_vs_load", ax[0])

# Fig. B - cost vs load (E2)
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
lines(ax[0], "E2", "load", "edge_to_fog_Bps", scale=1e-6)
ax[0].set_title("(a) Edge-to-Fog traffic", fontsize=9); ax[0].set_xlabel("Load factor"); ax[0].set_ylabel("Traffic (MB/s)")
lines(ax[1], "E2", "load", "fog_util")
ax[1].axhline(0.8, color="#8a8a85", linestyle="--", linewidth=1); ax[1].text(0.8, 0.82, r"$\theta_F$ = 0.8", fontsize=7, color="#555")
ax[1].set_title("(b) Fog utilisation", fontsize=9); ax[1].set_xlabel("Load factor"); ax[1].set_ylabel("Utilisation"); ax[1].set_ylim(0, 1.05)
for a in ax: a.set_xticks([0.8, 1.0, 1.2, 1.4])
save(fig, "fig_cost_vs_load", ax[0])

# Fig. C - threshold sensitivity (E3)
e3 = d[d.exp == "E3"]
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
for metric, lab, c, mk in [("crit_mean", "Critical tuples", COL["CRITICALITY_AWARE"], "^"), ("norm_mean", "Normal tuples", "#5e5e5a", "D")]:
    x, m, ci = summ(e3, "threshold", metric)
    ax[0].errorbar(x, m, yerr=ci, color=c, marker=mk, capsize=3, label=lab)
ax[0].set_title("(a) Mean latency (load 1.2)", fontsize=9); ax[0].set_xlabel(r"Edge threshold $\theta$"); ax[0].set_ylabel("Latency (ms)"); ax[0].legend(fontsize=7)
x, m, ci = summ(e3, "threshold", "offload_frac")
ax[1].errorbar(x, m, yerr=ci, color=COL["CRITICALITY_AWARE"], marker="^", capsize=3)
ax[1].set_title("(b) Critical tuples forwarded", fontsize=9); ax[1].set_xlabel(r"Edge threshold $\theta$"); ax[1].set_ylabel("Fraction"); ax[1].set_ylim(0, 1)
save(fig, "fig_threshold")

# Fig. D - scale with heterogeneous gateways (E4)
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
lines(ax[0], "E4", "patients", "crit_mean", ymax=25)
ax[0].set_title("(a) Critical tuples (off-scale values labelled)", fontsize=9); ax[0].set_xlabel("Number of patients"); ax[0].set_ylabel("End-to-end latency (ms)")
lines(ax[1], "E4", "patients", "fog_util")
ax[1].axhline(0.8, color="#8a8a85", linestyle="--", linewidth=1)
ax[1].set_title("(b) Fog utilisation", fontsize=9); ax[1].set_xlabel("Number of patients"); ax[1].set_ylabel("Utilisation"); ax[1].set_ylim(0, 1.05)
for a in ax: a.set_xticks([25, 50, 75, 100])
save(fig, "fig_scale_hetero", ax[0])

# Table - E1 (same workload, three policies, three anomaly rates) as LaTeX
e1 = d[d.exp == "E1"]
def ms(s): return f"{s.mean():.2f} $\\pm$ {s.std(ddof=1):.2f}" if s.mean() < 100 else f"{s.mean():.0f} $\\pm$ {s.std(ddof=1):.0f}"
def m1(s): return f"{s.mean():.2f}" if s.mean() < 100 else f"{s.mean():.0f}"
rows = []
for p_an in sorted(e1.anomaly.unique()):
    for j, pol in enumerate(POL):
        s = e1[(e1.anomaly == p_an) & (e1.policy == pol)]
        lab = f"{int(round(p_an*100))}\\%" if j == 0 else ""
        rows.append(f"{lab} & {LAB[pol].replace(' (proposed)','')} & {ms(s.crit_mean)} & {m1(s.crit_p95)} & {m1(s.norm_mean)} & "
                    f"{100*s.offload_frac.mean():.0f} & {s.edge_to_fog_Bps.mean()/1e6:.2f} & {s.fog_util.mean():.2f} \\\\")
    rows.append("\\midrule")
rows = rows[:-1]
tex = ("\\begin{table}[H]\n\\caption{Results for the same critical workload under the three policies (load 1.0, 50 patients). "
       "Latencies in ms; Fwd.: critical tuples forwarded to the Fog node; E$\\to$F: Edge-to-Fog traffic. Critical mean: mean $\\pm$ standard deviation over 10 replications; other columns: mean over 10 replications.\\label{tab:e1}}\n"
       "\\footnotesize\n\\newcolumntype{C}{>{\\centering\\arraybackslash}X}\n\\begin{tabularx}{\\textwidth}{cl>{\\centering\\arraybackslash}p{2.3cm}CCCCC}\n\\toprule\n"
       "\\textbf{$p$} & \\textbf{Policy} & \\textbf{Critical mean} & \\textbf{Critical p95} & \\textbf{Normal mean} & \\textbf{Fwd. (\\%)} & \\textbf{E$\\to$F (MB/s)} & \\textbf{Fog util.} \\\\\n\\midrule\n"
       + "\n".join(rows) + "\n\\bottomrule\n\\end{tabularx}\n\\end{table}\n")
open(f"{OUT}/table_e1.tex", "w").write(tex)

# Aggregated table for all experiments (CSV, for the text and the response letters)
cols = ["crit_mean", "crit_p95", "norm_mean", "norm_p95", "offload_frac", "edge_util_mean", "fog_util", "edge_to_fog_Bps", "edge_energy_J_mean", "fog_energy_J"]
out = []
for exp, key in [("E1", "anomaly"), ("E2", "load"), ("E3", "threshold"), ("E4", "patients")]:
    s = d[d.exp == exp].groupby([key, "policy"])[cols].agg(["mean", "std"])
    s.columns = [f"{a}_{b}" for a, b in s.columns]; s = s.reset_index().rename(columns={key: "level"}); s.insert(0, "exp", exp); s.insert(1, "factor", key)
    out.append(s)
pd.concat(out).round(4).to_csv(f"{OUT}/summary_by_config.csv", index=False)
print("ok")
