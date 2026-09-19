package com.uxplima.uxmskyblock.core.domain.ward;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain model representing entities and repulsive velocities produced by a kinetic ward wave (Section 2.42).
 */
public record KineticRepulsionResult(List<RepulsedEntity> repulsedEntities) {

    public KineticRepulsionResult {
        Objects.requireNonNull(repulsedEntities, "repulsedEntities must not be null");
        repulsedEntities = Collections.unmodifiableList(repulsedEntities);
    }

    public static KineticRepulsionResult of(List<RepulsedEntity> list) {
        return new KineticRepulsionResult(list);
    }

    public static KineticRepulsionResult empty() {
        return new KineticRepulsionResult(Collections.emptyList());
    }

    public int count() {
        return repulsedEntities.size();
    }

    public record RepulsedEntity(
            String entityId,
            double currentX,
            double currentY,
            double currentZ,
            double velocityX,
            double velocityY,
            double velocityZ) {}
}
