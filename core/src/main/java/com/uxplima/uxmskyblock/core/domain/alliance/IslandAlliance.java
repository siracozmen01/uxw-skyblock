package com.uxplima.uxmskyblock.core.domain.alliance;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Domain entity representing an established bilateral alliance between two islands.
 *
 * @param id the unique alliance identifier
 * @param islandA the first island in the bilateral relationship
 * @param islandB the second island in the bilateral relationship
 * @param createdAt the timestamp when the alliance was established
 */
public record IslandAlliance(AllianceId id, IslandId islandA, IslandId islandB, Instant createdAt) {

    public IslandAlliance {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(islandA, "islandA must not be null");
        Objects.requireNonNull(islandB, "islandB must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (islandA.equals(islandB)) {
            throw new IllegalArgumentException("An island cannot ally with itself: " + islandA);
        }
    }

    /**
     * Factory method creating a canonical alliance record where islandA < islandB.
     */
    public static IslandAlliance canonical(AllianceId id, IslandId one, IslandId two, Instant createdAt) {
        Objects.requireNonNull(one, "one must not be null");
        Objects.requireNonNull(two, "two must not be null");
        if (one.compareTo(two) <= 0) {
            return new IslandAlliance(id, one, two, createdAt);
        } else {
            return new IslandAlliance(id, two, one, createdAt);
        }
    }

    public boolean involves(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandA.equals(islandId) || islandB.equals(islandId);
    }

    public IslandId getPartner(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (islandA.equals(islandId)) {
            return islandB;
        }
        if (islandB.equals(islandId)) {
            return islandA;
        }
        throw new IllegalArgumentException("Island " + islandId + " is not a participant in alliance " + id);
    }
}
