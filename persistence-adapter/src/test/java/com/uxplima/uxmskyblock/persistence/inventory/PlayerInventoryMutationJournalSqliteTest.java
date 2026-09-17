package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane SQLite test suite for {@link PlayerInventoryMutationJournalAdapter} (WP2-005).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Full two-phase write-ahead protocol (INTENT -> COMMITTED).</li>
 *   <li>Active profile binding to locked session.</li>
 *   <li>Strict session authority fencing (ACTIVE state only, node, epoch, lease).</li>
 *   <li>OCC inventory version and session marker atomic synchronization.</li>
 *   <li>Monotonic version progression across multiple mutations.</li>
 *   <li>Idempotent retries and conflict detection on existing operation IDs.</li>
 *   <li>Abort lifecycle (INTENT -> ABORTED, aggregate and marker untouched).</li>
 *   <li>Rejection of commit on aborted or invalid state.</li>
 *   <li>Rejection of abort on committed state.</li>
 *   <li>Trigger-based rollback preserving atomicity without production hooks.</li>
 *   <li>SQLite writer serialization via {@code BEGIN IMMEDIATE} with temporal pre-release verification.</li>
 * </ul>
 */
class PlayerInventoryMutationJournalSqliteTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-bravo");

    private Database database;
    private PlayerProfileInventoryAdapter inventoryAdapter;
    private PlayerProfileHandoffFinalizationAdapter finalizationAdapter;
    private PlayerInventoryMutationJournalAdapter journalAdapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        inventoryAdapter = new PlayerProfileInventoryAdapter(database);
        finalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(database);
        journalAdapter = new PlayerInventoryMutationJournalAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("1. Full valid 2-phase lifecycle: recordIntent -> commitMutation advances version 1 -> 2")
    void validTwoPhaseLifecycleSucceeds() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {4, 5, 6};
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {9}));

        // Phase 1: record intent
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player,
                profile,
                NODE_A,
                1L,
                1L,
                opId,
                "VAULT_DEPOSIT",
                "hash-before-1",
                "hash-after-1",
                "{\"amount\": 100}",
                Duration.ofMinutes(5));

        assertThat(intentOutcome.isSuccess()).isTrue();
        assertThat(intentOutcome.isRejected()).isFalse();
        assertThat(intentOutcome.isConflict()).isFalse();

        // Verify journal in INTENT and participant PENDING
        Optional<InventoryMutationJournalRecord> journal = journalAdapter.loadJournal(opId);
        assertThat(journal).isPresent();
        assertThat(journal.get().state()).isEqualTo(InventoryMutationJournalState.INTENT);
        assertThat(journal.get().operationType()).isEqualTo("VAULT_DEPOSIT");
        assertThat(journal.get().participantCount()).isEqualTo(1);

        Optional<InventoryMutationParticipantRecord> participant = journalAdapter.loadParticipant(opId, 0);
        assertThat(participant).isPresent();
        assertThat(participant.get().applyState()).isEqualTo(ParticipantApplyState.PENDING);
        assertThat(participant.get().expectedVersion()).isEqualTo(1L);

        // Aggregate inventory and session marker must NOT be modified during Phase 1
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(inventoryAdapter.loadInventory(profile).get().inventoryNbt()).isEqualTo(v1Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);

        // Phase 2: commit mutation
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, v2Nbt);

        assertThat(commitOutcome.isSuccess()).isTrue();
        assertThat(commitOutcome.version()).hasValue(2L);

        // Verify journal transitioned to COMMITTED and participant to APPLIED
        journal = journalAdapter.loadJournal(opId);
        assertThat(journal).isPresent();
        assertThat(journal.get().state()).isEqualTo(InventoryMutationJournalState.COMMITTED);

        participant = journalAdapter.loadParticipant(opId, 0);
        assertThat(participant).isPresent();
        assertThat(participant.get().applyState()).isEqualTo(ParticipantApplyState.APPLIED);

        // Verify aggregate inventory and session marker both bumped to version 2
        Optional<ProfileInventoryRecord> updatedInv = inventoryAdapter.loadInventory(profile);
        assertThat(updatedInv).isPresent();
        assertThat(updatedInv.get().version()).isEqualTo(2L);
        assertThat(updatedInv.get().inventoryNbt()).isEqualTo(v2Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);
    }

    @Test
    @DisplayName("2. Monotonic version progression: 1 -> 2 -> 3 across multiple mutations")
    void monotonicVersionProgression() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Mutation 1: 1 -> 2
        InventoryMutationOperationId op1 = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, op1, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        InventoryMutationJournalOutcome outcome1 =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, op1, new byte[] {2});
        assertThat(outcome1.version()).hasValue(2L);
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // Mutation 2: 2 -> 3
        InventoryMutationOperationId op2 = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 2L, op2, "AUCTION", "fp2", "fp3", "{}", Duration.ofMinutes(1));
        InventoryMutationJournalOutcome outcome2 =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 2L, op2, new byte[] {3});
        assertThat(outcome2.version()).hasValue(3L);
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(3L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);
    }

    @Test
    @DisplayName("3. Cross-profile mismatch rejected on both intent and commit")
    void crossProfileMismatchRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId activeProfile = ProfileId.of(UUID.randomUUID());
        ProfileId altProfile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, activeProfile, NODE_A, 1L, "ACTIVE", false, 1L);
        seedSecondProfile(database, player, altProfile);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(activeProfile, new byte[] {1}, new byte[] {0}));
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(altProfile, new byte[] {9}, new byte[] {0}));

        // Attempt intent on altProfile (not active)
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player, altProfile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intentOutcome.isRejected()).isTrue();
        assertThat(intentOutcome.rejectionReason()).contains("CROSS_PROFILE_MISMATCH");
        assertThat(journalAdapter.loadJournal(opId)).isEmpty();

        // Now record valid intent on activeProfile
        journalAdapter.recordIntent(
                player, activeProfile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Attempt commit with altProfile
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, altProfile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(commitOutcome.isRejected()).isTrue();
        assertThat(commitOutcome.rejectionReason()).contains("CROSS_PROFILE_MISMATCH");

        // Profile inventories and journal remain uncommitted
        assertThat(journalAdapter.loadJournal(opId).get().state()).isEqualTo(InventoryMutationJournalState.INTENT);
        assertThat(inventoryAdapter.loadInventory(activeProfile).get().version())
                .isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("4. Wrong node rejected on both intent and commit")
    void wrongNodeRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Intent from NODE_B rejected
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player, profile, NODE_B, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intentOutcome.isRejected()).isTrue();
        assertThat(intentOutcome.rejectionReason()).contains("WRONG_NODE");

        // Record valid intent on NODE_A
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Commit from NODE_B rejected
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, profile, NODE_B, 1L, 1L, opId, new byte[] {2});
        assertThat(commitOutcome.isRejected()).isTrue();
        assertThat(commitOutcome.rejectionReason()).contains("WRONG_NODE");
        assertThat(journalAdapter.loadJournal(opId).get().state()).isEqualTo(InventoryMutationJournalState.INTENT);
    }

    @Test
    @DisplayName("5. Stale epoch rejected on both intent and commit")
    void staleEpochRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 2L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Intent with stale epoch (1L instead of 2L)
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intentOutcome.isRejected()).isTrue();
        assertThat(intentOutcome.rejectionReason()).contains("STALE_EPOCH");

        // Valid intent with epoch 2L
        journalAdapter.recordIntent(
                player, profile, NODE_A, 2L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Commit with stale epoch
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(commitOutcome.isRejected()).isTrue();
        assertThat(commitOutcome.rejectionReason()).contains("STALE_EPOCH");
    }

    @Test
    @DisplayName("6. Non-ACTIVE session states (DRAINING, HANDOFF_READY, RECOVERING, OFFLINE) are rejected")
    void nonActiveStatesRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        String[] nonActiveStates = new String[] {"DRAINING", "HANDOFF_READY", "RECOVERING", "OFFLINE"};

        for (String state : nonActiveStates) {
            setSessionState(database, player, state);

            InventoryMutationOperationId opId = InventoryMutationOperationId.random();
            InventoryMutationJournalOutcome outcome = journalAdapter.recordIntent(
                    player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
            assertThat(outcome.isRejected())
                    .as("Intent must be rejected for state: " + state)
                    .isTrue();
            assertThat(outcome.rejectionReason()).contains("SESSION_NOT_ACTIVE");
        }
    }

    @Test
    @DisplayName("7. Expired lease is rejected on DB clock for both intent and commit")
    void expiredLeaseRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", true, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Intent with expired lease rejected
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intentOutcome.isRejected()).isTrue();
        assertThat(intentOutcome.rejectionReason()).contains("LEASE_EXPIRED");

        // Renew lease and record intent
        renewLease(database, player);
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Expire lease again and attempt commit
        expireSession(database, player);
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(commitOutcome.isRejected()).isTrue();
        assertThat(commitOutcome.rejectionReason()).contains("LEASE_EXPIRED");
    }

    @Test
    @DisplayName("8. Stale OCC version rejected on both intent and commit")
    void staleOccVersionRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Advance inventory to version 2L via a prior completed mutation
        InventoryMutationOperationId setupOp = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, setupOp, "SETUP", "fp0", "fp1", "{}", Duration.ofMinutes(1));
        journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, setupOp, new byte[] {2});
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(2L);

        // Try to record intent with expectedVersion = 1L (stale)
        InventoryMutationJournalOutcome intentOutcome = journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intentOutcome.isRejected()).isTrue();
        assertThat(intentOutcome.rejectionReason()).contains("OCC_VERSION_MISMATCH");

        // Record intent with expectedVersion = 2L (valid)
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 2L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Advance inventory concurrently behind its back to 3L
        InventoryMutationOperationId concurrentOp = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 2L, concurrentOp, "CONCURRENT", "fp2", "fp3", "{}", Duration.ofMinutes(1));
        journalAdapter.commitMutation(player, profile, NODE_A, 1L, 2L, concurrentOp, new byte[] {3});
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(3L);

        // Try commit with expectedVersion = 2L (now stale)
        InventoryMutationJournalOutcome commitOutcome =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 2L, opId, new byte[] {4});
        assertThat(commitOutcome.isRejected()).isTrue();
        assertThat(commitOutcome.rejectionReason()).contains("OCC_VERSION_MISMATCH");
    }

    @Test
    @DisplayName(
            "9. Duplicate operation ID idempotency: replay of commit returns cached success without double increment")
    void duplicateOperationIdIdempotency() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // First execution
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        InventoryMutationJournalOutcome firstCommit =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(firstCommit.isSuccess()).isTrue();
        assertThat(firstCommit.version()).hasValue(2L);

        // Duplicate replay of commitMutation on already COMMITTED journal
        InventoryMutationJournalOutcome replayedCommit =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(replayedCommit.isSuccess()).isTrue();
        assertThat(replayedCommit.version())
                .hasValue(2L); // Returns cached durable version 2 without double incrementing

        // Verify version is still 2L and marker is still 2L
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);
    }

    @Test
    @DisplayName("10. Conflicting intent parameters fail with CONFLICT")
    void conflictingIntentParametersFailWithConflict() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "payload1", Duration.ofMinutes(1));

        // Same opId with conflicting payload / operation type
        InventoryMutationJournalOutcome conflictOutcome = journalAdapter.recordIntent(
                player,
                profile,
                NODE_A,
                1L,
                1L,
                opId,
                "DIFFERENT_TYPE",
                "fp1",
                "fp2",
                "payload2",
                Duration.ofMinutes(1));
        assertThat(conflictOutcome.isConflict()).isTrue();
        assertThat(conflictOutcome.rejectionReason()).contains("OPERATION_CONFLICT");
    }

    @Test
    @DisplayName("11. Abort intent transitions journal to ABORTED; leaves aggregate inventory and marker untouched")
    void abortIntentLifecycle() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "payload", Duration.ofMinutes(1));

        // Abort intent
        InventoryMutationJournalOutcome abortOutcome = journalAdapter.abortIntent(player, profile, NODE_A, 1L, opId);
        assertThat(abortOutcome.isSuccess()).isTrue();

        // Verify journal is ABORTED and participant is REVERTED
        Optional<InventoryMutationJournalRecord> journal = journalAdapter.loadJournal(opId);
        assertThat(journal).isPresent();
        assertThat(journal.get().state()).isEqualTo(InventoryMutationJournalState.ABORTED);

        Optional<InventoryMutationParticipantRecord> participant = journalAdapter.loadParticipant(opId, 0);
        assertThat(participant).isPresent();
        assertThat(participant.get().applyState()).isEqualTo(ParticipantApplyState.REVERTED);

        // Verify aggregate inventory and session marker untouched
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(inventoryAdapter.loadInventory(profile).get().inventoryNbt()).isEqualTo(v1Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);

        // Idempotent abort succeeds
        InventoryMutationJournalOutcome secondAbort = journalAdapter.abortIntent(player, profile, NODE_A, 1L, opId);
        assertThat(secondAbort.isSuccess()).isTrue();

        // Cannot commit ABORTED operation
        InventoryMutationJournalOutcome commitAborted =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {99});
        assertThat(commitAborted.isRejected()).isTrue();
        assertThat(commitAborted.rejectionReason()).contains("JOURNAL_ABORTED");
    }

    @Test
    @DisplayName("12. Cannot abort an already COMMITTED operation")
    void cannotAbortCommittedOperation() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});

        // Attempt abort on COMMITTED
        InventoryMutationJournalOutcome abortOutcome = journalAdapter.abortIntent(player, profile, NODE_A, 1L, opId);
        assertThat(abortOutcome.isRejected()).isTrue();
        assertThat(abortOutcome.rejectionReason()).contains("CANNOT_ABORT_COMMITTED");
    }

    @Test
    @DisplayName("13. Transaction rollback via trigger failure injection rolls back all changes cleanly")
    void triggerBasedTransactionRollback() throws SQLException {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        // Install test-only failure trigger on player_sessions during commit
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TRIGGER fail_journal_commit BEFORE UPDATE OF last_durable_inventory_version ON player_sessions "
                            + "BEGIN "
                            + "  SELECT RAISE(ABORT, 'Simulated failure during journal commit'); "
                            + "END;");
        }

        try {
            assertThatThrownBy(
                            () -> journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {99}))
                    .isInstanceOf(InventoryPersistenceException.class);

            // Verify both tables and journal were rolled back
            assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
            assertThat(inventoryAdapter.loadInventory(profile).get().inventoryNbt())
                    .isEqualTo(v1Nbt);
            assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player))
                    .hasValue(1L);
            assertThat(journalAdapter.loadJournal(opId).get().state()).isEqualTo(InventoryMutationJournalState.INTENT);
            assertThat(journalAdapter.loadParticipant(opId, 0).get().applyState())
                    .isEqualTo(ParticipantApplyState.PENDING);
        } finally {
            try (Connection conn = database.connection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS fail_journal_commit;");
            }
        }
    }

    @Test
    @DisplayName("14. SQLite writer serialization via BEGIN IMMEDIATE with temporal pre-release verification")
    void sqliteWriterSerialization(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("journal-serialization.db");
        try (Database fileDb = DatabaseTestFixture.createSqliteFile(dbFile)) {
            new MigrationRunner(fileDb).apply(SkyblockMigrations.getMigrations(fileDb.dialect()));
            PlayerProfileInventoryAdapter fileInvAdapter = new PlayerProfileInventoryAdapter(fileDb);
            PlayerProfileHandoffFinalizationAdapter fileFinalAdapter =
                    new PlayerProfileHandoffFinalizationAdapter(fileDb);
            PlayerInventoryMutationJournalAdapter fileJournalAdapter =
                    new PlayerInventoryMutationJournalAdapter(fileDb);

            PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            InventoryMutationOperationId opId = InventoryMutationOperationId.random();

            seedSession(fileDb, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
            fileInvAdapter.initializeInventory(
                    ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

            // Record intent first
            fileJournalAdapter.recordIntent(
                    player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

            String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch txAWriterAcquired = new CountDownLatch(1);
            CountDownLatch txBAttemptStarted = new CountDownLatch(1);
            CountDownLatch releaseTxA = new CountDownLatch(1);

            try (Connection connA = java.sql.DriverManager.getConnection(jdbcUrl)) {
                // TxA: raw test connection acquires BEGIN IMMEDIATE exclusive writer lock on file
                Future<Boolean> txAFuture = executor.submit(() -> {
                    try (Statement stmt = connA.createStatement()) {
                        stmt.execute("BEGIN IMMEDIATE");
                        txAWriterAcquired.countDown();
                        boolean released = releaseTxA.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new RuntimeException("Timeout waiting for releaseTxA latch");
                        }
                        stmt.execute("ROLLBACK");
                        return true;
                    }
                });

                assertThat(txAWriterAcquired.await(5, TimeUnit.SECONDS))
                        .as("TxA must acquire BEGIN IMMEDIATE writer lock")
                        .isTrue();

                // TxB: calls the REAL production commitMutation (competing writer)
                Future<InventoryMutationJournalOutcome> txBFuture = executor.submit(() -> {
                    txBAttemptStarted.countDown();
                    return fileJournalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {10});
                });

                assertThat(txBAttemptStarted.await(5, TimeUnit.SECONDS))
                        .as("TxB must signal attempt started")
                        .isTrue();

                // TEMPORAL PRE-RELEASE PROOF:
                // While TxA holds BEGIN IMMEDIATE, TxB must either remain blocked or fail with busy.
                // It MUST NOT acquire and commit writer authority!
                boolean txBCompletedPrematurely = false;
                try {
                    InventoryMutationJournalOutcome prematureResult = txBFuture.get(300, TimeUnit.MILLISECONDS);
                    assertThat(prematureResult.isSuccess())
                            .as("TxB must NOT commit writer authority while TxA holds BEGIN IMMEDIATE")
                            .isFalse();
                    txBCompletedPrematurely = true;
                } catch (TimeoutException e) {
                    // Allowed outcome A: TxB is blocked waiting for writer lock
                    assertThat(txBFuture.isDone()).isFalse();
                } catch (ExecutionException e) {
                    // Allowed outcome B: SQLite busy error rejected competing writer
                    assertThat(e.getCause()).isInstanceOf(InventoryPersistenceException.class);
                    txBCompletedPrematurely = true;
                }

                // Release TxA so it relinquishes exclusive writer lock
                releaseTxA.countDown();
                assertThat(txAFuture.get(5, TimeUnit.SECONDS)).isTrue();

                // After TxA releases, prove database remains usable and final state is correct
                if (!txBCompletedPrematurely) {
                    InventoryMutationJournalOutcome finalResult = txBFuture.get(5, TimeUnit.SECONDS);
                    assertThat(finalResult.isSuccess()).isTrue();
                    assertThat(finalResult.version()).hasValue(2L);
                    assertThat(fileFinalAdapter.loadLastDurableInventoryVersion(player))
                            .hasValue(2L);
                } else {
                    // If busy, retry now succeeds cleanly
                    InventoryMutationJournalOutcome retryResult =
                            fileJournalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {10});
                    assertThat(retryResult.isSuccess()).isTrue();
                    assertThat(retryResult.version()).hasValue(2L);
                    assertThat(fileFinalAdapter.loadLastDurableInventoryVersion(player))
                            .hasValue(2L);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("15. Crash before INTENT commit: journal does not exist, commit without intent is rejected")
    void crashBeforeIntentCommitRejectsCommit() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // No recordIntent executed (Scenario A: crash before INTENT commit)
        assertThat(journalAdapter.loadJournal(opId)).isEmpty();

        // Attempting to commit non-existent operation fails closed
        InventoryMutationJournalOutcome commit =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, new byte[] {2});
        assertThat(commit.isRejected()).isTrue();
        assertThat(commit.rejectionReason()).contains("JOURNAL_NOT_FOUND");

        // Profile inventory and session marker untouched
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    // ==========================================
    // Fixture Helpers
    // ==========================================

    private static void seedSession(
            Database database,
            PlayerUuid player,
            ProfileId profile,
            ServerNodeId node,
            long epoch,
            String state,
            boolean expired,
            long lastDurableVersion) {
        try (Connection conn = database.connection()) {
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON;");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, player.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, player.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, player.value().toString());
                ps.executeUpdate();
            }

            String leaseExpr = expired ? "DATETIME('now', '-10 seconds')" : "DATETIME('now', '+60 seconds')";
            String sql = "INSERT INTO player_sessions ("
                    + "player_uuid, active_profile_id, authoritative_node, session_epoch, "
                    + "state, last_durable_inventory_version, lease_expires_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, " + leaseExpr + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.value().toString());
                ps.setString(2, profile.value().toString());
                ps.setString(3, node.value());
                ps.setLong(4, epoch);
                ps.setString(5, state);
                ps.setLong(6, lastDurableVersion);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed SQLite test session", e);
        }
    }

    private static void seedSecondProfile(Database database, PlayerUuid player, ProfileId profile) {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
            ps.setString(1, profile.value().toString());
            ps.setString(2, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed second profile", e);
        }
    }

    private static void setSessionState(Database database, PlayerUuid player, String newState) {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE player_sessions SET state = ? WHERE player_uuid = ?")) {
            ps.setString(1, newState);
            ps.setString(2, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set session state", e);
        }
    }

    private static void expireSession(Database database, PlayerUuid player) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = DATETIME('now', '-10 seconds') WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire session", e);
        }
    }

    private static void renewLease(Database database, PlayerUuid player) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = DATETIME('now', '+60 seconds') WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to renew lease", e);
        }
    }
}
