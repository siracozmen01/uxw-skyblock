package com.uxplima.uxmskyblock.core.domain.limit;

import java.util.Objects;

/**
 * Outcome of evaluating an island tile or entity placement against configured limits (Section 2.31).
 */
public sealed interface IslandLimitCheckResult {

    record Allowed(int current, int max) implements IslandLimitCheckResult {}

    record LimitReached(LimitType type, int current, int max) implements IslandLimitCheckResult {
        public LimitReached {
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    record Bypassed(LimitType type) implements IslandLimitCheckResult {
        public Bypassed {
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    record NoIsland() implements IslandLimitCheckResult {}
}
