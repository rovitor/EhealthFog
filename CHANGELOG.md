# Changelog of the E-Health extension

## v1.2.0 (October 2026) - second revision round

### Fixed
- Criticality labels: the label of a sample was drawn from one seeded generator shared by all gateways, at the
  moment its filtering finished. Since the policy changes the order in which the 50 gateways finish filtering, the
  same seed labelled different samples under different policies (the number of critical samples was the same).
  The label is now decided when the sample is emitted, from a deterministic hash of (seed, sensor, per-sensor
  sample index) (`Metrics.newSample`, `LabelSelectivity`), so the three policies label exactly the same samples.
  The Cloud summaries (1% of routine results) use the same mechanism with an independent stream.
- Tasks pending at the end of a run: the simulation stopped at a fixed time and only completed tuples entered the
  statistics. Sensors now stop emitting at `simTime` and the run continues until every generated sample has been
  processed (drain), with `drainCap` (default 120 s) as a safety cap; `_run.csv` reports critical and normal
  tuples generated, completed by the end of generation, completed and still pending.
- Measurement interval: the warm-up was excluded only from latency. Utilization, energy and traffic are now
  computed from snapshots taken at the end of the warm-up and at the end of sample generation, i.e. over the same
  interval as latency.

### Added
- `_labels.csv` per run (identifiers of the critical samples) and `scripts/compare_traces.py`, which checks that
  the three policies label the same samples and compares the latency of each critical sample across policies.
- Deadline metrics in `summarize_runs.py`: fraction of generated critical tuples delivered within 20, 50 and
  100 ms; mean power; drain duration.
- `make_figures.py`: logarithmic latency axis with open markers for configurations with a saturated Fog node,
  American spelling, fonts of at least 8 pt, 600-dpi RGB PNG, and the new table of generated/completed tuples.
- `run_experiments.py`: configurations shared by several experiment sets are run once and copied.

### Documented
- Units: the simulation clock is in milliseconds, so task lengths in the code are thousands of instructions
  (e.g. `criticalMI=10000` = 10 MI, 3.33 ms on a 3000-MIPS gateway) and bandwidths are bytes per ms.

## v1.1.1 (October 2026) - Fog threshold sensitivity

### Added
- Experiment E5: sensitivity of `CRITICALITY_AWARE` to the Fog threshold (`fogThreshold` 0.6, 0.7, 0.8, 0.9, 1.0 and
  2.0 = no Fog guard) at loads 1.2 and 1.4, 10 seeds (120 runs). The campaign now has 500 runs.
- `make_figures.py` writes Table 7 of the revised manuscript (`table_e5.tex`) and the E5 rows of
  `summary_by_config.csv`.

### Changed
- `run_experiments.py`: run names include `_ft<value>` when the Fog threshold differs from 0.8 (names of E1-E4 are
  unchanged, so existing results are reused).
- `revision/results_summary.csv` and `revision/latency_hist.csv` include the 120 runs of E5. The rows of E1-E4 are
  unchanged; E5 with `fogThreshold=0.8` reproduces the corresponding E2 runs exactly.

## v1.1.0 (October 2026) - version of the revised manuscript

### Fixed
- The baseline placement policy (`ModulePlacementEdgewards`) was created before the `Controller`. The controller
  builds the parent-child links of the topology, so the policy found no children and placed no modules; the
  baseline processed no data.
- The analysis module was installed on only one of the 50 gateways, and the CPU threshold was evaluated only once,
  at deployment time, with all devices idle. Critical data of the other 49 patients were never analysed.
- iFogSim creates each module twice (`canBeCreated` and `LAUNCH_MODULE`), so each module received two processor
  shares. The instance created by the admission test is now removed.

### Changed
- The forwarding decision is taken for every critical tuple when it arrives at a gateway
  (`CriticalityAwareFogDevice`): forward to the Fog node only if the gateway utilisation over the last 100 ms is at
  least `threshold` and the utilisation advertised by the Fog node is below `fogThreshold`.
- The analysis module is deployed on every gateway and on the Fog node.
- Processor capacity is shared equally among the modules with pending work (upstream gives all of it to the first
  active module), completion times are exact, and results return by unicast to the originating gateway.
- Policy renamed from `ModulePlacementCriticalityAware` to `CriticalityAware`; it is no longer a module-placement
  policy.

### Added
- Scenario `EhealthFogV2` with three policies (`LOCAL`, `ALWAYS_FOG`, `CRITICALITY_AWARE`), seeded Poisson arrivals
  and anomaly labels, heterogeneous gateways and key=value parameters.
- Per-tuple end-to-end latency, transmitted bytes, busy-time energy and per-run CSV output.
- Campaign scripts (E1-E4, 380 runs), summary and figure scripts, and the per-run summary of the results.

### Removed
- `EhealthFog.java`, `EhealthFogBaseline.java`, `ModulePlacementCriticalityAware.java` and the plotting scripts of
  v1.0.0 (still available under the tag `v1.0.0`).

## v1.0.0 (September 2026) - first submission
- Initial version. Do not use: see the fixes in v1.1.0.
