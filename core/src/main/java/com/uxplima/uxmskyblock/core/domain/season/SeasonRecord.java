package com.uxplima.uxmskyblock.core.domain.season;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain record representing a competitive Skyblock season and timeline boundaries.
 */
public record SeasonRecord(SeasonId id, String name, Instant startsAt, Instant endsAt, SeasonState state) {

    public SeasonRecord {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(startsAt, "startsAt cannot be null");
        Objects.requireNonNull(endsAt, "endsAt cannot be null");
        Objects.requireNonNull(state, "state cannot be null");
    }
}
