package com.uxplima.uxmskyblock.bukkit.performance;

import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

/**
 * The server's tick rate, readable from any thread.
 *
 * <p>Folia ticks each region on its own and refuses the question off a region's thread. The reset
 * asks it once a second from a thread of no region, to decide how many chunks to clear, and the
 * refusal threw out of the reset before it began: the island stayed, and every later attempt was
 * told a reset was running.
 *
 * <p>A read on a region's thread is that region's rate and is kept. A read off every region answers
 * the rate kept last, and a full twenty until one has been read.
 */
public final class ServerTickRate implements DoubleSupplier {

    private static final double FULL = 20.0;

    private final Supplier<double[]> read;
    private volatile double last = FULL;

    public ServerTickRate() {
        this(Bukkit::getTPS);
    }

    /** Package private so a test can stand in for a server that answers only on a region. */
    ServerTickRate(Supplier<double[]> read) {
        this.read = Objects.requireNonNull(read, "read must not be null");
    }

    @Override
    public double getAsDouble() {
        try {
            double[] tps = read.get();
            if (tps != null && tps.length > 0) {
                last = tps[0];
            }
        } catch (UnsupportedOperationException offEveryRegion) {
            // Folia answers only on a region's thread; the rate read last stands in.
        }
        return last;
    }
}
