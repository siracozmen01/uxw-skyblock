package com.uxplima.uxmskyblock.core.domain.home;

/**
 * Domain policy dictating maximum allowed homes per player profile.
 */
@FunctionalInterface
public interface HomeLimitPolicy {

    int maxHomesFor(int tier);

    static HomeLimitPolicy defaultPolicy() {
        return tier -> Math.max(1, 3 + (tier * 2));
    }
}
