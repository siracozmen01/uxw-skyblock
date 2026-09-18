package com.uxplima.uxmskyblock.core.domain.alliance;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identity value object for an Island Alliance relationship.
 *
 * @param value the underlying alliance UUID
 */
public record AllianceId(UUID value) implements Comparable<AllianceId> {

    public AllianceId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static AllianceId random() {
        return new AllianceId(UUID.randomUUID());
    }

    public static AllianceId of(UUID value) {
        return new AllianceId(value);
    }

    public static AllianceId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new AllianceId(UUID.fromString(value));
    }

    @Override
    public int compareTo(AllianceId other) {
        int msbComparison =
                Long.compareUnsigned(this.value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (msbComparison != 0) {
            return msbComparison;
        }
        return Long.compareUnsigned(this.value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
