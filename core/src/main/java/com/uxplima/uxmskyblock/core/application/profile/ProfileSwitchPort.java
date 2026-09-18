package com.uxplima.uxmskyblock.core.application.profile;

import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Hexagonal Outbound Port governing the crash-consistent write-ahead state machine
 * for player profile switching.
 */
public interface ProfileSwitchPort {

    /**
     * Attempts atomic CAS reservation on player_accounts.active_switch_operation_id
     * and persists initial operation record in PREPARING state.
     */
    Result<ProfileSwitchOperation.Preparing, String> reserveSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch);

    /**
     * Persists source profile snapshot and transitions state to SOURCE_SNAPSHOTTED.
     */
    Result<ProfileSwitchOperation.SourceSnapshotted, String> recordSourceSnapshot(
            UUID operationId, byte[] sourceSnapshot);

    /**
     * Persists target profile snapshot and transitions state to TARGET_LOADED.
     */
    Result<ProfileSwitchOperation.TargetLoaded, String> recordTargetLoaded(UUID operationId, byte[] targetSnapshot);

    /**
     * Persists TARGET_APPLY_INTENT to SQL before mutating the player entity.
     */
    Result<ProfileSwitchOperation.TargetApplyIntent, String> recordTargetApplyIntent(UUID operationId);

    /**
     * Records that target snapshot was applied to player entity in-memory (PLAYER_APPLIED).
     */
    Result<ProfileSwitchOperation.PlayerApplied, String> recordPlayerApplied(UUID operationId);

    Result<ProfileSwitchOperation.Committed, String> commitSwitch(
            UUID operationId, PlayerUuid playerId, ProfileId toProfileId, ServerNodeId currentNode, long expectedEpoch);

    /**
     * Atomically commits profile switch serialized on player_sessions authority row lock and stages an outbox event.
     * Updates active_profile_id on both player_sessions and player_accounts, clears active_switch_operation_id,
     * stages optional outbox event, and transitions operation state to COMMITTED.
     */
    default Result<ProfileSwitchOperation.Committed, String> commitSwitch(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId toProfileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            @Nullable StagedOutboxEvent outboxEvent) {
        return commitSwitch(operationId, playerId, toProfileId, currentNode, expectedEpoch);
    }

    /**
     * Aborts switch operation, performs rollback, clears active_switch_operation_id,
     * and marks operation FAILED.
     */
    Result<Unit, String> abortSwitch(UUID operationId, PlayerUuid playerId, String failureReason);

    /**
     * Locates a switch operation by ID.
     */
    Optional<ProfileSwitchOperation> findOperation(UUID operationId);

    /**
     * Locates active/in-flight switch operation for the player, if any.
     */
    Optional<ProfileSwitchOperation> findActiveOperation(PlayerUuid playerId);
}
