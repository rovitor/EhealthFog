#!/usr/bin/env python3
"""Paired differences of the mean latency of critical tuples (CriticalityAware minus each reference policy) with
95% t confidence intervals over the ten replications, for the ten distinct scenarios of E1, E2 and E4.
Usage: python3 paired_differences.py revision/results_summary.csv revision/figures   -> table_paired.tex"""
import sys, numpy as np, pandas as pd
from scipy import stats
SRC = sys.argv[1] if len(sys.argv) > 1 else "revision/results_summary.csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "revision/figures"
d = pd.read_csv(SRC)
scen = [("E2", "load", 0.8, "Load 0.8"), ("E2", "load", 1.0, "Load 1.0 ($p=5\\%$, base)"), ("E2", "load", 1.2, "Load 1.2"),
        ("E2", "load", 1.4, "Load 1.4"), ("E1", "anomaly", 0.01, "$p=1\\%$"), ("E1", "anomaly", 0.10, "$p=10\\%$"),
        ("E4", "patients", 25, "25 patients (het.)"), ("E4", "patients", 50, "50 patients (het.)"),
        ("E4", "patients", 75, "75 patients (het.)"), ("E4", "patients", 100, "100 patients (het.)")]
def ci(exp, key, lv, ref):
    s = d[(d.exp == exp) & (d[key] == lv)]
    a = s[s.policy == "CRITICALITY_AWARE"].sort_values("seed").crit_mean.values
    b = s[s.policy == ref].sort_values("seed").crit_mean.values
    x = a - b; m = x.mean(); h = stats.t.ppf(0.975, len(x) - 1) * x.std(ddof=1) / np.sqrt(len(x))
    return m, m - h, m + h
def fmt(m, lo, hi):
    f = (lambda v: f"{v:.2f}") if abs(m) < 100 else (lambda v: f"{v:,.0f}")
    mark = "" if lo * hi > 0 else "$^{\\dagger}$"
    g = lambda v: f(v).replace("-", "$-$")   # typographic minus sign
    return f"{g(m)} [{g(lo)}, {g(hi)}]{mark}"
rows = [f"{lab} & {fmt(*ci(e, k, v, 'LOCAL'))} & {fmt(*ci(e, k, v, 'ALWAYS_FOG'))} \\\\" for e, k, v, lab in scen]
tex = ("\\begin{table}[H]\n\\caption{Paired difference in the mean latency of critical tuples between \\policy{} and each reference policy "
       "(\\policy{} minus reference, ms): mean over the ten replications and 95\\% confidence interval. Negative values favor \\policy{}. "
       "$^{\\dagger}$ The interval includes zero.\\label{tab:paired}}\n\\small\n\\newcolumntype{C}{>{\\centering\\arraybackslash}X}\n"
       "\\begin{tabularx}{\\textwidth}{lCC}\n\\toprule\n\\textbf{Scenario} & \\textbf{vs. Edge-local} & \\textbf{vs. Always-Fog} \\\\\n\\midrule\n"
       + "\n".join(rows) + "\n\\bottomrule\n\\end{tabularx}\n\\end{table}\n")
open(f"{OUT}/table_paired.tex", "w").write(tex)
print(tex)
