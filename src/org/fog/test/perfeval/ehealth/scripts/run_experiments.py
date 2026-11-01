#!/usr/bin/env python3
"""
Runs the revised experimental campaign (one JVM per run, in parallel).

Usage (from the iFogSim project root, after compiling):
    python3 run_experiments.py --cp "build:jars/*:jars/commons-math3-3.5/*" --jobs 4 --sets E1 E2 E3 E4 E5
    python3 run_experiments.py --dry-run          # only prints the commands

Every run writes results/<set>/<name>_{critical,normal,decisions,devices,run}.csv
Existing results are skipped, so the script can be interrupted and resumed.
"""
import argparse, itertools, os, subprocess, sys
from concurrent.futures import ThreadPoolExecutor

POLICIES = ["LOCAL", "ALWAYS_FOG", "CRITICALITY_AWARE"]
SEEDS = range(1, 11)          # 10 independent replications
# Calibrated on 2 Oct 2026 (pilot + calibration sweep): critical analysis = 10,000 MI,
# Fog accepts offloads only while U_F < 0.8; samples generated for 10 s (first 1 s = warm-up), then drained (v1.2.0).
BASE = dict(threshold=0.7, fogThreshold=0.8, window=100, patients=50, edgeMips="3000",
            criticalMI=10000, fogMips=10000, anomaly=0.05, load=1.0, simTime=10000, warmup=1000)


def experiments():
    runs = []
    # E1 - main comparison: same critical workload, three policies, three anomaly rates (R1, R4 #1)
    for pol, p, s in itertools.product(POLICIES, [0.01, 0.05, 0.10], SEEDS):
        runs.append(("E1", dict(BASE, policy=pol, anomaly=p, seed=s)))
    # E2 - system load (R2 #9, R3 robustness)
    for pol, ld, s in itertools.product(POLICIES, [0.8, 1.0, 1.2, 1.4], SEEDS):
        runs.append(("E2", dict(BASE, policy=pol, load=ld, seed=s)))
    # E3 - threshold sensitivity (R1 "show it triggers", manuscript promised it)
    for th, s in itertools.product([0.5, 0.6, 0.7, 0.8, 0.9], SEEDS):
        runs.append(("E3", dict(BASE, policy="CRITICALITY_AWARE", threshold=th, load=1.2, seed=s)))
    # E4 - heterogeneous gateways and scale (R3 heterogeneity, "50 to 100 patients")
    for pol, n, s in itertools.product(POLICIES, [25, 50, 75, 100], SEEDS):
        runs.append(("E4", dict(BASE, policy=pol, patients=n, edgeMips="hetero", seed=s)))
    # E5 - Fog threshold sensitivity near saturation (2.0 = no Fog guard, since utilisation never exceeds 1)
    for ft, ld, s in itertools.product([0.6, 0.7, 0.8, 0.9, 1.0, 2.0], [1.2, 1.4], SEEDS):
        runs.append(("E5", dict(BASE, policy="CRITICALITY_AWARE", fogThreshold=ft, load=ld, seed=s)))
    return runs


def name_of(cfg):
    ft = "" if cfg["fogThreshold"] == 0.8 else f"_ft{cfg['fogThreshold']}"   # E1-E4 names unchanged
    return (f"{cfg['policy']}_n{cfg['patients']}_m{cfg['edgeMips']}_p{cfg['anomaly']}"
            f"_l{cfg['load']}_th{cfg['threshold']}{ft}_s{cfg['seed']}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cp", default="build:jars/*:jars/commons-math3-3.5/*")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    ap.add_argument("--sets", nargs="*", default=["E1", "E2", "E3", "E4", "E5"])
    ap.add_argument("--xmx", default="2g")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--timeout", type=int, default=3600, help="seconds per run before it is killed")
    a = ap.parse_args()

    # Configurations shared by several sets (e.g. the base configuration in E1 and E2) are run once: each run is
    # fully determined by its configuration and seed, so the other sets receive a copy of its output files.
    todo, first_out, copies = [], {}, []
    for exp, cfg in experiments():
        if exp not in a.sets:
            continue
        out = os.path.join("results", exp, name_of(cfg))
        if name_of(cfg) in first_out:
            copies.append((first_out[name_of(cfg)], out))
            continue
        first_out[name_of(cfg)] = out
        if os.path.exists(out + "_run.csv"):
            continue
        args = [f"{k}={v}" for k, v in cfg.items()] + [f"out={out}"]
        todo.append(["java", f"-Xmx{a.xmx}", "-cp", a.cp, "org.fog.test.perfeval.ehealth.EhealthFogV2", *args])

    print(f"{len(todo)} runs pending", file=sys.stderr)
    if a.dry_run:
        for c in todo:
            print(" ".join(c))
        return

    def run(cmd):
        try:
            r = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                               timeout=a.timeout)
            return cmd[-1], r.returncode, r.stderr[-500:]
        except subprocess.TimeoutExpired:
            return cmd[-1], -9, f"TIMEOUT after {a.timeout} s"

    done = 0
    with ThreadPoolExecutor(a.jobs) as ex:
        for out, rc, err in ex.map(run, todo):
            done += 1
            status = "ok" if rc == 0 else f"FAIL rc={rc} {err}"
            print(f"[{done}/{len(todo)}] {out} {status}", file=sys.stderr)

    import shutil
    for src, dst in copies:
        if os.path.exists(src + "_run.csv") and not os.path.exists(dst + "_run.csv"):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            for suf in ["_critical.csv", "_normal.csv", "_decisions.csv", "_labels.csv", "_devices.csv", "_run.csv"]:
                shutil.copyfile(src + suf, dst + suf)
    print(f"{len(copies)} shared configurations copied from their first run", file=sys.stderr)


if __name__ == "__main__":
    main()
