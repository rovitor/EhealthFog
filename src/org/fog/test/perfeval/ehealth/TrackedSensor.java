package org.fog.test.perfeval.ehealth;

import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.application.AppEdge;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;
import org.fog.utils.distribution.Distribution;

/** Sensor that stamps each sample with a unique id and its emission time (for end-to-end latency). */
public class TrackedSensor extends Sensor {

    private final int patient;

    public TrackedSensor(String name, String tupleType, int userId, String appId,
                         Distribution dist, int patient) {
        super(name, tupleType, userId, appId, dist);
        this.patient = patient;
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
        t.setActualTupleId(Metrics.newSample(patient, CloudSim.clock()));
        send(getGatewayDeviceId(), getLatency(), FogEvents.TUPLE_ARRIVAL, t);
    }
}
