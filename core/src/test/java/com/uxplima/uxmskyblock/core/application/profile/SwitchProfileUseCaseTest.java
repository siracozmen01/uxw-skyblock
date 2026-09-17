package com.uxplima.uxmskyblock.core.application.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SwitchProfileUseCaseTest {

    private FakeProfileSwitchPort switchPort;
    private FakeInventoryCheckpointPort checkpointPort;
    private SwitchProfileUseCase useCase;

    private static final PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
    private static final ProfileId profileA = new ProfileId(UUID.randomUUID());
    private static final ProfileId profileB = new ProfileId(UUID.randomUUID());
    private static final ServerNodeId nodeId = new ServerNodeId("node-1");

    @BeforeEach
    void setUp() {
        switchPort = new FakeProfileSwitchPort();
        checkpointPort = new FakeInventoryCheckpointPort();
        useCase = new SwitchProfileUseCase(switchPort, checkpointPort);
    }

    @Test
    @DisplayName("prepareSwitch succeeds and returns target inventory payload")
    void prepareSwitchSucceeds() {
        UUID opId = UUID.randomUUID();
        byte[] srcNbt = new byte[] {1, 2, 3};
        byte[] targetNbt = new byte[] {4, 5, 6};
        checkpointPort.records.put(profileB, ProfileInventoryRecord.createDefault(profileB, targetNbt, new byte[0]));

        Result<SwitchProfileUseCase.PreparedSwitch, String> result =
                useCase.prepareSwitch(opId, playerUuid, profileA, profileB, nodeId, 1L, srcNbt);

        assertThat(result.isOk()).isTrue();
        SwitchProfileUseCase.PreparedSwitch prepared = result.orElseThrow();
        assertThat(prepared.operationId()).isEqualTo(opId);
        assertThat(prepared.toProfileId()).isEqualTo(profileB);
        assertThat(prepared.targetInventoryNbt()).isEqualTo(targetNbt);

        assertThat(switchPort.sourceSnapshots).containsEntry(opId, srcNbt);
        assertThat(switchPort.targetSnapshots).containsEntry(opId, targetNbt);
        assertThat(switchPort.appliedIntents).contains(opId);
    }

    @Test
    @DisplayName("prepareSwitch fails when switching to identical profile")
    void prepareSwitchRejectsSameProfile() {
        UUID opId = UUID.randomUUID();
        Result<SwitchProfileUseCase.PreparedSwitch, String> result =
                useCase.prepareSwitch(opId, playerUuid, profileA, profileA, nodeId, 1L, new byte[0]);

        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).contains("Cannot switch to currently active profile");
    }

    @Test
    @DisplayName("completeSwitch commits the profile switch")
    void completeSwitchCommits() {
        UUID opId = UUID.randomUUID();
        SwitchProfileUseCase.PreparedSwitch prepared =
                new SwitchProfileUseCase.PreparedSwitch(opId, playerUuid, profileB, nodeId, 1L, new byte[] {1});

        Result<Unit, String> result = useCase.completeSwitch(prepared);

        assertThat(result.isOk()).isTrue();
        assertThat(switchPort.playerApplied).contains(opId);
        assertThat(switchPort.committed).contains(opId);
    }

    @Test
    @DisplayName("prepareSwitch aborts when reservation fails")
    void prepareSwitchAbortsOnReservationFailure() {
        switchPort.failReserve = true;
        UUID opId = UUID.randomUUID();

        Result<SwitchProfileUseCase.PreparedSwitch, String> result =
                useCase.prepareSwitch(opId, playerUuid, profileA, profileB, nodeId, 1L, new byte[0]);

        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).contains("Failed to reserve switch");
    }

    private static class FakeProfileSwitchPort implements ProfileSwitchPort {
        boolean failReserve = false;
        final Map<UUID, byte[]> sourceSnapshots = new HashMap<>();
        final Map<UUID, byte[]> targetSnapshots = new HashMap<>();
        final java.util.Set<UUID> appliedIntents = new java.util.HashSet<>();
        final java.util.Set<UUID> playerApplied = new java.util.HashSet<>();
        final java.util.Set<UUID> committed = new java.util.HashSet<>();
        final Map<UUID, String> aborts = new HashMap<>();

        @Override
        public Result<ProfileSwitchOperation.Preparing, String> reserveSwitch(
                UUID operationId,
                PlayerUuid playerId,
                ProfileId fromProfileId,
                ProfileId toProfileId,
                ServerNodeId currentNode,
                long expectedEpoch) {
            if (failReserve) {
                return Result.err("Reservation error");
            }
            return Result.ok(new ProfileSwitchOperation.Preparing(
                    operationId, playerId, fromProfileId, toProfileId, java.time.Instant.now()));
        }

        @Override
        public Result<ProfileSwitchOperation.SourceSnapshotted, String> recordSourceSnapshot(
                UUID operationId, byte[] sourceSnapshot) {
            sourceSnapshots.put(operationId, sourceSnapshot);
            return Result.ok(new ProfileSwitchOperation.SourceSnapshotted(
                    operationId, playerUuid, profileA, profileB, java.time.Instant.now(), sourceSnapshot));
        }

        @Override
        public Result<ProfileSwitchOperation.TargetLoaded, String> recordTargetLoaded(
                UUID operationId, byte[] targetSnapshot) {
            targetSnapshots.put(operationId, targetSnapshot);
            return Result.ok(new ProfileSwitchOperation.TargetLoaded(
                    operationId, playerUuid, profileA, profileB, java.time.Instant.now(), new byte[0], targetSnapshot));
        }

        @Override
        public Result<ProfileSwitchOperation.TargetApplyIntent, String> recordTargetApplyIntent(UUID operationId) {
            appliedIntents.add(operationId);
            return Result.ok(new ProfileSwitchOperation.TargetApplyIntent(
                    operationId, playerUuid, profileA, profileB, java.time.Instant.now(), new byte[0]));
        }

        @Override
        public Result<ProfileSwitchOperation.PlayerApplied, String> recordPlayerApplied(UUID operationId) {
            playerApplied.add(operationId);
            return Result.ok(new ProfileSwitchOperation.PlayerApplied(
                    operationId, playerUuid, profileA, profileB, java.time.Instant.now()));
        }

        @Override
        public Result<ProfileSwitchOperation.Committed, String> commitSwitch(
                UUID operationId,
                PlayerUuid playerId,
                ProfileId toProfileId,
                ServerNodeId currentNode,
                long expectedEpoch) {
            committed.add(operationId);
            return Result.ok(new ProfileSwitchOperation.Committed(
                    operationId, playerId, profileA, toProfileId, java.time.Instant.now(), java.time.Instant.now()));
        }

        @Override
        public Result<Unit, String> abortSwitch(UUID operationId, PlayerUuid playerId, String failureReason) {
            aborts.put(operationId, failureReason);
            return Result.ok(Unit.INSTANCE);
        }

        @Override
        public Optional<ProfileSwitchOperation> findOperation(UUID operationId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProfileSwitchOperation> findActiveOperation(PlayerUuid playerId) {
            return Optional.empty();
        }
    }

    private static class FakeInventoryCheckpointPort implements ProfileInventoryCheckpointPort {
        final Map<ProfileId, ProfileInventoryRecord> records = new HashMap<>();

        @Override
        public ProfileInventoryMutationOutcome checkpointInventory(
                PlayerUuid playerUuid,
                ProfileId profileId,
                ServerNodeId currentNode,
                long expectedEpoch,
                long expectedVersion,
                byte[] inventoryNbt) {
            records.put(profileId, ProfileInventoryRecord.createDefault(profileId, inventoryNbt, new byte[0]));
            return new ProfileInventoryMutationOutcome.Success(expectedVersion + 1);
        }

        @Override
        public Optional<ProfileInventoryRecord> loadInventory(ProfileId profileId) {
            return Optional.ofNullable(records.get(profileId));
        }

        @Override
        public void initializeInventory(ProfileInventoryRecord record) {
            records.put(record.profileId(), record);
        }
    }
}
