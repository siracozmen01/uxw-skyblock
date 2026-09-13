package com.uxplima.uxmskyblock.core.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identity value object for an Island aggregate root.
 *
 * <p>Implements {@link Comparable} using <strong>UUID Unsigned 128-Bit Binary Order</strong>
 * (comparing {@code Long.compareUnsigned(mostSigBits)} followed by
 * {@code Long.compareUnsigned(leastSigBits)}) as mandated by persistence specifications to prevent
 * distributed lock inversion cycles.
 *
 * @param value the underlying island aggregate UUID
 */
public record IslandId(UUID value) implements Comparable<IslandId> {

    public IslandId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static IslandId of(UUID value) {
        return new IslandId(value);
    }

    public static IslandId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new IslandId(UUID.fromString(value));
    }

    @Override
    public int compareTo(IslandId other) {
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
