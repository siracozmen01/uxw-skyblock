package com.uxplima.uxmskyblock.core.domain.reward;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed operation identity for an individual reward component delivery.
 *
 * <p>Enforces the Identity Hierarchy & Isolation Invariant:
 * Each component delivery is keyed by its own stable {@code componentOperationId} deterministically
 * derived from {@code grantId + componentIndex}, preventing collision across {@code InventoryMutationJournal},
 * {@code processed_operations}, SQL economic OCC, and external economy sagas.
 *
 * @param value underlying operation UUID
 */
public record RewardComponentOperationId(UUID value) implements Comparable<RewardComponentOperationId> {

    public RewardComponentOperationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static RewardComponentOperationId of(UUID value) {
        return new RewardComponentOperationId(value);
    }

    public static RewardComponentOperationId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new RewardComponentOperationId(UUID.fromString(value));
    }

    /**
     * Deterministically derives a stable component operation ID from the parent grant ID
     * and the zero-based component index.
     *
     * <p>Invariants:
     * <ul>
     *   <li>{@code same grant + same component} &rArr; {@code same component operation ID}</li>
     *   <li>{@code different components} &rArr; {@code different component operation IDs}</li>
     * </ul>
     *
     * @param grantId parent grant ID
     * @param componentIndex 0-based component index within the grant
     * @return deterministically derived operation ID
     */
    public static RewardComponentOperationId derive(RewardGrantId grantId, int componentIndex) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        if (componentIndex < 0) {
            throw new IllegalArgumentException("componentIndex must not be negative: " + componentIndex);
        }
        String seed = "uxm:reward-component:" + grantId.value() + ":" + componentIndex;
        UUID derived = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        return new RewardComponentOperationId(derived);
    }

    @Override
    public int compareTo(RewardComponentOperationId other) {
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
