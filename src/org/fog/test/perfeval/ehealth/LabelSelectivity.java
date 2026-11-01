package org.fog.test.perfeval.ehealth;

import org.fog.application.selectivity.SelectivityModel;

/**
 * Selectivity that reads the per-sample decision taken at emission (Metrics), instead of drawing a new random
 * number when the module finishes. The sample being processed is set by CriticalityAwareFogDevice just before
 * iFogSim generates the resulting tuples. Two streams: CRITICAL labels and Cloud summaries (HISTORY).
 */
public class LabelSelectivity implements SelectivityModel {

    public enum Kind { CRITICAL, SUMMARY }

    private final Kind kind;
    private final double rate;

    public LabelSelectivity(Kind kind, double rate) { this.kind = kind; this.rate = rate; }

    @Override
    public boolean canSelect() {
        int id = Metrics.currentSample;
        if (id < 0) throw new IllegalStateException("LabelSelectivity used outside CriticalityAwareFogDevice");
        return kind == Kind.CRITICAL ? Metrics.isCritical(id) : Metrics.isSummarised(id);
    }

    @Override public double getMeanRate() { return rate; }
    @Override public double getMaxRate() { return 1.0; }
}
