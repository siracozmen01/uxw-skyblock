package com.uxplima.uxmskyblock.core.application.tradewinds;

/**
 * How a vessel's standing in a port moves its prices: every {@code step} of trade done there is one step,
 * and each step pays {@code percent} more for what the crew sells and asks that much less for what it
 * buys, up to {@code maxSteps}.
 *
 * <p>A price paid never passes the price asked, so no standing turns a port into a source of money.
 */
public record Standing(long step, int percent, int maxSteps) {

    /** No standing: every port prices every vessel alike. */
    public static final Standing NONE = new Standing(1, 0, 0);

    public Standing {
        if (step < 1) {
            throw new IllegalArgumentException("a step of standing is at least 1");
        }
        if (percent < 0 || percent > 50) {
            throw new IllegalArgumentException("a step moves a price from 0 to 50 percent");
        }
        if (maxSteps < 0 || (long) maxSteps * percent > 50) {
            throw new IllegalArgumentException("all steps together move a price by at most 50 percent");
        }
    }

    /** How many steps {@code standing} is worth. */
    public int steps(long standing) {
        return (int) Math.min(maxSteps, Math.max(0, standing) / step);
    }

    /** What the port pays for {@code good}, per item, to a vessel of {@code standing}. */
    public long pays(Port.Good good, long standing) {
        long raised = good.pays() * (100L + (long) percent * steps(standing)) / 100L;
        return good.sold() ? Math.min(raised, asks(good, standing)) : raised;
    }

    /** What the port asks for {@code good}, per item, of a vessel of {@code standing}. */
    public long asks(Port.Good good, long standing) {
        long lowered = good.asks() * (100L - (long) percent * steps(standing)) / 100L;
        return Math.max(lowered, good.pays());
    }
}
