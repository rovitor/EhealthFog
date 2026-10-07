package org.fog.test.perfeval.ehealth;

/** Real transmitted bytes (iFogSim's "network usage" is latency x size / T, not bytes). */
public final class NetworkBytes {
    private NetworkBytes() {}
    public static long total = 0;
    public static long edgeToFog = 0;
}
