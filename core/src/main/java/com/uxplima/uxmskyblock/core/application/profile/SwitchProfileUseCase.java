package com.uxplima.uxmskyblock.core.application.profile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Application use-case orchestrating the crash-consistent 2-phase player profile switch protocol.
 */
public final class SwitchProfileUseCase {

    @SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
    public record PreparedSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            byte[] targetInventoryNbt,
            @Nullable ProfileInventoryRecord targetRecord) {

        public PreparedSwitch(
                UUID operationId,
                PlayerUuid playerId,
                ProfileId toProfileId,
                ServerNodeId currentNode,
                long expectedEpoch,
                byte[] targetInventoryNbt) {
            this(operationId, playerId, toProfileId, toProfileId, currentNode, expectedEpoch, targetInventoryNbt, null);
        }

        public PreparedSwitch(
                UUID operationId,
                PlayerUuid playerId,
                ProfileId fromProfileId,
                ProfileId toProfileId,
                ServerNodeId currentNode,
                long expectedEpoch,
                byte[] targetInventoryNbt) {
            this(
                    operationId,
                    playerId,
                    fromProfileId,
                    toProfileId,
                    currentNode,
                    expectedEpoch,
                    targetInventoryNbt,
                    null);
        }

        public PreparedSwitch {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(currentNode, "currentNode must not be null");
            Objects.requireNonNull(targetInventoryNbt, "targetInventoryNbt must not be null");
        }
    }

    private final ProfileSwitchPort profileSwitchPort;
    private final ProfileInventoryCheckpointPort inventoryCheckpointPort;
    private final @Nullable OutboxPort outboxPort;

    public SwitchProfileUseCase(
            ProfileSwitchPort profileSwitchPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            @Nullable OutboxPort outboxPort) {
        this.profileSwitchPort = Objects.requireNonNull(profileSwitchPort, "profileSwitchPort must not be null");
        this.inventoryCheckpointPort =
                Objects.requireNonNull(inventoryCheckpointPort, "inventoryCheckpointPort must not be null");
        this.outboxPort = outboxPort;
    }

    public SwitchProfileUseCase(
            ProfileSwitchPort profileSwitchPort, ProfileInventoryCheckpointPort inventoryCheckpointPort) {
        this(profileSwitchPort, inventoryCheckpointPort, null);
    }

    /**
     * Executes Phase 1 of profile switching: reserves the CAS switch slot, saves the source snapshot,
     * reads target inventory, and commits target apply intent.
     *
     * @param operationId unique switch operation ID
     * @param playerId player UUID
     * @param fromProfileId current active profile ID
     * @param toProfileId destination profile ID
     * @param currentNode claiming server node ID
     * @param expectedEpoch expected session epoch
     * @param sourceSnapshot serialized source inventory snapshot
     * @return result carrying prepared switch state with target inventory payload
     */
    public Result<PreparedSwitch, String> prepareSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            byte[] sourceSnapshot) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
        Objects.requireNonNull(toProfileId, "toProfileId must not be null");
        Objects.requireNonNull(currentNode, "currentNode must not be null");
        Objects.requireNonNull(sourceSnapshot, "sourceSnapshot must not be null");

        if (fromProfileId.equals(toProfileId)) {
            return Result.err("Cannot switch to currently active profile");
        }

        Result<?, String> reserveRes = profileSwitchPort.reserveSwitch(
                operationId, playerId, fromProfileId, toProfileId, currentNode, expectedEpoch);
        if (reserveRes.isErr()) {
            return Result.err("Failed to reserve switch: " + reserveRes.errorOrThrow());
        }

        Result<?, String> srcRes = profileSwitchPort.recordSourceSnapshot(operationId, sourceSnapshot);
        if (srcRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    operationId, playerId, "Failed to save source snapshot: " + srcRes.errorOrThrow());
            return Result.err(srcRes.errorOrThrow());
        }

        Optional<ProfileInventoryRecord> optTarget = inventoryCheckpointPort.loadInventory(toProfileId);
        byte[] targetNbt = optTarget.map(ProfileInventoryRecord::inventoryNbt).orElseGet(() -> new byte[0]);

        Result<?, String> targetLoadedRes = profileSwitchPort.recordTargetLoaded(operationId, targetNbt);
        if (targetLoadedRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    operationId, playerId, "Failed to load target inventory: " + targetLoadedRes.errorOrThrow());
            return Result.err(targetLoadedRes.errorOrThrow());
        }

        Result<?, String> intentRes = profileSwitchPort.recordTargetApplyIntent(operationId);
        if (intentRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    operationId, playerId, "Failed to record apply intent: " + intentRes.errorOrThrow());
            return Result.err(intentRes.errorOrThrow());
        }

        return Result.ok(new PreparedSwitch(
                operationId,
                playerId,
                fromProfileId,
                toProfileId,
                currentNode,
                expectedEpoch,
                targetNbt,
                optTarget.orElse(null)));
    }

    /**
     * Executes Phase 2 of profile switching after target inventory has been applied to player:
     * records player applied and commits the switch to database.
     *
     * @param prepared prepared switch state from Phase 1
     * @return success unit or error reason
     */
    public Result<Unit, String> completeSwitch(PreparedSwitch prepared) {
        Objects.requireNonNull(prepared, "prepared must not be null");

        Result<?, String> appliedRes = profileSwitchPort.recordPlayerApplied(prepared.operationId());
        if (appliedRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    prepared.operationId(),
                    prepared.playerId(),
                    "Failed to record player applied: " + appliedRes.errorOrThrow());
            return Result.err(appliedRes.errorOrThrow());
        }

        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "PROFILE_SWITCHED",
                        prepared.playerId().value().toString(),
                        String.format(
                                "{\"playerId\":\"%s\",\"fromProfileId\":\"%s\",\"toProfileId\":\"%s\",\"operationId\":\"%s\"}",
                                prepared.playerId().value(),
                                prepared.fromProfileId().value(),
                                prepared.toProfileId().value(),
                                prepared.operationId()))
                : null;

        Result<?, String> commitRes = profileSwitchPort.commitSwitch(
                prepared.operationId(),
                prepared.playerId(),
                prepared.toProfileId(),
                prepared.currentNode(),
                prepared.expectedEpoch(),
                outboxEvent);
        if (commitRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    prepared.operationId(),
                    prepared.playerId(),
                    "Failed to commit switch: " + commitRes.errorOrThrow());
            return Result.err(commitRes.errorOrThrow());
        }

        if (outboxPort != null && outboxEvent != null) {
            outboxPort.stageEvent(
                    outboxEvent.id(), outboxEvent.eventType(), outboxEvent.aggregateId(), outboxEvent.payload());
        }

        return Result.ok(Unit.INSTANCE);
    }

    /**
     * Executes crash recovery on any in-flight profile switch operation found for the player on connect.
     *
     * <p>States PREPARING, SOURCE_SNAPSHOTTED, and TARGET_LOADED are rolled back safely to the source profile.
     * States TARGET_APPLY_INTENT and PLAYER_APPLIED are rolled forward to target profile.
     */
    public Result<Optional<ProfileId>, String> recoverInFlightSwitch(
            PlayerUuid playerId, ServerNodeId currentNode, long expectedEpoch) {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(currentNode, "currentNode must not be null");

        Optional<ProfileSwitchOperation> optOp = profileSwitchPort.findActiveOperation(playerId);
        if (optOp.isEmpty()) {
            return Result.ok(Optional.empty());
        }

        return recoverInFlightSwitch(playerId, optOp.get(), currentNode, expectedEpoch);
    }

    /**
     * Executes crash recovery on any in-flight profile switch operation found for the player on connect.
     *
     * <p>States PREPARING, SOURCE_SNAPSHOTTED, and TARGET_LOADED are rolled back safely to the source profile.
     * States TARGET_APPLY_INTENT and PLAYER_APPLIED are rolled forward to target profile.
     */
    public Result<Optional<ProfileId>, String> recoverInFlightSwitch(
            PlayerUuid playerId, ProfileSwitchOperation op, ServerNodeId currentNode, long expectedEpoch) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(currentNode, "currentNode");

        if (op instanceof ProfileSwitchOperation.Preparing
                || op instanceof ProfileSwitchOperation.SourceSnapshotted
                || op instanceof ProfileSwitchOperation.TargetLoaded) {
            Result<Unit, String> abortRes = profileSwitchPort.abortSwitch(
                    op.operationId(), playerId, "Startup crash recovery: rolling back incomplete switch");
            if (abortRes.isErr()) {
                return Result.err("Failed to abort incomplete switch during recovery: " + abortRes.errorOrThrow());
            }
            return Result.ok(Optional.of(op.fromProfileId()));
        } else if (op instanceof ProfileSwitchOperation.TargetApplyIntent
                || op instanceof ProfileSwitchOperation.PlayerApplied) {
            StagedOutboxEvent outboxEvent = (outboxPort != null)
                    ? new StagedOutboxEvent(
                            EventId.random(),
                            "PROFILE_SWITCHED",
                            playerId.value().toString(),
                            String.format(
                                    "{\"playerId\":\"%s\",\"fromProfileId\":\"%s\",\"toProfileId\":\"%s\",\"operationId\":\"%s\"}",
                                    playerId.value(),
                                    op.fromProfileId().value(),
                                    op.toProfileId().value(),
                                    op.operationId()))
                    : null;
            Result<?, String> commitRes = profileSwitchPort.commitSwitch(
                    op.operationId(), playerId, op.toProfileId(), currentNode, expectedEpoch, outboxEvent);
            if (commitRes.isErr()) {
                return Result.err("Failed to commit roll-forward switch during recovery: " + commitRes.errorOrThrow());
            }
            if (outboxPort != null && outboxEvent != null) {
                outboxPort.stageEvent(
                        outboxEvent.id(), outboxEvent.eventType(), outboxEvent.aggregateId(), outboxEvent.payload());
            }
            return Result.ok(Optional.of(op.toProfileId()));
        }
        return Result.ok(Optional.empty());
    }
}
