package org.fog.test.perfeval.ehealth;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.utils.Config;
import org.fog.utils.FogEvents;
import org.fog.utils.NetworkUsageMonitor;

/** Controller that writes our metrics (instead of only iFogSim's console summary) and exits. */
public class EHealthController extends Controller {

    private final String outPrefix;
    private final String runInfo;

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
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == FogEvents.UPDATE_RESOURCE_USAGE) {
            // backlog = tuples waiting/executing per module: summed over Edges (and max per Edge) and on the Fog
            java.util.Map<String, Integer> edgeSum = new java.util.TreeMap<>(), edgeMax = new java.util.TreeMap<>();
            java.util.Map<String, Integer> fogQ = new java.util.TreeMap<>();
            for (FogDevice d : getFogDevices()) {
                CriticalityAwareFogDevice c = (CriticalityAwareFogDevice) d;
                for (java.util.Map.Entry<String, Integer> e : c.backlog().entrySet()) {
                    if (c.getRole() == CriticalityAwareFogDevice.Role.EDGE) {
                        edgeSum.merge(e.getKey(), e.getValue(), Integer::sum);
                        edgeMax.merge(e.getKey(), e.getValue(), Math::max);
                    } else if (c.getRole() == CriticalityAwareFogDevice.Role.FOG) {
                        fogQ.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
            }
            System.out.printf(Locale.ROOT, "[progreso] t_sim = %.0f ms de %d | t_real = %.1f s | cola Edge (suma) %s | cola Edge (max) %s | cola Fog %s%n",
                    CloudSim.clock(), Config.MAX_SIMULATION_TIME, (System.currentTimeMillis() - wallStart) / 1000.0,
                    edgeSum, edgeMax, fogQ);
            System.out.flush();
            send(getId(), HEARTBEAT_MS, FogEvents.UPDATE_RESOURCE_USAGE);
            return;
        }
        if (ev.getTag() != FogEvents.STOP_SIMULATION) { super.processEvent(ev); return; }
        CloudSim.stopSimulation();
        double T = Config.MAX_SIMULATION_TIME;
        try {
            Metrics.writeCsv(outPrefix);
            try (PrintWriter w = new PrintWriter(outPrefix + "_devices.csv")) {
                w.println("device,role,patient,mips,busy_ms,utilization,energy_Wms,critical_seen,critical_offloaded,executed_tuples,work_ms");
                for (FogDevice d : getFogDevices()) {
                    CriticalityAwareFogDevice c = (CriticalityAwareFogDevice) d;
                    double b = c.busyTime(T);
                    w.printf(Locale.ROOT, "%s,%s,%d,%.0f,%.3f,%.5f,%.3f,%d,%d,%d,%.3f%n", c.getName(), c.getRole(),
                            c.getPatientIndex(), (double) c.getHost().getTotalMips(), b, b / T, c.energy(T),
                            c.getCriticalSeen(), c.getCriticalOffloaded(), c.getExecutedTuples(),
                            c.getExecutedMI() / c.getHost().getTotalMips());
                }
            }
            try (PrintWriter w = new PrintWriter(outPrefix + "_run.csv")) {
                w.println("key,value");
                w.println(runInfo);
                // iFogSim metric: sum(link latency x tuple size) / T  (NOT bytes)
                w.printf(Locale.ROOT, "network_usage_ifogsim,%.3f%n", NetworkUsageMonitor.getNetworkUsage() / T);
                w.printf(Locale.ROOT, "network_bytes_edge_to_fog,%.0f%n", (double) NetworkBytes.edgeToFog);
                w.printf(Locale.ROOT, "network_bytes_total,%.0f%n", (double) NetworkBytes.total);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}
