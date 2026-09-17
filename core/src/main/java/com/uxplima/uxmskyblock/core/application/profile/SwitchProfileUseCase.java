package com.uxplima.uxmskyblock.core.application.profile;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Application use-case orchestrating the crash-consistent 2-phase player profile switch protocol.
 */
public final class SwitchProfileUseCase {

    @SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
    public record PreparedSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            byte[] targetInventoryNbt) {

        public PreparedSwitch {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(currentNode, "currentNode must not be null");
            Objects.requireNonNull(targetInventoryNbt, "targetInventoryNbt must not be null");
        }
    }

    private final ProfileSwitchPort profileSwitchPort;
    private final ProfileInventoryCheckpointPort inventoryCheckpointPort;

    public SwitchProfileUseCase(
            ProfileSwitchPort profileSwitchPort, ProfileInventoryCheckpointPort inventoryCheckpointPort) {
        this.profileSwitchPort = Objects.requireNonNull(profileSwitchPort, "profileSwitchPort must not be null");
        this.inventoryCheckpointPort =
                Objects.requireNonNull(inventoryCheckpointPort, "inventoryCheckpointPort must not be null");
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

        return Result.ok(new PreparedSwitch(operationId, playerId, toProfileId, currentNode, expectedEpoch, targetNbt));
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

        Result<?, String> commitRes = profileSwitchPort.commitSwitch(
                prepared.operationId(),
                prepared.playerId(),
                prepared.toProfileId(),
                prepared.currentNode(),
                prepared.expectedEpoch());
        if (commitRes.isErr()) {
            profileSwitchPort.abortSwitch(
                    prepared.operationId(),
                    prepared.playerId(),
                    "Failed to commit switch: " + commitRes.errorOrThrow());
            return Result.err(commitRes.errorOrThrow());
        }

        return Result.ok(Unit.INSTANCE);
    }
}
