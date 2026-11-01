package org.fog.test.perfeval.ehealth;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-run metric collector. One simulation run per JVM, so static state is safe.
 *
 * v1.2.0:
 *  - the criticality label of every sample is decided when the sample is emitted, from a deterministic
 *    function of (seed, sensor, per-sensor sample index), so that the three policies label exactly the same
 *    samples as critical (previously one shared generator was drawn in the order in which filtering finished);
 *  - samples are generated in [0, GEN_END_MS); the simulation then drains until every generated sample has
 *    been fully processed, so no tuple is excluded from the latency statistics;
 *  - latency statistics use the samples emitted in [WARMUP_MS, GEN_END_MS).
 */
public final class Metrics {

    private Metrics() {}

    /** Discard samples emitted before this time (warm-up), ms. */
    public static double WARMUP_MS = 1000.0;
    /** Sensors stop emitting at this time (end of sample generation), ms. */
    public static double GEN_END_MS = 10000.0;
    /** Anomaly rate p and label seed. */
    public static double ANOMALY = 0.05;
    public static long LABEL_SEED = 1;
    /** Fraction of routine results summarised to the Cloud (also decided per sample). */
    public static double HISTORY_FRACTION = 0.01;

    private static int nextId = 1;
    // id -> {emitTime, patient, critical(0/1), sensorKey, index}
    private static final Map<Integer, double[]> emitted = new HashMap<>();
    private static final Map<Integer, Boolean> offloadedById = new HashMap<>();
    private static final Map<Integer, Double> utilById = new HashMap<>();

    // per delivered critical tuple: {patient, emitTime, latency, offloaded(0/1), utilAtDecision}
    public static final List<double[]> critical = new ArrayList<>();
    // per completed normal tuple: {patient, emitTime, latency}
    public static final List<double[]> normal = new ArrayList<>();
    // per decision: {patient, time, util, offloaded}
    public static final List<double[]> decisions = new ArrayList<>();
    // every critical label: {patient, sensorKey, index, emitTime}
    public static final List<double[]> labels = new ArrayList<>();

    /** Counters used to detect the end of the drain phase (all samples, warm-up included). */
    public static long emittedCount = 0, normalDoneCount = 0, criticalLabeledCount = 0, criticalDoneCount = 0;

    /** actualTupleId of the tuple whose results are being generated (set by CriticalityAwareFogDevice). */
    public static int currentSample = -1;

    /** SplitMix64 finaliser: a well-mixed 64-bit hash. */
    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform number in [0,1) determined only by the seed, the stream (salt), the sensor and the sample index. */
    static double uniform(long seed, long salt, long sensorKey, long index) {
        long h = mix(mix(mix(seed * 0x632BE59BD9B4E019L + salt) + sensorKey) + index);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Registers a sample at emission and decides its criticality label. Returns the sample id. */
    public static int newSample(int patient, long sensorKey, long index, double now) {
        int id = nextId++;
        boolean crit = uniform(LABEL_SEED, 1, sensorKey, index) < ANOMALY;
        emitted.put(id, new double[]{now, patient, crit ? 1 : 0, sensorKey, index});
        emittedCount++;
        if (crit) {
            criticalLabeledCount++;
            labels.add(new double[]{patient, sensorKey, index, now});
        }
        return id;
    }

    /** Label of the sample whose results are being generated (used by LabelSelectivity). */
    public static boolean isCritical(int id) {
        double[] e = emitted.get(id);
        if (e == null) throw new IllegalStateException("unknown sample " + id);
        return e[2] == 1;
    }

    /** Per-sample decision for the summaries sent to the Cloud (independent stream, same for all policies). */
    public static boolean isSummarised(int id) {
        double[] e = emitted.get(id);
        if (e == null) throw new IllegalStateException("unknown sample " + id);
        return uniform(LABEL_SEED, 2, (long) e[3], (long) e[4]) < HISTORY_FRACTION;
    }

    public static void decision(int id, int patient, double now, double util, boolean offload) {
        decisions.add(new double[]{patient, now, util, offload ? 1 : 0});
        utilById.put(id, util);
        offloadedById.put(id, offload);
    }

    public static void criticalDelivered(int id, double now) {
        double[] e = emitted.get(id);
        if (e == null) return;
        criticalDoneCount++;
        Boolean off = offloadedById.remove(id);
        Double u = utilById.remove(id);
        critical.add(new double[]{e[1], e[0], now - e[0], off != null && off ? 1 : 0, u == null ? -1 : u});
    }

    public static void normalCompleted(int id, double now) {
        double[] e = emitted.get(id);
        if (e == null) return;
        normalDoneCount++;
        normal.add(new double[]{e[1], e[0], now - e[0]});
    }

    /** True when every emitted sample has completed its routine processing and every critical result arrived. */
    public static boolean drained() {
        return normalDoneCount == emittedCount && criticalDoneCount == criticalLabeledCount;
    }

    private static boolean measured(double emit) { return emit >= WARMUP_MS && emit < GEN_END_MS; }

    /** Counts over the measured samples: {generated, completed by GEN_END, completed in total}. */
    public static long[] criticalCounts() {
        long gen = 0, byEnd = 0, done = 0;
        for (double[] l : labels) if (measured(l[3])) gen++;
        for (double[] r : critical) if (measured(r[1])) { done++; if (r[1] + r[2] <= GEN_END_MS) byEnd++; }
        return new long[]{gen, byEnd, done};
    }

    public static long[] normalCounts() {
        long gen = 0, byEnd = 0, done = 0;
        for (double[] e : emitted.values()) if (measured(e[0])) gen++;
        for (double[] r : normal) if (measured(r[1])) { done++; if (r[1] + r[2] <= GEN_END_MS) byEnd++; }
        return new long[]{gen, byEnd, done};
    }

    public static void writeCsv(String prefix) throws Exception {
        try (PrintWriter w = new PrintWriter(prefix + "_critical.csv")) {
            w.println("patient,emit_ms,latency_ms,offloaded,util_at_decision");
            for (double[] r : critical) if (measured(r[1]))
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f,%d,%.4f%n", (int) r[0], r[1], r[2], (int) r[3], r[4]);
        }
        try (PrintWriter w = new PrintWriter(prefix + "_normal.csv")) {
            w.println("patient,emit_ms,latency_ms");
            for (double[] r : normal) if (measured(r[1]))
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f%n", (int) r[0], r[1], r[2]);
        }
        try (PrintWriter w = new PrintWriter(prefix + "_decisions.csv")) {
            w.println("patient,time_ms,util,offloaded");
            for (double[] r : decisions)
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f,%d%n", (int) r[0], r[1], r[2], (int) r[3]);
        }
        // trace of the critical labels (sensor key and per-sensor index identify a sample across policies)
        try (PrintWriter w = new PrintWriter(prefix + "_labels.csv")) {
            w.println("patient,sensor,index,emit_ms");
            for (double[] l : labels)
                w.printf(java.util.Locale.ROOT, "%d,%d,%d,%.4f%n", (int) l[0], (long) l[1], (long) l[2], l[3]);
        }
    }
}
