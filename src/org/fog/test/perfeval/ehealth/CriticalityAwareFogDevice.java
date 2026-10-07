package org.fog.test.perfeval.ehealth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.predicates.PredicateType;
import org.fog.application.AppModule;
import org.fog.application.Application;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Tuple;
import org.fog.utils.TimeKeeper;

/**
 * FogDevice with tuple-level, criticality-aware forwarding (CATF).
 *
 * Differences with respect to the stock iFogSim FogDevice:
 *  1. Decision per tuple at run time (not at module placement): on an EDGE node, a
 *     tuple of type CRITICAL addressed to Analysis_Module is forwarded to the parent
 *     Fog node when policy == ALWAYS_FOG, or when policy == CRITICALITY_AWARE and the
 *     sliding-window CPU utilisation of the Edge node is >= threshold.
 *  2. CPU sharing: stock iFogSim gives the full host MIPS to the first active module and
 *     0 MIPS to the rest (strict priority by VM order). Here all active modules share the
 *     host MIPS equally (processor sharing), which models a time-sharing gateway OS.
 *  3. Cloudlet completion is scheduled at the exact estimated finish time (stock
 *     PowerDatacenter only checks every schedulingInterval or at the next arrival, which
 *     quantises latencies to the sensor inter-arrival time).
 *  4. The Fog node returns DOWN tuples only to the child that hosts the destination
 *     module instance (stock iFogSim broadcasts to all children).
 *  5. Busy-time accounting for utilisation (sliding window W) and energy
 *     E = P_idle*T + (P_busy - P_idle)*T_busy.
 */
public class CriticalityAwareFogDevice extends FogDevice {

    public enum Role { EDGE, FOG, CLOUD }
    public enum Policy { LOCAL, ALWAYS_FOG, CRITICALITY_AWARE }

    // ---- global experiment configuration (set once from main) ----
    public static Policy POLICY = Policy.CRITICALITY_AWARE;
    public static double THRESHOLD = 0.70;   // CPU_THRESHOLD
    public static double WINDOW_MS = 100.0;  // W, sliding window for utilisation
    /** theta_F: the Fog accepts offloaded tuples only while its own utilisation is below this value
     *  (the Fog advertises U_F to its children; 1.0 disables the check). */
    public static double FOG_THRESHOLD = 0.8;

    public static final String CRITICAL = "CRITICAL";
    public static final String NORMAL = "NORMAL";
    public static final String RESPONSE = "RESPONSE";
    public static final String ANALYSIS = "Analysis_Module";

    private final Role role;
    private final int patientIndex; // -1 for Fog/Cloud

    // busy-time bookkeeping
    private boolean busy = false;
    private double busySince = 0.0;
    private double totalBusyTime = 0.0;
    private final Deque<double[]> busyIntervals = new ArrayDeque<>(); // [start, end]

    // counters
    private long criticalSeen = 0, criticalOffloaded = 0;
    private double executedMI = 0.0;   // diagnostic: work actually executed
    private long executedTuples = 0;

    public CriticalityAwareFogDevice(String name, FogDeviceCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval,
            double uplinkBandwidth, double downlinkBandwidth, double uplinkLatency, double ratePerMips,
            Role role, int patientIndex) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval,
                uplinkBandwidth, downlinkBandwidth, uplinkLatency, ratePerMips);
        this.role = role;
        this.patientIndex = patientIndex;
    }

    // ------------------------------------------------------------------
    // 1. Tuple-level decision
    // ------------------------------------------------------------------
    @Override
    protected void processTupleArrival(SimEvent ev) {
        Tuple tuple = (Tuple) ev.getData();
        double now = CloudSim.clock();

        if (role == Role.EDGE && tuple.getDirection() == Tuple.UP
                && CRITICAL.equals(tuple.getTupleType()) && ANALYSIS.equals(tuple.getDestModuleName())) {
            double u = windowUtilization(now);
            boolean offload;
            switch (POLICY) {
                case ALWAYS_FOG: offload = true; break;
                case CRITICALITY_AWARE: {
                    double uf = ((CriticalityAwareFogDevice) CloudSim.getEntity(getParentId())).windowUtilization(now);
                    offload = u >= THRESHOLD && uf < FOG_THRESHOLD;
                    break;
                }
                default: offload = false;
            }
            criticalSeen++;
            Metrics.decision(tuple.getActualTupleId(), patientIndex, now, u, offload);
            if (offload) {
                criticalOffloaded++;
                sendUp(tuple);           // bypass the local queue
                return;
            }
        }

        if (role == Role.EDGE && tuple.getDirection() == Tuple.DOWN && RESPONSE.equals(tuple.getTupleType())) {
            Metrics.criticalDelivered(tuple.getActualTupleId(), now);   // alarm trigger point
        }

        if (role == Role.FOG && tuple.getDirection() == Tuple.DOWN && !hostsModule(tuple)) {
            Integer targetVm = tuple.getModuleCopyMap().get(tuple.getDestModuleName());
            if (targetVm != null) {
                for (int childId : getChildrenIds()) {
                    FogDevice child = (FogDevice) CloudSim.getEntity(childId);
                    for (Vm vm : child.getHost().getVmList()) {
                        if (vm.getId() == targetVm) { sendDown(tuple, childId); return; }
                    }
                }
            }
        }
        super.processTupleArrival(ev);
        // Stock FogDevice.processTupleArrival de-allocates the first module's MIPS on EVERY arrival
        // (and re-allocates all host MIPS to it when possible). Restore the equal-share allocation.
        updateAllocatedMips(null);
    }

    /** Host VM list without duplicates (defensive; see EhealthFogV2 for the iFogSim double-creation bug). */
    private List<Vm> modules() {
        return new ArrayList<>(new java.util.LinkedHashSet<Vm>(getHost().getVmList()));
    }

    private boolean hostsModule(Tuple tuple) {
        List<String> mods = appToModulesMap.get(tuple.getAppId());
        return mods != null && mods.contains(tuple.getDestModuleName());
    }

    // ------------------------------------------------------------------
    // 2. Equal processor sharing among active modules
    // ------------------------------------------------------------------
    @Override
    protected void updateAllocatedMips(String incomingOperator) {
        double now = CloudSim.clock();
        // settle progress with the share that was in force until now
        for (Vm vm : modules()) {
            ((AppModule) vm).updateVmProcessing(now, getHost().getVmScheduler().getAllocatedMipsForVm(vm));
        }
        List<Vm> active = new ArrayList<>();
        for (Vm vm : modules()) {
            if (vm.getCloudletScheduler().runningCloudlets() > 0
                    || ((AppModule) vm).getName().equals(incomingOperator)) {
                active.add(vm);
            }
        }
        getHost().getVmScheduler().deallocatePesForAllVms();
        // tiny safety margin so that n * share never exceeds the host MIPS through rounding
        final double share = active.isEmpty() ? 0.0 : getHost().getTotalMips() * (1.0 - 1e-9) / (double) active.size();
        for (Vm vm : modules()) {
            final double mips = active.contains(vm) ? share : 0.0;
            List<Double> req = new ArrayList<>();
            req.add(mips);
            getHost().getVmScheduler().allocatePesForVm(vm, req);
            ((AppModule) vm).updateVmProcessing(now, getHost().getVmScheduler().getAllocatedMipsForVm(vm));
        }
        setBusy(!active.isEmpty(), now);
        scheduleNextCompletion(now);
    }

    // ------------------------------------------------------------------
    // 3. Exact completion events
    // ------------------------------------------------------------------
    @Override
    protected void updateCloudletProcessing() {
        double now = CloudSim.clock();
        for (Host host : getVmAllocationPolicy().<Host>getHostList()) {
            host.updateCloudletsProcessing(now);
        }
        setLastProcessTime(now);
        scheduleNextCompletion(now);
    }

    private void scheduleNextCompletion(double now) {
        double next = Double.MAX_VALUE;
        for (Vm vm : modules()) {
            double t = vm.getCloudletScheduler().updateCloudletsProcessing(now,
                    getHost().getVmScheduler().getAllocatedMipsForVm(vm));
            if (t > 0.0 && t < next) next = t;
            // finished cloudlets not yet collected -> collect them as soon as possible
            if (vm.getCloudletScheduler().isFinishedCloudlets()) next = now;
        }
        CloudSim.cancelAll(getId(), new PredicateType(CloudActionTags.VM_DATACENTER_EVENT));
        if (next != Double.MAX_VALUE && !Double.isInfinite(next)) {
            send(getId(), Math.max(next - now, CloudSim.getMinTimeBetweenEvents()),
                    CloudActionTags.VM_DATACENTER_EVENT);
        }
    }

    @Override
    protected void checkCloudletCompletion() {
        boolean completed = false;
        for (Host host : getVmAllocationPolicy().<Host>getHostList()) {
            for (Vm vm : host.getVmList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl == null) continue;
                    completed = true;
                    Tuple tuple = (Tuple) cl;
                    TimeKeeper.getInstance().tupleEndedExecution(tuple);
                    executedMI += tuple.getCloudletLength();
                    executedTuples++;
                    if (NORMAL.equals(tuple.getTupleType())) {
                        Metrics.normalCompleted(tuple.getActualTupleId(), CloudSim.clock());
                    }
                    Application application = getApplicationMap().get(tuple.getAppId());
                    List<Tuple> results = application.getResultantTuples(tuple.getDestModuleName(), tuple, getId(), vm.getId());
                    for (Tuple res : results) {
                        res.setModuleCopyMap(new HashMap<String, Integer>(tuple.getModuleCopyMap()));
                        res.getModuleCopyMap().put(((AppModule) vm).getName(), vm.getId());
                        updateTimingsOnSending(res);
                        sendToSelf(res);
                    }
                    sendNow(cl.getUserId(), CloudActionTags.CLOUDLET_RETURN, cl);
                }
            }
        }
        if (completed) updateAllocatedMips(null);
    }

    // ------------------------------------------------------------------
    // Byte counters (network cost of offloading)
    // ------------------------------------------------------------------
    @Override
    protected void sendUpFreeLink(Tuple tuple) {
        NetworkBytes.total += tuple.getCloudletFileSize();
        if (role == Role.EDGE) NetworkBytes.edgeToFog += tuple.getCloudletFileSize();
        super.sendUpFreeLink(tuple);
    }

    @Override
    protected void sendDownFreeLink(Tuple tuple, int childId) {
        NetworkBytes.total += tuple.getCloudletFileSize();
        super.sendDownFreeLink(tuple, childId);
    }

    // ------------------------------------------------------------------
    // 5. Utilisation and energy
    // ------------------------------------------------------------------
    private void setBusy(boolean nowBusy, double now) {
        if (nowBusy == busy) return;
        if (nowBusy) {
            busySince = now;
        } else {
            totalBusyTime += now - busySince;
            busyIntervals.addLast(new double[]{busySince, now});
        }
        busy = nowBusy;
    }

    /** U_W(t) = busy time in [t-W, t] / W  (Eq. 1 of the revised manuscript). */
    public double windowUtilization(double now) {
        double from = now - WINDOW_MS;
        while (!busyIntervals.isEmpty() && busyIntervals.peekFirst()[1] <= from) busyIntervals.pollFirst();
        double b = 0.0;
        for (double[] iv : busyIntervals) b += Math.max(0.0, iv[1] - Math.max(iv[0], from));
        if (busy) b += now - Math.max(busySince, from);
        double w = Math.min(WINDOW_MS, Math.max(now, 1e-9));
        return Math.min(1.0, b / w);
    }

    public double busyTime(double simEnd) {
        return totalBusyTime + (busy ? simEnd - busySince : 0.0);
    }

    public double energy(double simEnd) {
        double pIdle = getHost().getPowerModel().getPower(0.0);
        double pBusy = getHost().getPowerModel().getPower(1.0);
        return pIdle * simEnd + (pBusy - pIdle) * busyTime(simEnd);
    }

    /** Diagnostic: number of tuples queued in each module of this device. */
    public java.util.Map<String, Integer> backlog() {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        for (Vm vm : modules()) {
            m.merge(((AppModule) vm).getName(), vm.getCloudletScheduler().runningCloudlets(), Integer::sum);
        }
        return m;
    }

    public Role getRole() { return role; }
    public int getPatientIndex() { return patientIndex; }
    public long getCriticalSeen() { return criticalSeen; }
    public long getCriticalOffloaded() { return criticalOffloaded; }
    public double getExecutedMI() { return executedMI; }
    public long getExecutedTuples() { return executedTuples; }
}
