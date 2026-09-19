package com.uxplima.uxmskyblock.core.application.ward;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.ward.KineticRepulsionResult;
import com.uxplima.uxmskyblock.core.domain.ward.KineticRepulsionResult.RepulsedEntity;

/**
 * Enterprise kinetic ward service computing non-destructive repulsive impulse waves (Section 2.42).
 * Radiates outward forces pushing hostile entities away from teleport arrival coordinates
 * without deleting or despawning custom mobs, bosses, or named pets.
 */
public final class KineticWardService {

    public static final double DEFAULT_RADIUS = 5.0;
    public static final double DEFAULT_FORCE = 1.5;
    public static final double DEFAULT_VERTICAL_LIFT = 0.35;

    private final double radius;
    private final double force;
    private final double verticalLift;

    public KineticWardService() {
        this(DEFAULT_RADIUS, DEFAULT_FORCE, DEFAULT_VERTICAL_LIFT);
    }

    public KineticWardService(double radius, double force) {
        this(radius, force, DEFAULT_VERTICAL_LIFT);
    }

    public KineticWardService(double radius, double force, double verticalLift) {
        if (radius <= 0) {
            throw new IllegalArgumentException("Kinetic ward radius must be positive: " + radius);
        }
        if (force <= 0) {
            throw new IllegalArgumentException("Kinetic ward force must be positive: " + force);
        }
        this.radius = radius;
        this.force = force;
        this.verticalLift = verticalLift;
    }

    /**
     * Target representation for spatial kinetic repulsion calculation.
     */
    public record TargetEntity(String id, double x, double y, double z) {
        public TargetEntity {
            Objects.requireNonNull(id, "Target entity id must not be null");
        }
    }

    /**
     * Calculates repulsive velocities for all candidates within the kinetic wave radius.
     *
     * @param originX arrival X
     * @param originY arrival Y
     * @param originZ arrival Z
     * @param candidates list of nearby hostile candidates
     * @return result containing calculated impulse velocities for entities within radius
     */
    public KineticRepulsionResult calculateRepulsion(
            double originX, double originY, double originZ, List<TargetEntity> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");

        List<RepulsedEntity> repulsed = new ArrayList<>();
        double radiusSquared = radius * radius;

        for (TargetEntity entity : candidates) {
            double dx = entity.x() - originX;
            double dy = entity.y() - originY;
            double dz = entity.z() - originZ;
            double distSq = dx * dx + dz * dz; // horizontal distance check

            if (distSq > radiusSquared || Math.abs(dy) > radius) {
                continue;
            }

            double dist = Math.sqrt(distSq);
            double vx;
            double vz;
            if (dist < 1e-4) {
                // Directly on arrival center: push forward in default direction
                vx = force;
                vz = 0.0;
            } else {
                vx = (dx / dist) * force;
                vz = (dz / dist) * force;
            }
            double vy = verticalLift;

            repulsed.add(new RepulsedEntity(entity.id(), entity.x(), entity.y(), entity.z(), vx, vy, vz));
        }

        return KineticRepulsionResult.of(repulsed);
    }

    public double radius() {
        return radius;
    }

    public double force() {
        return force;
    }

    public double verticalLift() {
        return verticalLift;
    }
}
