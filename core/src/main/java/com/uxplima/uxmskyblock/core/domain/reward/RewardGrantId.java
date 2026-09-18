package com.uxplima.uxmskyblock.core.domain.reward;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identifier for a parent reward grant aggregate.
 *
 * @param value underlying grant UUID
 */
public record RewardGrantId(UUID value) implements Comparable<RewardGrantId> {

    public RewardGrantId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RewardGrantId random() {
        return new RewardGrantId(UUID.randomUUID());
    }

    public static RewardGrantId of(UUID value) {
        return new RewardGrantId(value);
    }

    public static RewardGrantId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new RewardGrantId(UUID.fromString(value));
    }

    @Override
    public int compareTo(RewardGrantId other) {
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
