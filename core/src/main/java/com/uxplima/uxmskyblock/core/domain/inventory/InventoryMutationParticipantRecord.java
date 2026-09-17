package com.uxplima.uxmskyblock.core.domain.inventory;

import java.time.Instant;
import java.util.Objects;

/**
 * Read model record representing a persisted participant in {@code inventory_mutation_participants}.
 */
public record InventoryMutationParticipantRecord(
        InventoryMutationOperationId operationId,
        int participantIndex,
        String inventoryType,
        String ownerRootType,
        String ownerRootId,
        long expectedVersion,
        String authorityType,
        String authorityId,
        long authorityEpoch,
        String beforeFingerprint,
        String afterFingerprint,
        ParticipantApplyState durableApplyState,
        String mutationDeltaPayload,
        Instant updatedAt) {

    public InventoryMutationParticipantRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(inventoryType, "inventoryType");
        Objects.requireNonNull(ownerRootType, "ownerRootType");
        Objects.requireNonNull(ownerRootId, "ownerRootId");
        Objects.requireNonNull(authorityType, "authorityType");
        Objects.requireNonNull(authorityId, "authorityId");
        Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
        Objects.requireNonNull(afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(durableApplyState, "durableApplyState");
        Objects.requireNonNull(mutationDeltaPayload, "mutationDeltaPayload");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public ParticipantApplyState applyState() {
        return durableApplyState;
    }
}
