package org.fog.test.perfeval.ehealth;

import java.util.Random;
import org.fog.utils.distribution.Distribution;

/** Exponential inter-arrival times (Poisson arrivals) with an explicit seed. */
public class SeededExponentialDistribution extends Distribution {
    private final double mean;
    private final Random rnd;

    public SeededExponentialDistribution(double mean, long seed) {
        this.mean = mean;
        this.rnd = new Random(seed);
    }
    @Override public double getNextValue() { return -mean * Math.log(1.0 - rnd.nextDouble()); }
    @Override public int getDistributionType() { return 4; }
    @Override public double getMeanInterTransmitTime() { return mean; }
}
