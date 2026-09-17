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
            Supplier<Result<MutationExecution<T>, String>> mutationSupplier) {
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
        InventoryMutationJournalOutcome commitOutcome = journalPort.commitMutation(
                playerUuid,
                profileId,
                nodeId,
                sessionEpoch,
                expectedVersion,
                operationId,
                execution.updatedInventoryNbt());

        if (!commitOutcome.isSuccess()) {
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
