package com.uxplima.uxmskyblock.core.domain.access;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a temporary access grant.
 *
 * @param value the underlying grant UUID
 */
public record GrantId(UUID value) implements Comparable<GrantId> {

    public GrantId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static GrantId random() {
        return new GrantId(UUID.randomUUID());
    }

    public static GrantId of(UUID value) {
        return new GrantId(value);
    }

    public static GrantId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new GrantId(UUID.fromString(value));
    }

    @Override
    public int compareTo(GrantId other) {
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
