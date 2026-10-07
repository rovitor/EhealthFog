package org.fog.test.perfeval.ehealth;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-run metric collector. One simulation run per JVM, so static state is safe. */
public final class Metrics {

    private Metrics() {}

    /** Discard samples emitted before this time (warm-up), ms. */
    public static double WARMUP_MS = 1000.0;

    private static int nextId = 1;
    private static final Map<Integer, double[]> emitted = new HashMap<>(); // id -> {emitTime, patient}
    private static final Map<Integer, Boolean> offloadedById = new HashMap<>();

    // per completed critical tuple: {patient, emitTime, latency, offloaded(0/1), utilAtDecision}
    public static final List<double[]> critical = new ArrayList<>();
    // per completed normal tuple: {patient, emitTime, latency}
    public static final List<double[]> normal = new ArrayList<>();
    // per decision: {patient, time, util, offloaded}
    public static final List<double[]> decisions = new ArrayList<>();
    private static final Map<Integer, Double> utilById = new HashMap<>();

    public static int newSample(int patient, double now) {
        int id = nextId++;
        emitted.put(id, new double[]{now, patient});
        return id;
    }

    public static void decision(int id, int patient, double now, double util, boolean offload) {
        decisions.add(new double[]{patient, now, util, offload ? 1 : 0});
        utilById.put(id, util);
        offloadedById.put(id, offload);
    }

    public static void criticalDelivered(int id, double now) {
        double[] e = emitted.get(id);
        if (e == null) return;
        Boolean off = offloadedById.remove(id);
        Double u = utilById.remove(id);
        critical.add(new double[]{e[1], e[0], now - e[0], off != null && off ? 1 : 0, u == null ? -1 : u});
    }

    public static void normalCompleted(int id, double now) {
        double[] e = emitted.get(id);
        if (e == null) return;
        normal.add(new double[]{e[1], e[0], now - e[0]});
    }

    public static void writeCsv(String prefix) throws Exception {
        try (PrintWriter w = new PrintWriter(prefix + "_critical.csv")) {
            w.println("patient,emit_ms,latency_ms,offloaded,util_at_decision");
            for (double[] r : critical) if (r[1] >= WARMUP_MS)
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f,%d,%.4f%n", (int) r[0], r[1], r[2], (int) r[3], r[4]);
        }
        try (PrintWriter w = new PrintWriter(prefix + "_normal.csv")) {
            w.println("patient,emit_ms,latency_ms");
            for (double[] r : normal) if (r[1] >= WARMUP_MS)
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f%n", (int) r[0], r[1], r[2]);
        }
        try (PrintWriter w = new PrintWriter(prefix + "_decisions.csv")) {
            w.println("patient,time_ms,util,offloaded");
            for (double[] r : decisions)
                w.printf(java.util.Locale.ROOT, "%d,%.3f,%.4f,%d%n", (int) r[0], r[1], r[2], (int) r[3]);
        }
    }
}
