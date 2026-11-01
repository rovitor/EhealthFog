package org.fog.test.perfeval.ehealth;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.Actuator;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacement;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.utils.Config;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

import org.fog.test.perfeval.ehealth.CriticalityAwareFogDevice.Policy;
import org.fog.test.perfeval.ehealth.CriticalityAwareFogDevice.Role;

/**
 * Revised E-Health experiment (one run per JVM).
 *
 * Usage (all arguments optional, key=value):
 *   java org.fog.test.perfeval.ehealth.EhealthFogV2 policy=CRITICALITY_AWARE threshold=0.7 window=100 \
 *        patients=50 edgeMips=3000|hetero anomaly=0.05 load=1.0 seed=1 simTime=20000 warmup=2000 \
 *        out=results/CA_p50_s1
 *
 * policy: LOCAL (all processing on the Edge, Edgewards-like baseline)
 *         ALWAYS_FOG (every CRITICAL tuple is sent to the Fog)
 *         CRITICALITY_AWARE (CRITICAL tuple sent to the Fog only if U_W(edge) >= threshold)
 */
public class EhealthFogV2 {

    static final String APP = "App_Salud";

    // ---------------- workload parameters (Table of parameters in the manuscript) -------------
    // Units. The simulation clock is in milliseconds (link latencies 1, 2 and 100 ms; simTime 10,000 ms) and
    // iFogSim computes execution time = length / MIPS in clock units. With capacities in MIPS (10^3 instructions
    // per ms), the lengths below are therefore in THOUSANDS of instructions (kI): CRITICAL_MI = 10,000 kI = 10 MI,
    // i.e. 10,000 / 3000 = 3.33 ms on an idle 3000-MIPS gateway and 1 ms on the 10,000-MIPS Fog node.
    // Tuple sizes are in bytes and bandwidths in bytes per ms (10,000 bytes/ms = 10 MB/s).
    static final double ECG_MEAN_MS = 5.0;       // mean inter-arrival at load = 1
    static final double OXI_MEAN_MS = 10.0;
    static final double SENSOR_MI = 1000, SENSOR_BYTES = 500;
    static final double RAW_MI = 1000, RAW_BYTES = 500;
    static final double NORMAL_MI = 4000, NORMAL_BYTES = 500;     // routine (trend/HRV) processing
    static double CRITICAL_MI = 2000; static final double CRITICAL_BYTES = 20000; // anomaly analysis on an ECG window
    static final double RESPONSE_MI = 100, RESPONSE_BYTES = 50;
    static final double ALARM_MI = 100, ALARM_BYTES = 50;
    static final int MODULE_MIPS = 100;
    static final double HISTORY_MI = 500, HISTORY_BYTES = 1000, HISTORY_SEL = 0.01;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String s : args) { String[] kv = s.split("=", 2); a.put(kv[0], kv.length > 1 ? kv[1] : ""); }
        Policy policy = Policy.valueOf(a.getOrDefault("policy", "CRITICALITY_AWARE"));
        double threshold = Double.parseDouble(a.getOrDefault("threshold", "0.7"));
        double window = Double.parseDouble(a.getOrDefault("window", "100"));
        int patients = Integer.parseInt(a.getOrDefault("patients", "50"));
        String edgeMips = a.getOrDefault("edgeMips", "3000");
        CRITICAL_MI = Double.parseDouble(a.getOrDefault("criticalMI", "10000"));
        long fogMips = Long.parseLong(a.getOrDefault("fogMips", "10000"));
        double anomaly = Double.parseDouble(a.getOrDefault("anomaly", "0.05"));
        double load = Double.parseDouble(a.getOrDefault("load", "1.0"));
        long seed = Long.parseLong(a.getOrDefault("seed", "1"));
        double simTime = Double.parseDouble(a.getOrDefault("simTime", "10000"));        // end of sample generation
        double drainCap = Double.parseDouble(a.getOrDefault("drainCap", "120000"));    // safety cap for the drain
        double warmup = Double.parseDouble(a.getOrDefault("warmup", "1000"));
        String out = a.getOrDefault("out", "results/run");
        EHealthController.HEARTBEAT_MS = Double.parseDouble(a.getOrDefault("heartbeat", "1000"));
        File parent = new File(out).getParentFile();
        if (parent != null) parent.mkdirs();

        CriticalityAwareFogDevice.POLICY = policy;
        CriticalityAwareFogDevice.THRESHOLD = threshold;
        CriticalityAwareFogDevice.WINDOW_MS = window;
        double fogThreshold = Double.parseDouble(a.getOrDefault("fogThreshold", "0.8"));
        CriticalityAwareFogDevice.FOG_THRESHOLD = fogThreshold;
        Metrics.WARMUP_MS = warmup;
        Metrics.GEN_END_MS = simTime;
        Metrics.ANOMALY = anomaly;
        Metrics.LABEL_SEED = seed;
        Metrics.HISTORY_FRACTION = HISTORY_SEL;
        // sensors stop at simTime; the run drains until every sample is processed (cap: simTime + drainCap)
        Config.MAX_SIMULATION_TIME = (int) (simTime + drainCap);

        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
        FogBroker broker = new FogBroker("broker");

        List<FogDevice> devices = new ArrayList<>();
        List<Sensor> sensors = new ArrayList<>();
        List<Actuator> actuators = new ArrayList<>();

        // Tier 4 - Cloud
        CriticalityAwareFogDevice cloud = createDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16.48, 13.32, Role.CLOUD, -1);
        cloud.setParentId(-1);
        devices.add(cloud);
        // Tier 3 - Fog (hospital server)
        CriticalityAwareFogDevice fog = createDevice("FogServer_Hospital", fogMips, 8192, 10000, 10000, 1, 0.0, 107.339, 83.4333, Role.FOG, -1);
        fog.setParentId(cloud.getId());
        fog.setUplinkLatency(100.0);
        devices.add(fog);

        int[] heteroMips = {2500, 3000, 4000};
        for (int i = 0; i < patients; i++) {
            long mips = edgeMips.equals("hetero") ? heteroMips[i % heteroMips.length] : Long.parseLong(edgeMips);
            // Tier 2 - Edge gateway
            CriticalityAwareFogDevice edge = createDevice("Edge_Paciente_" + i, mips, 2048, 10000, 10000, 2, 0.0, 87.53, 82.44, Role.EDGE, i);
            edge.setParentId(fog.getId());
            edge.setUplinkLatency(2.0);
            devices.add(edge);
            // Tier 1 - sensors and actuator
            Sensor ecg = new TrackedSensor("Sensor_ECG_" + i, "ECG", broker.getId(), APP,
                    new SeededExponentialDistribution(ECG_MEAN_MS / load, seed * 100003L + 2L * i), i, 0);
            ecg.setGatewayDeviceId(edge.getId());
            ecg.setLatency(1.0);
            Sensor oxi = new TrackedSensor("Sensor_Oximetro_" + i, "OXIMETRO", broker.getId(), APP,
                    new SeededExponentialDistribution(OXI_MEAN_MS / load, seed * 100003L + 2L * i + 1), i, 1);
            oxi.setGatewayDeviceId(edge.getId());
            oxi.setLatency(1.0);
            Actuator alarm = new Actuator("Alarma_" + i, broker.getId(), APP, "ALARMA_EMERGENCIA");
            alarm.setGatewayDeviceId(edge.getId());
            alarm.setLatency(1.0);
            sensors.add(ecg); sensors.add(oxi); actuators.add(alarm);
        }

        Application app = createApplication(broker.getId(), anomaly, seed);
        app.setUserId(broker.getId());

        // Controller FIRST: its constructor links parents and children (getChildrenIds).
        String runInfo = String.format(java.util.Locale.ROOT,
                "policy,%s%nthreshold,%.3f%nwindow_ms,%.1f%npatients,%d%nedge_mips,%s%nanomaly,%.4f%nload,%.3f%nseed,%d%nsim_ms,%.0f%nwarmup_ms,%.0f%ndrain_cap_ms,%.0f%nversion,1.2.0",
                policy, threshold, window, patients, edgeMips, anomaly, load, seed, simTime, warmup, drainCap)
                + String.format(java.util.Locale.ROOT, "%ncritical_mi,%.0f%nfog_mips,%d%nfog_threshold,%.2f", CRITICAL_MI, fogMips, fogThreshold);
        Controller controller = new EHealthController("master-controller", devices, sensors, actuators, out, runInfo);

        // Static placement, identical for the three policies; only the run-time tuple decision differs.
        ModuleMapping mapping = ModuleMapping.createModuleMapping();
        for (int i = 0; i < patients; i++) {
            String e = "Edge_Paciente_" + i;
            mapping.addModuleToDevice("client", e);
            mapping.addModuleToDevice("Filtering_Module", e);
            mapping.addModuleToDevice("Routine_Module", e);
            mapping.addModuleToDevice("Analysis_Module", e);
        }
        mapping.addModuleToDevice("Analysis_Module", "FogServer_Hospital");
        mapping.addModuleToDevice("Storage_Module", "cloud");
        ModulePlacement placement = new ModulePlacementMapping(devices, app, mapping);

        // iFogSim bug: ModulePlacement.canBeCreated() already creates the VM on the host as an admission
        // test, and LAUNCH_MODULE creates it again -> every module would appear twice on its host and
        // receive two CPU shares. Undo the admission-time creation; the module is created once at launch.
        for (FogDevice d : devices) {
            List<org.fog.application.AppModule> mods = placement.getDeviceToModuleMap().get(d.getId());
            if (mods == null) continue;
            for (org.fog.application.AppModule m : mods) d.getVmAllocationPolicy().deallocateHostForVm(m);
        }

        // Guard: abort if any module could not be deployed (otherwise the policies silently become identical)
        for (FogDevice d : devices) {
            Role r = ((CriticalityAwareFogDevice) d).getRole();
            int expected = r == Role.EDGE ? 4 : 1;
            List<org.fog.application.AppModule> mods = placement.getDeviceToModuleMap().get(d.getId());
            int got = mods == null ? 0 : mods.size();
            if (got != expected) {
                System.err.println("PLACEMENT ERROR: " + d.getName() + " has " + got + " modules, expected " + expected);
                System.exit(2);
            }
        }

        controller.submitApplication(app, placement);
        CloudSim.startSimulation();   // EHealthController writes the CSV files and exits
    }

    static Application createApplication(int userId, double anomaly, long seed) {
        Application app = Application.createApplication(APP, userId);
        // Nominal VM MIPS only matter for admission (4 modules must fit on a 2000-MIPS gateway);
        // at run time the host MIPS are shared equally among active modules (CriticalityAwareFogDevice).
        app.addAppModule("client", 10, MODULE_MIPS, 10000);
        app.addAppModule("Filtering_Module", 10, MODULE_MIPS, 10000);
        app.addAppModule("Routine_Module", 10, MODULE_MIPS, 10000);
        app.addAppModule("Analysis_Module", 10, MODULE_MIPS, 10000);
        app.addAppModule("Storage_Module", 10, MODULE_MIPS, 10000);

        app.addAppEdge("ECG", "client", SENSOR_MI, SENSOR_BYTES, "ECG", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("OXIMETRO", "client", SENSOR_MI, SENSOR_BYTES, "OXIMETRO", Tuple.UP, AppEdge.SENSOR);
        app.addAppEdge("client", "Filtering_Module", RAW_MI, RAW_BYTES, "RAW_DATA", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("Filtering_Module", "Routine_Module", NORMAL_MI, NORMAL_BYTES, "NORMAL", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("Filtering_Module", "Analysis_Module", CRITICAL_MI, CRITICAL_BYTES, "CRITICAL", Tuple.UP, AppEdge.MODULE);
        app.addAppEdge("Analysis_Module", "client", RESPONSE_MI, RESPONSE_BYTES, "RESPONSE", Tuple.DOWN, AppEdge.MODULE);
        app.addAppEdge("client", "ALARMA_EMERGENCIA", ALARM_MI, ALARM_BYTES, "ALARM", Tuple.DOWN, AppEdge.ACTUATOR);
        app.addAppEdge("Routine_Module", "Storage_Module", HISTORY_MI, HISTORY_BYTES, "HISTORY", Tuple.UP, AppEdge.MODULE);

        app.addTupleMapping("client", "ECG", "RAW_DATA", new FractionalSelectivity(1.0));
        app.addTupleMapping("client", "OXIMETRO", "RAW_DATA", new FractionalSelectivity(1.0));
        // every window gets routine processing; a fraction p is flagged as anomalous (criticality label).
        // v1.2.0: the label of each sample is decided at emission (Metrics.newSample), so the three policies
        // label exactly the same samples; LabelSelectivity only reads it.
        app.addTupleMapping("Filtering_Module", "RAW_DATA", "NORMAL", new FractionalSelectivity(1.0));
        app.addTupleMapping("Filtering_Module", "RAW_DATA", "CRITICAL", new LabelSelectivity(LabelSelectivity.Kind.CRITICAL, anomaly));
        app.addTupleMapping("Analysis_Module", "CRITICAL", "RESPONSE", new FractionalSelectivity(1.0));
        app.addTupleMapping("client", "RESPONSE", "ALARM", new FractionalSelectivity(1.0));
        app.addTupleMapping("Routine_Module", "NORMAL", "HISTORY", new LabelSelectivity(LabelSelectivity.Kind.SUMMARY, HISTORY_SEL));

        app.setLoops(new ArrayList<AppLoop>()); // latency is measured by Metrics, not by iFogSim loops
        return app;
    }

    static CriticalityAwareFogDevice createDevice(String name, long mips, int ram, long upBw, long downBw, int level,
            double ratePerMips, double busyPower, double idlePower, Role role, int patient) throws Exception {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));
        PowerHost host = new PowerHost(FogUtils.generateEntityId(), new RamProvisionerSimple(ram),
                new BwProvisionerSimple(10000), 1000000, peList, new VmSchedulerTimeShared(peList),
                new FogLinearPowerModel(busyPower, idlePower));
        List<Host> hostList = new ArrayList<>();
        hostList.add(host);
        FogDeviceCharacteristics ch = new FogDeviceCharacteristics("x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
        CriticalityAwareFogDevice d = new CriticalityAwareFogDevice(name, ch, new AppModuleAllocationPolicy(hostList),
                new LinkedList<>(), 10.0, upBw, downBw, 0, ratePerMips, role, patient);
        d.setLevel(level);
        return d;
    }
}
