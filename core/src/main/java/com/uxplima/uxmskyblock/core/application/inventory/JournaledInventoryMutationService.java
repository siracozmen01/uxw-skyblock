package com.uxplima.uxmskyblock.core.application.inventory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Coordinates economic, value-sensitive inventory mutations through the two-phase write-ahead journal.
 */
public final class JournaledInventoryMutationService {

    @SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
    public record MutationExecution<T>(T value, byte[] updatedInventoryNbt) {
        public MutationExecution {
            Objects.requireNonNull(updatedInventoryNbt, "updatedInventoryNbt must not be null");
        }
    }

    public record MutationSuccess<T>(T value, long committedVersion) {}

    private final InventoryMutationJournalPort journalPort;

    public JournaledInventoryMutationService(InventoryMutationJournalPort journalPort) {
        this.journalPort = Objects.requireNonNull(journalPort, "journalPort must not be null");
    }

    /**
     * Executes a journaled mutation using the two-phase write-ahead protocol:
     * 1. Records INTENT in the journal.
     * 2. Runs the in-memory mutation callback.
     * 3. Commits the mutation to the journal and bumps inventory version.
     * Rollback (ABORT) is executed if step 2 or 3 fails.
     *
     * <p>{@code compensation} undoes whatever the supplier did to the world, and it is required
     * rather than optional. The journal can abort its own intent, but it cannot take an item back
     * out of a player's inventory: a commit that fails after the mutation ran would otherwise leave
     * the item given and the ledger saying it never was. The reward delivery handler does exactly
     * this by hand, rolling back only the slots it changed, which is why it never used this service.
     */
    public <T> Result<MutationSuccess<T>, String> execute(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            long expectedVersion,
            InventoryMutationOperationId operationId,
            String operationType,
            String beforeFingerprint,
            String afterFingerprint,
            String payload,
            Duration expiryDuration,
            Supplier<Result<MutationExecution<T>, String>> mutationSupplier,
            Runnable compensation) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
        Objects.requireNonNull(afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expiryDuration, "expiryDuration");
        Objects.requireNonNull(mutationSupplier, "mutationSupplier");
        Objects.requireNonNull(compensation, "compensation");

        InventoryMutationJournalOutcome intentOutcome = journalPort.recordIntent(
                playerUuid,
                profileId,
                nodeId,
                sessionEpoch,
                expectedVersion,
                operationId,
                operationType,
                beforeFingerprint,
                afterFingerprint,
                payload,
                expiryDuration);

        if (!intentOutcome.isSuccess()) {
            return Result.err("Failed to record intent: "
                    + intentOutcome
                            .rejectionReason()
                            .orElse(intentOutcome.status().name()));
        }

        Result<MutationExecution<T>, String> execResult;
        try {
            execResult = mutationSupplier.get();
        } catch (Exception e) {
            journalPort.abortIntent(playerUuid, profileId, nodeId, sessionEpoch, operationId);
            return Result.err("Mutation action failed with exception: " + e.getMessage());
        }

        if (execResult.isErr()) {
            journalPort.abortIntent(playerUuid, profileId, nodeId, sessionEpoch, operationId);
            return Result.err("Mutation action failed: " + execResult.errorOrThrow());
        }

        MutationExecution<T> execution = execResult.orElseThrow();
        InventoryMutationJournalOutcome commitOutcome;
        try {
            commitOutcome = journalPort.commitMutation(
                    playerUuid,
                    profileId,
                    nodeId,
                    sessionEpoch,
                    expectedVersion,
                    operationId,
                    execution.updatedInventoryNbt());
        } catch (RuntimeException e) {
            // A commit that throws is a commit that did not happen, and the mutation already did.
            // The same undo a refused commit needs, for the same reason: leaving the item out there
            // with the ledger saying it was never given is a duplication, not a rollback.
            compensation.run();
            journalPort.abortIntent(playerUuid, profileId, nodeId, sessionEpoch, operationId);
            return Result.err("Failed to commit mutation: " + e.getMessage());
        }

        if (!commitOutcome.isSuccess()) {
            // The mutation already happened out there. Undoing the journal without undoing the world
            // leaves the item in the player's hands and the ledger saying it was never given, which
            // is a duplication rather than a rollback.
            compensation.run();
            journalPort.abortIntent(playerUuid, profileId, nodeId, sessionEpoch, operationId);
            return Result.err("Failed to commit mutation: "
                    + commitOutcome
                            .rejectionReason()
                            .orElse(commitOutcome.status().name()));
        }

        long committedVersion = commitOutcome.version().orElse(expectedVersion + 1);
        return Result.ok(new MutationSuccess<>(execution.value(), committedVersion));
    }
}
