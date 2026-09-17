package com.uxplima.uxmskyblock.core.domain.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Sealed algebraic data type representing the crash-consistent write-ahead state machine
 * for player profile switches.
 */
public sealed interface ProfileSwitchOperation
        permits ProfileSwitchOperation.Preparing,
                ProfileSwitchOperation.SourceSnapshotted,
                ProfileSwitchOperation.TargetLoaded,
                ProfileSwitchOperation.TargetApplyIntent,
                ProfileSwitchOperation.PlayerApplied,
                ProfileSwitchOperation.Committed,
                ProfileSwitchOperation.Failed,
                ProfileSwitchOperation.RecoveryRequired {

    UUID operationId();

    PlayerUuid playerId();

    ProfileId fromProfileId();

    ProfileId toProfileId();

    Instant initiatedAt();

    ProfileSwitchState state();

    record Preparing(
            UUID operationId, PlayerUuid playerId, ProfileId fromProfileId, ProfileId toProfileId, Instant initiatedAt)
            implements ProfileSwitchOperation {

        public Preparing {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.PREPARING;
        }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record SourceSnapshotted(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            byte[] sourceSnapshot)
            implements ProfileSwitchOperation {

        public SourceSnapshotted {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(sourceSnapshot, "sourceSnapshot must not be null");
            sourceSnapshot = sourceSnapshot.clone();
        }

        @Override
        public byte[] sourceSnapshot() {
            return sourceSnapshot.clone();
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.SOURCE_SNAPSHOTTED;
        }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record TargetLoaded(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            byte[] sourceSnapshot,
            byte[] targetSnapshot)
            implements ProfileSwitchOperation {

        public TargetLoaded {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(sourceSnapshot, "sourceSnapshot must not be null");
            Objects.requireNonNull(targetSnapshot, "targetSnapshot must not be null");
            sourceSnapshot = sourceSnapshot.clone();
            targetSnapshot = targetSnapshot.clone();
        }

        @Override
        public byte[] sourceSnapshot() {
            return sourceSnapshot.clone();
        }

        @Override
        public byte[] targetSnapshot() {
            return targetSnapshot.clone();
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.TARGET_LOADED;
        }
    }

    @SuppressWarnings("ArrayRecordComponent")
    record TargetApplyIntent(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            byte[] targetSnapshot)
            implements ProfileSwitchOperation {

        public TargetApplyIntent {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(targetSnapshot, "targetSnapshot must not be null");
            targetSnapshot = targetSnapshot.clone();
        }

        @Override
        public byte[] targetSnapshot() {
            return targetSnapshot.clone();
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.TARGET_APPLY_INTENT;
        }
    }

    record PlayerApplied(
            UUID operationId, PlayerUuid playerId, ProfileId fromProfileId, ProfileId toProfileId, Instant initiatedAt)
            implements ProfileSwitchOperation {

        public PlayerApplied {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.PLAYER_APPLIED;
        }
    }

    record Committed(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            Instant completedAt)
            implements ProfileSwitchOperation {

        public Committed {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(completedAt, "completedAt must not be null");
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.COMMITTED;
        }
    }

    record Failed(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            String failureReason,
            boolean rollbackCompleted)
            implements ProfileSwitchOperation {

        public Failed {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(failureReason, "failureReason must not be null");
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.FAILED;
        }
    }

    record RecoveryRequired(
            UUID operationId,
            PlayerUuid playerId,
            ProfileId fromProfileId,
            ProfileId toProfileId,
            Instant initiatedAt,
            String failureReason)
            implements ProfileSwitchOperation {

        public RecoveryRequired {
            Objects.requireNonNull(operationId, "operationId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(fromProfileId, "fromProfileId must not be null");
            Objects.requireNonNull(toProfileId, "toProfileId must not be null");
            Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
            Objects.requireNonNull(failureReason, "failureReason must not be null");
        }

        @Override
        public ProfileSwitchState state() {
            return ProfileSwitchState.RECOVERY_REQUIRED;
        }
    }
}
