package com.uxplima.uxmskyblock.core.application.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JournaledInventoryMutationServiceTest {

    private FakeJournalPort journalPort;
    private JournaledInventoryMutationService service;

    private final PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());
    private final ServerNodeId nodeId = new ServerNodeId("node-1");
    private final InventoryMutationOperationId opId = new InventoryMutationOperationId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        journalPort = new FakeJournalPort();
        service = new JournaledInventoryMutationService(journalPort);
    }

    @Test
    @DisplayName("execute succeeds when intent and commit succeed")
    void executeSucceeds() {
        byte[] updatedNbt = new byte[] {1, 2, 3};

        Result<JournaledInventoryMutationService.MutationSuccess<String>, String> result = service.execute(
                playerUuid,
                profileId,
                nodeId,
                1L,
                1L,
                opId,
                "TEST_OP",
                "hash1",
                "hash2",
                "{}",
                Duration.ofSeconds(30),
                () -> Result.ok(new JournaledInventoryMutationService.MutationExecution<>("done", updatedNbt)));

        assertThat(result.isOk()).isTrue();
        JournaledInventoryMutationService.MutationSuccess<String> success = result.orElseThrow();
        assertThat(success.value()).isEqualTo("done");
        assertThat(success.committedVersion()).isEqualTo(2L);
        assertThat(journalPort.recordedIntent).isTrue();
        assertThat(journalPort.committed).isTrue();
        assertThat(journalPort.aborted).isFalse();
    }

    @Test
    @DisplayName("execute fails and aborts when mutation supplier returns error")
    void executeAbortsOnActionFailure() {
        Result<JournaledInventoryMutationService.MutationSuccess<String>, String> result = service.execute(
                playerUuid,
                profileId,
                nodeId,
                1L,
                1L,
                opId,
                "TEST_OP",
                "hash1",
                "hash2",
                "{}",
                Duration.ofSeconds(30),
                () -> Result.err("insufficient funds"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).contains("insufficient funds");
        assertThat(journalPort.recordedIntent).isTrue();
        assertThat(journalPort.aborted).isTrue();
        assertThat(journalPort.committed).isFalse();
    }

    @Test
    @DisplayName("execute fails and aborts when mutation supplier throws exception")
    void executeAbortsOnActionException() {
        Result<JournaledInventoryMutationService.MutationSuccess<String>, String> result = service.execute(
                playerUuid,
                profileId,
                nodeId,
                1L,
                1L,
                opId,
                "TEST_OP",
                "hash1",
                "hash2",
                "{}",
                Duration.ofSeconds(30),
                () -> {
                    throw new RuntimeException("unexpected NPE");
                });

        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).contains("unexpected NPE");
        assertThat(journalPort.recordedIntent).isTrue();
        assertThat(journalPort.aborted).isTrue();
        assertThat(journalPort.committed).isFalse();
    }

    @Test
    @DisplayName("execute fails immediately when recording intent is rejected")
    void executeFailsOnIntentRejection() {
        journalPort.rejectIntent = true;

        Result<JournaledInventoryMutationService.MutationSuccess<String>, String> result = service.execute(
                playerUuid,
                profileId,
                nodeId,
                1L,
                1L,
                opId,
                "TEST_OP",
                "hash1",
                "hash2",
                "{}",
                Duration.ofSeconds(30),
                () -> Result.ok(new JournaledInventoryMutationService.MutationExecution<>("done", new byte[0])));

        assertThat(result.isErr()).isTrue();
        assertThat(result.errorOrThrow()).contains("Failed to record intent");
        assertThat(journalPort.committed).isFalse();
        assertThat(journalPort.aborted).isFalse();
    }

    private static class FakeJournalPort implements InventoryMutationJournalPort {
        boolean rejectIntent = false;
        boolean recordedIntent = false;
        boolean committed = false;
        boolean aborted = false;

        @Override
        public InventoryMutationJournalOutcome recordIntent(
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
                Duration expiryDuration) {
            if (rejectIntent) {
                return InventoryMutationJournalOutcome.rejected("stale epoch");
            }
            recordedIntent = true;
            return InventoryMutationJournalOutcome.intentRecorded();
        }

        @Override
        public InventoryMutationJournalOutcome commitMutation(
                PlayerUuid playerUuid,
                ProfileId profileId,
                ServerNodeId nodeId,
                long sessionEpoch,
                long expectedVersion,
                InventoryMutationOperationId operationId,
                byte[] updatedInventoryNbt) {
            committed = true;
            return InventoryMutationJournalOutcome.success(expectedVersion + 1);
        }

        @Override
        public InventoryMutationJournalOutcome abortIntent(
                PlayerUuid playerUuid,
                ProfileId profileId,
                ServerNodeId nodeId,
                long sessionEpoch,
                InventoryMutationOperationId operationId) {
            aborted = true;
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public Optional<InventoryMutationJournalRecord> loadJournal(InventoryMutationOperationId operationId) {
            return Optional.empty();
        }

        @Override
        public Optional<InventoryMutationParticipantRecord> loadParticipant(
                InventoryMutationOperationId operationId, int participantIndex) {
            return Optional.empty();
        }
    }
}
