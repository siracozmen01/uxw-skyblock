package com.uxplima.uxmskyblock.core.domain.vault;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Write-ahead transfer intent journal entry recording dual-slot state and expected versions.
 */
@SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
public record EscrowTransferRecord(
        UUID transferId,
        VaultSessionId sessionId,
        TransferSourceType source,
        TransferSourceType destination,
        int sourceSlot,
        int destinationSlot,
        String sourceBeforeFingerprint,
        String sourceAfterFingerprint,
        String destinationBeforeFingerprint,
        String destinationAfterFingerprint,
        long sourceExpectedVersion,
        long destinationExpectedVersion,
        long sourceContainerVersion,
        long destinationContainerVersion,
        byte[] serializedItemNbt,
        int quantity,
        EscrowTransferState state,
        Instant createdAt,
        Instant updatedAt) {

    public EscrowTransferRecord {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(sourceBeforeFingerprint, "sourceBeforeFingerprint must not be null");
        Objects.requireNonNull(sourceAfterFingerprint, "sourceAfterFingerprint must not be null");
        Objects.requireNonNull(destinationBeforeFingerprint, "destinationBeforeFingerprint must not be null");
        Objects.requireNonNull(destinationAfterFingerprint, "destinationAfterFingerprint must not be null");
        Objects.requireNonNull(serializedItemNbt, "serializedItemNbt must not be null");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1: " + quantity);
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        serializedItemNbt = serializedItemNbt.clone();
    }

    @Override
    public byte[] serializedItemNbt() {
        return serializedItemNbt.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EscrowTransferRecord that)) return false;
        return sourceSlot == that.sourceSlot
                && destinationSlot == that.destinationSlot
                && sourceExpectedVersion == that.sourceExpectedVersion
                && destinationExpectedVersion == that.destinationExpectedVersion
                && sourceContainerVersion == that.sourceContainerVersion
                && destinationContainerVersion == that.destinationContainerVersion
                && quantity == that.quantity
                && Objects.equals(transferId, that.transferId)
                && Objects.equals(sessionId, that.sessionId)
                && source == that.source
                && destination == that.destination
                && Objects.equals(sourceBeforeFingerprint, that.sourceBeforeFingerprint)
                && Objects.equals(sourceAfterFingerprint, that.sourceAfterFingerprint)
                && Objects.equals(destinationBeforeFingerprint, that.destinationBeforeFingerprint)
                && Objects.equals(destinationAfterFingerprint, that.destinationAfterFingerprint)
                && Arrays.equals(serializedItemNbt, that.serializedItemNbt)
                && state == that.state
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                transferId,
                sessionId,
                source,
                destination,
                sourceSlot,
                destinationSlot,
                sourceBeforeFingerprint,
                sourceAfterFingerprint,
                destinationBeforeFingerprint,
                destinationAfterFingerprint,
                sourceExpectedVersion,
                destinationExpectedVersion,
                sourceContainerVersion,
                destinationContainerVersion,
                quantity,
                state,
                createdAt,
                updatedAt);
        result = 31 * result + Arrays.hashCode(serializedItemNbt);
        return result;
    }
}
