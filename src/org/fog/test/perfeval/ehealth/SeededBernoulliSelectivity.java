package org.fog.test.perfeval.ehealth;

import java.util.Random;
import org.fog.application.selectivity.SelectivityModel;

/** Bernoulli selectivity with an explicit seed (iFogSim's FractionalSelectivity uses Math.random). */
public class SeededBernoulliSelectivity implements SelectivityModel {
    private final double p;
    private final Random rnd;

    public SeededBernoulliSelectivity(double p, long seed) { this.p = p; this.rnd = new Random(seed); }
    @Override public boolean canSelect() { return rnd.nextDouble() < p; }
    @Override public double getMeanRate() { return p; }
    @Override public double getMaxRate() { return 1.0; }
}
