package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Pure outcome record representing the result of an inventory mutation journal operation.
 */
public record InventoryMutationJournalOutcome(Status status, OptionalLong version, Optional<String> rejectionReason) {

    public InventoryMutationJournalOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(rejectionReason, "rejectionReason");
    }

    public enum Status {
        SUCCESS,
        REJECTED,
        CONFLICT
    }

    public static InventoryMutationJournalOutcome success(long version) {
        return new InventoryMutationJournalOutcome(Status.SUCCESS, OptionalLong.of(version), Optional.empty());
    }

    public static InventoryMutationJournalOutcome success() {
        return new InventoryMutationJournalOutcome(Status.SUCCESS, OptionalLong.empty(), Optional.empty());
    }

    public static InventoryMutationJournalOutcome intentRecorded() {
        return success();
    }

    public static InventoryMutationJournalOutcome rejected(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new InventoryMutationJournalOutcome(Status.REJECTED, OptionalLong.empty(), Optional.of(reason));
    }

    public static InventoryMutationJournalOutcome conflict(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new InventoryMutationJournalOutcome(Status.CONFLICT, OptionalLong.empty(), Optional.of(reason));
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isRejected() {
        return status == Status.REJECTED;
    }

    public boolean isConflict() {
        return status == Status.CONFLICT;
    }
}
