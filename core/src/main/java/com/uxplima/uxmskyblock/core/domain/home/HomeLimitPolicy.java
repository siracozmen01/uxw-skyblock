package com.uxplima.uxmskyblock.core.domain.home;

/**
 * How many homes an allowance buys.
 *
 * <p>The allowance is a number the caller already worked out from whichever permission node the
 * player holds, and the operator names the node. No rank is named here, so a server with three
 * ranks and a server with nine are the same plugin.
 *
 * <p>The policy only bounds the number. It used to be {@code 3 + allowance * 2}, an arithmetic
 * ladder written in code: an operator who wrote five in their file got thirteen homes and no
 * explanation. The production policy has always been the pass through the configuration builds, so
 * that formula was reachable only from the short constructor, waiting for the next caller to use it.
 */
@FunctionalInterface
public interface HomeLimitPolicy {

    /**
     * Turns an allowance into the number of homes it buys.
     *
     * @param allowance the number the permission the player holds grants
     * @return how many homes they may keep
     */
    int maxHomesFor(int allowance);

    /** The allowance itself, never less than one, for a caller that brings no configuration. */
    static HomeLimitPolicy defaultPolicy() {
        return allowance -> Math.max(1, allowance);
    }
}
