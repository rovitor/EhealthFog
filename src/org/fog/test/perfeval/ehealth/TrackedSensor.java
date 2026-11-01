package org.fog.test.perfeval.ehealth;

import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.application.AppEdge;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.distribution.Distribution;

/**
 * Sensor that stamps each sample with a unique id and its emission time (for end-to-end latency), decides its
 * criticality label at emission (v1.2.0) and stops emitting at Metrics.GEN_END_MS so that the run can drain.
 */
public class TrackedSensor extends Sensor {

    private final int patient;
    private final long sensorKey;   // unique per sensor: 2 * patient + (0 = ECG, 1 = pulse oximeter)
    private long index = 0;         // per-sensor sample index

    public TrackedSensor(String name, String tupleType, int userId, String appId,
                         Distribution dist, int patient, int sensorSlot) {
        super(name, tupleType, userId, appId, dist);
        this.patient = patient;
        this.sensorKey = 2L * patient + sensorSlot;
    }

    @Override
    public void processEvent(SimEvent ev) {
        // end of sample generation: no new samples and no further emission events
        if (ev.getTag() == FogEvents.EMIT_TUPLE && CloudSim.clock() >= Metrics.GEN_END_MS) return;
        super.processEvent(ev);
    }

    @Override
    public void transmit() {
        AppEdge e = null;
        for (AppEdge edge : getApp().getEdges())
            if (edge.getSource().equals(getTupleType())) e = edge;
        Tuple t = new Tuple(getAppId(), FogUtils.generateTupleId(), Tuple.UP,
                (long) e.getTupleCpuLength(), 1, (long) e.getTupleNwLength(), getOutputSize(),
                new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
        t.setUserId(getUserId());
        t.setTupleType(getTupleType());
        t.setDestModuleName(e.getDestination());
        t.setSrcModuleName(getSensorName());
        t.setDestinationDeviceId(getGatewayDeviceId());
        // actualTupleId is copied by iFogSim to every tuple derived from this sample
        t.setActualTupleId(Metrics.newSample(patient, sensorKey, index++, CloudSim.clock()));
        send(getGatewayDeviceId(), getLatency(), FogEvents.TUPLE_ARRIVAL, t);
    }
}
