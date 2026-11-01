package org.fog.test.perfeval.ehealth;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.NetworkUsageMonitor;

/**
 * Controller that writes our metrics (instead of only iFogSim's console summary) and exits.
 *
 * v1.2.0: samples are generated until GEN_END_MS; the run then continues until every generated sample has been
 * processed (drain), or until Config.MAX_SIMULATION_TIME as a safety cap. Utilization, energy and traffic are
 * measured over the same window as latency, [WARMUP_MS, GEN_END_MS], from snapshots taken at both instants.
 */
public class EHealthController extends Controller {

    /** Events of this controller (measurement-window snapshots and drain check). */
    private enum Tag implements org.cloudbus.cloudsim.core.CloudSimTags { SNAPSHOT_WARMUP, SNAPSHOT_GEN_END, DRAIN_CHECK }
    public static double DRAIN_CHECK_MS = 1.0;

    private final String outPrefix;
    private final String runInfo;

    // cumulative busy time of every device and bytes at the two ends of the measurement window
    private final Map<Integer, Double> busyAtWarmup = new HashMap<>(), busyAtGenEnd = new HashMap<>();
    private long bytesE2FAtWarmup, bytesTotAtWarmup, bytesE2FAtGenEnd, bytesTotAtGenEnd;

    public EHealthController(String name, List<FogDevice> devs, List<Sensor> sensors,
                             List<Actuator> acts, String outPrefix, String runInfo) {
        super(name, devs, sensors, acts);
        this.outPrefix = outPrefix;
        this.runInfo = runInfo;
    }

    public static double HEARTBEAT_MS = 1000.0;
    private final long wallStart = System.currentTimeMillis();

    @Override
    public void startEntity() {
        super.startEntity();
        send(getId(), HEARTBEAT_MS, FogEvents.UPDATE_RESOURCE_USAGE);   // progress heartbeat
        send(getId(), Metrics.WARMUP_MS, Tag.SNAPSHOT_WARMUP);
        send(getId(), Metrics.GEN_END_MS, Tag.SNAPSHOT_GEN_END);
    }

    private void snapshot(Map<Integer, Double> busy) {
        double now = CloudSim.clock();
        for (FogDevice d : getFogDevices()) busy.put(d.getId(), ((CriticalityAwareFogDevice) d).busyTime(now));
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() instanceof Tag) {
            switch ((Tag) ev.getTag()) {
            case SNAPSHOT_WARMUP:
                snapshot(busyAtWarmup);
                bytesE2FAtWarmup = NetworkBytes.edgeToFog; bytesTotAtWarmup = NetworkBytes.total;
                return;
            case SNAPSHOT_GEN_END:
                snapshot(busyAtGenEnd);
                bytesE2FAtGenEnd = NetworkBytes.edgeToFog; bytesTotAtGenEnd = NetworkBytes.total;
                send(getId(), DRAIN_CHECK_MS, Tag.DRAIN_CHECK);
                return;
            case DRAIN_CHECK:
                if (Metrics.drained()) finish();
                else send(getId(), DRAIN_CHECK_MS, Tag.DRAIN_CHECK);
                return;
            }
        }
        if (ev.getTag() == FogEvents.UPDATE_RESOURCE_USAGE) {
            progress();
            send(getId(), HEARTBEAT_MS, FogEvents.UPDATE_RESOURCE_USAGE);
            return;
        }
        if (ev.getTag() == FogEvents.STOP_SIMULATION) { finish(); return; }   // safety cap reached before the drain completed
        super.processEvent(ev);
    }

    private void progress() {
        // backlog = tuples waiting/executing per module: summed over Edges (and max per Edge) and on the Fog
        Map<String, Integer> edgeSum = new java.util.TreeMap<>(), edgeMax = new java.util.TreeMap<>();
        Map<String, Integer> fogQ = new java.util.TreeMap<>();
        for (FogDevice d : getFogDevices()) {
            CriticalityAwareFogDevice c = (CriticalityAwareFogDevice) d;
            for (Map.Entry<String, Integer> e : c.backlog().entrySet()) {
                if (c.getRole() == CriticalityAwareFogDevice.Role.EDGE) {
                    edgeSum.merge(e.getKey(), e.getValue(), Integer::sum);
                    edgeMax.merge(e.getKey(), e.getValue(), Math::max);
                } else if (c.getRole() == CriticalityAwareFogDevice.Role.FOG) {
                    fogQ.merge(e.getKey(), e.getValue(), Integer::sum);
                }
            }
        }
        System.out.printf(Locale.ROOT, "[progress] t_sim = %.0f ms (generation until %.0f) | t_real = %.1f s | Edge queue (sum) %s | Edge queue (max) %s | Fog queue %s%n",
                CloudSim.clock(), Metrics.GEN_END_MS, (System.currentTimeMillis() - wallStart) / 1000.0, edgeSum, edgeMax, fogQ);
        System.out.flush();
    }

    private void finish() {
        double end = CloudSim.clock();
        CloudSim.stopSimulation();
        double W = Metrics.WARMUP_MS, G = Metrics.GEN_END_MS, span = G - W;
        try {
            Metrics.writeCsv(outPrefix);
            try (PrintWriter w = new PrintWriter(outPrefix + "_devices.csv")) {
                w.println("device,role,patient,mips,busy_ms,utilization,energy_Wms,critical_seen,critical_offloaded,executed_tuples,work_ms");
                for (FogDevice d : getFogDevices()) {
                    CriticalityAwareFogDevice c = (CriticalityAwareFogDevice) d;
                    double b0 = busyAtWarmup.get(c.getId()), b1 = busyAtGenEnd.get(c.getId()), b = b1 - b0;
                    w.printf(Locale.ROOT, "%s,%s,%d,%.0f,%.3f,%.5f,%.3f,%d,%d,%d,%.3f%n", c.getName(), c.getRole(),
                            c.getPatientIndex(), (double) c.getHost().getTotalMips(), b, b / span, c.energy(W, G, b0, b1),
                            c.getCriticalSeen(), c.getCriticalOffloaded(), c.getExecutedTuples(),
                            c.getExecutedMI() / c.getHost().getTotalMips());
                }
            }
            long[] cc = Metrics.criticalCounts(), nc = Metrics.normalCounts();
            try (PrintWriter w = new PrintWriter(outPrefix + "_run.csv")) {
                w.println("key,value");
                w.println(runInfo);
                // iFogSim metric: sum(link latency x tuple size) / T  (NOT bytes); whole run
                w.printf(Locale.ROOT, "network_usage_ifogsim,%.3f%n", NetworkUsageMonitor.getNetworkUsage() / end);
                // bytes transmitted inside the measurement window [warm-up, end of generation]
                w.printf(Locale.ROOT, "network_bytes_edge_to_fog,%d%n", bytesE2FAtGenEnd - bytesE2FAtWarmup);
                w.printf(Locale.ROOT, "network_bytes_total,%d%n", bytesTotAtGenEnd - bytesTotAtWarmup);
                w.printf(Locale.ROOT, "measure_window_ms,%.0f%n", span);
                w.printf(Locale.ROOT, "end_ms,%.3f%n", end);
                w.printf(Locale.ROOT, "drained,%d%n", Metrics.drained() ? 1 : 0);
                w.printf(Locale.ROOT, "crit_generated,%d%ncrit_done_by_gen_end,%d%ncrit_done,%d%ncrit_pending_end,%d%n",
                        cc[0], cc[1], cc[2], cc[0] - cc[2]);
                w.printf(Locale.ROOT, "norm_generated,%d%nnorm_done_by_gen_end,%d%nnorm_done,%d%nnorm_pending_end,%d%n",
                        nc[0], nc[1], nc[2], nc[0] - nc[2]);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }
}
