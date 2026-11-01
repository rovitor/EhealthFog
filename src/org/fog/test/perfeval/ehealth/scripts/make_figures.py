#!/usr/bin/env python3
"""Figures and tables for the revised manuscript, from revision/results_summary.csv (10 seeds per point).
Usage: python3 make_figures.py revision/results_summary.csv revision/figures"""
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
plt.rcParams.update({"font.size": 9, "legend.fontsize": 8.5, "xtick.labelsize": 8.5, "ytick.labelsize": 8.5, "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
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

SAT = 0.95   # Fog utilization at or above which a configuration is drawn with an open marker (Fog node saturated)

def lines(ax, exp, key, metric, scale=1.0, policies=POL, mark_saturated=False):
    """mark_saturated: configurations whose mean Fog utilization is >= SAT are drawn with open markers."""
    sub = d[d.exp == exp]
    for p in policies:
        x, m, ci = summ(sub[sub.policy == p], key, metric)
        m, ci = m * scale, ci * scale
        ax.errorbar(x, m, yerr=ci, color=COL[p], marker=MRK[p], capsize=3, label=LAB[p])
        if mark_saturated:
            _, fu, _ = summ(sub[sub.policy == p], key, "fog_util")
            sat = fu >= SAT
            if sat.any():
                ax.plot(x[sat], m[sat], linestyle="none", marker=MRK[p], markersize=7, markerfacecolor="white",
                        markeredgecolor=COL[p], markeredgewidth=1.6, zorder=5)

def save(fig, name, legend_ax=None, manual=False):
    if legend_ax is not None:
        h, l = legend_ax.get_legend_handles_labels()
        fig.legend(h, l, loc="upper center", ncol=3, bbox_to_anchor=(0.5, 1.02), fontsize=8.5)
    if manual:   # figures with a broken axis (gridspec): fixed margins instead of tight_layout
        fig.subplots_adjust(left=0.12, right=0.98, top=0.83, bottom=0.15)
    else:
        fig.tight_layout(rect=(0, 0, 1, 0.93) if legend_ax is not None else None)
    fig.savefig(f"{OUT}/{name}.pdf", bbox_inches="tight")
    fig.savefig(f"{OUT}/{name}.png", bbox_inches="tight", dpi=600, facecolor="white")
    from PIL import Image   # MDPI: RGB at 8 bits per channel (matplotlib writes RGBA)
    Image.open(f"{OUT}/{name}.png").convert("RGB").save(f"{OUT}/{name}.png", dpi=(600, 600))
    plt.close(fig)

def broken_latency(fig, cell, exp, key, metric="crit_mean", low=(0, 25), high=(100, 10000)):
    """Panel with a broken vertical axis: linear `low` range for the stable configurations (bottom, 3/4 of the
    height) and logarithmic `high` range for the saturated ones (top). Returns (top_axis, bottom_axis)."""
    import matplotlib.gridspec as gs
    sub = gs.GridSpecFromSubplotSpec(2, 1, subplot_spec=cell, height_ratios=[1.3, 3], hspace=0.12)
    top, bot = fig.add_subplot(sub[0]), fig.add_subplot(sub[1], sharex=None)
    for ax in (top, bot):
        lines(ax, exp, key, metric, mark_saturated=True)
    bot.set_ylim(*low); top.set_ylim(*high); logaxis(top)
    top.yaxis.set_major_locator(mt.FixedLocator([100, 1000, 10000])); top.yaxis.set_major_formatter(mt.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    top.spines["bottom"].set_visible(False); top.tick_params(axis="x", bottom=False, labelbottom=False)
    kw = dict(color="#555", clip_on=False, linewidth=0.9)
    for ax, y in ((top, 0.0), (bot, 1.0)):   # diagonal break marks on the left axis
        ax.plot([-0.025, 0.025], [y - 0.03 * (3 if ax is top else 1), y + 0.03 * (3 if ax is top else 1)], transform=ax.transAxes, **kw)
    return top, bot

# Fig. A - latency vs load (E2)
fig = plt.figure(figsize=(5.4, 2.9)); G = fig.add_gridspec(1, 2, wspace=0.42)
top, bot = broken_latency(fig, G[0], "E2", "load")
top.set_title("(a) Critical tuples", fontsize=9); bot.set_xlabel("Load factor")
fig.text(0.015, 0.49, "End-to-end latency (ms)", rotation=90, va="center", ha="center", fontsize=9)
ax1 = fig.add_subplot(G[1]); lines(ax1, "E2", "load", "norm_mean")
ax1.set_title("(b) Normal tuples", fontsize=9); ax1.set_xlabel("Load factor"); ax1.set_ylabel("End-to-end latency (ms)")
for a in (top, bot, ax1): a.set_xticks([0.8, 1.0, 1.2, 1.4])
top.set_xticklabels([])
save(fig, "fig_latency_vs_load", bot, manual=True)

# Fig. B - cost vs load (E2)
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
lines(ax[0], "E2", "load", "edge_to_fog_Bps", scale=1e-6)
ax[0].set_title("(a) Edge-to-Fog traffic", fontsize=9); ax[0].set_xlabel("Load factor"); ax[0].set_ylabel("Traffic (MB/s)")
lines(ax[1], "E2", "load", "fog_util")
ax[1].axhline(0.8, color="#8a8a85", linestyle="--", linewidth=1); ax[1].text(0.8, 0.83, r"$\theta_F$ = 0.8", fontsize=8, color="#555")
ax[1].set_title("(b) Fog utilization", fontsize=9); ax[1].set_xlabel("Load factor"); ax[1].set_ylabel("Utilization"); ax[1].set_ylim(0, 1.05)
for a in ax: a.set_xticks([0.8, 1.0, 1.2, 1.4])
save(fig, "fig_cost_vs_load", ax[0])

# Fig. C - threshold sensitivity (E3)
e3 = d[d.exp == "E3"]
fig, ax = plt.subplots(1, 2, figsize=(5.4, 2.55))
for metric, lab, c, mk in [("crit_mean", "Critical tuples", COL["CRITICALITY_AWARE"], "^"), ("norm_mean", "Normal tuples", "#5e5e5a", "D")]:
    x, m, ci = summ(e3, "threshold", metric)
    ax[0].errorbar(x, m, yerr=ci, color=c, marker=mk, capsize=3, label=lab)
ax[0].set_title("(a) Mean latency", fontsize=9); ax[0].set_xlabel(r"Edge threshold $\theta$"); ax[0].set_ylabel("End-to-end latency (ms)"); ax[0].legend(fontsize=8.5)
x, m, ci = summ(e3, "threshold", "offload_frac")
ax[1].errorbar(x, m, yerr=ci, color=COL["CRITICALITY_AWARE"], marker="^", capsize=3)
ax[1].set_title("(b) Critical tuples forwarded", fontsize=9); ax[1].set_xlabel(r"Edge threshold $\theta$"); ax[1].set_ylabel("Fraction"); ax[1].set_ylim(0, 1)
save(fig, "fig_threshold")

# Fig. D - scale with heterogeneous gateways (E4)
fig = plt.figure(figsize=(5.4, 2.9)); G = fig.add_gridspec(1, 2, wspace=0.42)
top, bot = broken_latency(fig, G[0], "E4", "patients")
top.set_title("(a) Critical tuples", fontsize=9); bot.set_xlabel("Number of patients")
fig.text(0.015, 0.49, "End-to-end latency (ms)", rotation=90, va="center", ha="center", fontsize=9)
ax1 = fig.add_subplot(G[1]); lines(ax1, "E4", "patients", "fog_util")
ax1.axhline(0.8, color="#8a8a85", linestyle="--", linewidth=1); ax1.text(25, 0.83, r"$\theta_F$ = 0.8", fontsize=8, color="#555")
ax1.set_title("(b) Fog utilization", fontsize=9); ax1.set_xlabel("Number of patients"); ax1.set_ylabel("Utilization"); ax1.set_ylim(0, 1.05)
for a in (top, bot, ax1): a.set_xticks([25, 50, 75, 100])
top.set_xticklabels([])
save(fig, "fig_scale_hetero", bot, manual=True)

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

# Table - generated, completed and pending critical tuples, and deadline satisfaction (E1), as LaTeX
def pct(x): return f"{100*x:.1f}"
rows = []
for p_an in sorted(e1.anomaly.unique()):
    for j, pol in enumerate(POL):
        s = e1[(e1.anomaly == p_an) & (e1.policy == pol)]
        lab = f"{int(round(p_an*100))}\\%" if j == 0 else ""
        pend = (s.crit_generated - s.crit_done_by_gen_end).mean()
        rows.append(f"{lab} & {LAB[pol].replace(' (proposed)','')} & {s.crit_generated.mean():,.0f} & {pct(s.crit_done_by_gen_end_frac.mean())} & "
                    f"{pend:,.0f} & {s.drain_ms.mean():,.0f} & {pct(s.crit_within_20ms.mean())} & {pct(s.crit_within_50ms.mean())} \\\\")
    rows.append("\\midrule")
rows = rows[:-1]
tex = ("\\begin{table}[H]\n\\caption{Completion of critical tuples (load 1.0, 50 patients; mean over 10 replications). Generated: critical tuples generated "
       "in the measurement interval; Done: percentage completed by the end of sample generation; Pending: tuples still pending at that time; Drain: time "
       "needed after the end of generation to complete them; $\\leq$20~ms, $\\leq$50~ms: percentage of the generated critical tuples whose result arrived "
       "within 20 and 50~ms. After the drain, every generated tuple was completed in every run.\\label{tab:tasks}}\n"
       "\\footnotesize\n\\newcolumntype{C}{>{\\centering\\arraybackslash}X}\n\\begin{tabularx}{\\textwidth}{clCCCCCC}\n\\toprule\n"
       "\\textbf{$p$} & \\textbf{Policy} & \\textbf{Generated} & \\textbf{Done (\\%)} & \\textbf{Pending} & \\textbf{Drain (ms)} & \\textbf{$\\leq$20~ms (\\%)} & \\textbf{$\\leq$50~ms (\\%)} \\\\\n\\midrule\n"
       + "\n".join(rows) + "\n\\bottomrule\n\\end{tabularx}\n\\end{table}\n")
open(f"{OUT}/table_tasks.tex", "w").write(tex)

# Table - E5 (sensitivity to the Fog threshold theta_F, CriticalityAware, loads 1.2 and 1.4) as LaTeX
e5 = d[d.exp == "E5"]
if len(e5):
    rows = []
    for ld in sorted(e5.load.unique()):
        for j, ft in enumerate(sorted(e5.fog_threshold.unique())):
            s = e5[(e5.load == ld) & (e5.fog_threshold == ft)]
            lab = f"{ld:.1f}" if j == 0 else ""
            ftl = "$\\infty$ (no guard)" if ft > 1.5 else f"{ft:.1f}"
            rows.append(f"{lab} & {ftl} & {ms(s.crit_mean)} & {m1(s.crit_p95)} & {m1(s.norm_mean)} & "
                        f"{100*s.offload_frac.mean():.0f} & {s.edge_to_fog_Bps.mean()/1e6:.1f} & {s.fog_util.mean():.2f} \\\\")
        rows.append("\\midrule")
    rows = rows[:-1]
    tex = ("\\begin{table}[H]\n\\caption{Sensitivity of \\textit{CriticalityAware} to the Fog threshold $\\theta_F$ ($\\theta=0.7$, 50 patients, $p=5\\%$). "
           "Latencies in ms; Fwd.: critical tuples forwarded to the Fog node; E$\\to$F: Edge-to-Fog traffic. Critical mean: mean $\\pm$ standard deviation over 10 replications; other columns: mean over 10 replications.\\label{tab:e5}}\n"
           "\\footnotesize\n\\newcolumntype{C}{>{\\centering\\arraybackslash}X}\n\\begin{tabularx}{\\textwidth}{cc>{\\centering\\arraybackslash}p{2.3cm}CCCCC}\n\\toprule\n"
           "\\textbf{Load} & \\textbf{$\\theta_F$} & \\textbf{Critical mean} & \\textbf{Critical p95} & \\textbf{Normal mean} & \\textbf{Fwd. (\\%)} & \\textbf{E$\\to$F (MB/s)} & \\textbf{Fog util.} \\\\\n\\midrule\n"
           + "\n".join(rows) + "\n\\bottomrule\n\\end{tabularx}\n\\end{table}\n")
    open(f"{OUT}/table_e5.tex", "w").write(tex)

# Aggregated table for all experiments (CSV, for the text and the response letters)
cols = ["crit_mean", "crit_p95", "crit_p99", "norm_mean", "norm_p95", "offload_frac", "edge_util_mean", "fog_util", "edge_to_fog_Bps",
        "edge_energy_J_mean", "fog_energy_J", "edge_power_W_mean", "fog_power_W", "crit_generated", "crit_done_by_gen_end_frac",
        "drain_ms", "crit_within_20ms", "crit_within_50ms", "crit_within_100ms"]
out = []
for exp, key in [("E1", "anomaly"), ("E2", "load"), ("E3", "threshold"), ("E4", "patients")]:
    s = d[d.exp == exp].groupby([key, "policy"])[cols].agg(["mean", "std"])
    s.columns = [f"{a}_{b}" for a, b in s.columns]; s = s.reset_index().rename(columns={key: "level"}); s.insert(0, "exp", exp); s.insert(1, "factor", key)
    out.append(s)
if len(e5):
    s = e5.groupby(["load", "fog_threshold", "policy"])[cols].agg(["mean", "std"])
    s.columns = [f"{a}_{b}" for a, b in s.columns]; s = s.reset_index()
    s.insert(0, "level", "load=" + s.pop("load").astype(str) + ";fog_threshold=" + s.pop("fog_threshold").astype(str))
    s.insert(0, "factor", "load,fog_threshold"); s.insert(0, "exp", "E5"); out.append(s)
pd.concat(out).round(4).to_csv(f"{OUT}/summary_by_config.csv", index=False)
print("ok")
