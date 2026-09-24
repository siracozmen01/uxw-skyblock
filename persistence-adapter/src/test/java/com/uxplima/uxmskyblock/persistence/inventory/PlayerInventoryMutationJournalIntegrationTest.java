package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * P2 Integration Lane test suite for {@link PlayerInventoryMutationJournalAdapter} (WP2-005).
 *
 * <p>Runs against real MariaDB 10.11.11 and PostgreSQL 15.12-alpine Testcontainers.
 * Verifies write-ahead durability, OCC version increments, active profile binding,
 * row lock serialization via {@code SELECT ... FOR UPDATE}, and idempotency/abort semantics.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerInventoryMutationJournalIntegrationTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerProfileInventoryAdapter mariaInventoryAdapter;
    private static PlayerProfileHandoffFinalizationAdapter mariaFinalizationAdapter;
    private static PlayerInventoryMutationJournalAdapter mariaJournalAdapter;

    private static PlayerProfileInventoryAdapter postgresInventoryAdapter;
    private static PlayerProfileHandoffFinalizationAdapter postgresFinalizationAdapter;
    private static PlayerInventoryMutationJournalAdapter postgresJournalAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaInventoryAdapter = new PlayerProfileInventoryAdapter(mariaDatabase);
            mariaFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(mariaDatabase);
            mariaJournalAdapter = new PlayerInventoryMutationJournalAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresInventoryAdapter = new PlayerProfileInventoryAdapter(postgresDatabase);
            postgresFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(postgresDatabase);
            postgresJournalAdapter = new PlayerInventoryMutationJournalAdapter(postgresDatabase);
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Full 2-phase lifecycle and monotonic version progression")
    void mariaDbTwoPhaseLifecycle() {
        verifyTwoPhaseLifecycle(mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter, mariaJournalAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Cross-profile mismatch rejection")
    void mariaDbCrossProfileRejection() {
        verifyCrossProfileRejection(
                mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter, mariaJournalAdapter);
    }

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Row-level lock serialization via SELECT FOR UPDATE")
    void mariaDbRowLockSerialization() throws Exception {
        verifyRowLockSerialization(mariaDatabase, mariaJournalAdapter, mariaFinalizationAdapter);
    }

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Abort intent lifecycle and idempotent commit")
    void mariaDbAbortAndIdempotency() {
        verifyAbortAndIdempotency(mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter, mariaJournalAdapter);
    }

    @Test
    @Order(5)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Full 2-phase lifecycle and monotonic version progression")
    void postgresTwoPhaseLifecycle() {
        verifyTwoPhaseLifecycle(
                postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter, postgresJournalAdapter);
    }

    @Test
    @Order(6)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Cross-profile mismatch rejection")
    void postgresCrossProfileRejection() {
        verifyCrossProfileRejection(
                postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter, postgresJournalAdapter);
    }

    @Test
    @Order(7)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Row-level lock serialization via SELECT FOR UPDATE")
    void postgresRowLockSerialization() throws Exception {
        verifyRowLockSerialization(postgresDatabase, postgresJournalAdapter, postgresFinalizationAdapter);
    }

    @Test
    @Order(8)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Abort intent lifecycle and idempotent commit")
    void postgresAbortAndIdempotency() {
        verifyAbortAndIdempotency(
                postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter, postgresJournalAdapter);
    }

    @Test
    @Order(9)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: a journal that has settled is swept with its participants")
    void mariaDbSettledJournalsAreSwept() throws Exception {
        verifySettledJournalsAreSwept(mariaDatabase, mariaInventoryAdapter, mariaJournalAdapter);
    }

    @Test
    @Order(10)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: a journal that has settled is swept with its participants")
    void postgresSettledJournalsAreSwept() throws Exception {
        verifySettledJournalsAreSwept(postgresDatabase, postgresInventoryAdapter, postgresJournalAdapter);
    }

    /**
     * A settled journal has nothing left to recover, and nothing ever deleted one.
     *
     * <p>What must survive matters as much: an intent nobody finished, because that is exactly the
     * crash the journal exists for.
     */
    private static void verifySettledJournalsAreSwept(
            Database database,
            PlayerProfileInventoryAdapter invAdapter,
            PlayerInventoryMutationJournalAdapter journalAdapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        invAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        InventoryMutationOperationId committed = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player,
                profile,
                NODE_A,
                1L,
                1L,
                committed,
                "TRADE",
                "fp1",
                "fp2",
                "{\"kind\": \"trade\"}",
                Duration.ofMinutes(1));
        journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, committed, new byte[] {9});

        InventoryMutationOperationId open = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player,
                profile,
                NODE_A,
                1L,
                2L,
                open,
                "TRADE",
                "fp2",
                "fp3",
                "{\"kind\": \"trade\"}",
                Duration.ofMinutes(1));

        // Only these two are pushed back. The class shares one container across its ordered tests,
        // and aging every journal would sweep what an earlier test left behind.
        try (java.sql.Connection conn = database.connection();
                java.sql.PreparedStatement ps = conn.prepareStatement(
                        "UPDATE inventory_mutation_journals SET updated_at = ? WHERE operation_id IN (?, ?)")) {
            ps.setTimestamp(1, java.sql.Timestamp.from(java.time.Instant.parse("2020-01-01T00:00:00Z")));
            ps.setString(2, committed.value().toString());
            ps.setString(3, open.value().toString());
            ps.executeUpdate();
        }

        assertThat(journalAdapter.purgeSettledBefore(java.time.Instant.parse("2021-01-01T00:00:00Z")))
                .describedAs("the committed one, and not the intent")
                .isEqualTo(1);
        assertThat(journalAdapter.loadJournal(committed)).isEmpty();
        assertThat(journalAdapter.loadParticipant(committed, 0))
                .describedAs("the participants go with the journal through the foreign key")
                .isEmpty();
        assertThat(journalAdapter.loadJournal(open)).isPresent();
    }

    // ==========================================
    // Verification Helpers
    // ==========================================

    private static void verifyTwoPhaseLifecycle(
            Database database,
            PlayerProfileInventoryAdapter invAdapter,
            PlayerProfileHandoffFinalizationAdapter finalAdapter,
            PlayerInventoryMutationJournalAdapter journalAdapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {4, 5, 6};
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        invAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {9}));

        // 1. Record Intent
        InventoryMutationJournalOutcome intent = journalAdapter.recordIntent(
                player,
                profile,
                NODE_A,
                1L,
                1L,
                opId,
                "VAULT_DEPOSIT",
                "fp1",
                "fp2",
                "{\"amount\": 500}",
                Duration.ofMinutes(5));
        assertThat(intent.isSuccess()).isTrue();

        Optional<InventoryMutationJournalRecord> jRec = journalAdapter.loadJournal(opId);
        assertThat(jRec).isPresent();
        assertThat(jRec.get().state()).isEqualTo(InventoryMutationJournalState.INTENT);

        Optional<InventoryMutationParticipantRecord> pRec = journalAdapter.loadParticipant(opId, 0);
        assertThat(pRec).isPresent();
        assertThat(pRec.get().durableApplyState()).isEqualTo(ParticipantApplyState.PENDING);

        // Pre-commit state unchanged
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);

        // 2. Commit Mutation
        InventoryMutationJournalOutcome commit =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, v2Nbt);
        assertThat(commit.isSuccess()).isTrue();
        assertThat(commit.version()).hasValue(2L);

        // Post-commit state: journal COMMITTED, participant APPLIED, versions bumped to 2
        assertThat(journalAdapter.loadJournal(opId).get().state()).isEqualTo(InventoryMutationJournalState.COMMITTED);
        assertThat(journalAdapter.loadParticipant(opId, 0).get().durableApplyState())
                .isEqualTo(ParticipantApplyState.APPLIED);
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
        assertThat(invAdapter.loadInventory(profile).get().inventoryNbt()).isEqualTo(v2Nbt);
        assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // 3. Second mutation: 2 -> 3
        InventoryMutationOperationId op2 = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 2L, op2, "AUCTION", "fp2", "fp3", "{\"bid\": 100}", Duration.ofMinutes(5));
        InventoryMutationJournalOutcome commit2 =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 2L, op2, new byte[] {7, 8, 9});
        assertThat(commit2.version()).hasValue(3L);
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(3L);
        assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);
    }

    private static void verifyCrossProfileRejection(
            Database database,
            PlayerProfileInventoryAdapter invAdapter,
            PlayerProfileHandoffFinalizationAdapter finalAdapter,
            PlayerInventoryMutationJournalAdapter journalAdapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId activeProfile = ProfileId.of(UUID.randomUUID());
        ProfileId altProfile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, activeProfile, NODE_A, 1L, "ACTIVE", false, 1L);
        seedSecondProfile(database, player, altProfile);
        invAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(activeProfile, new byte[] {1}, new byte[] {0}));
        invAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(altProfile, new byte[] {2}, new byte[] {0}));

        InventoryMutationJournalOutcome intent = journalAdapter.recordIntent(
                player, altProfile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        assertThat(intent.isRejected()).isTrue();
        assertThat(intent.rejectionReason()).contains("CROSS_PROFILE_MISMATCH");

        assertThat(invAdapter.loadInventory(activeProfile).get().version()).isEqualTo(1L);
        assertThat(invAdapter.loadInventory(altProfile).get().version()).isEqualTo(1L);
        assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    private static void verifyRowLockSerialization(
            Database database,
            PlayerInventoryMutationJournalAdapter journalAdapter,
            PlayerProfileHandoffFinalizationAdapter finalAdapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {10, 20};
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        PlayerProfileInventoryAdapter invAdapter = new PlayerProfileInventoryAdapter(database);
        invAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        // Record intent first
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, opId, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch tx1HoldingLock = new CountDownLatch(1);
        CountDownLatch tx2Attempted = new CountDownLatch(1);
        CountDownLatch releaseTx1 = new CountDownLatch(1);

        try {
            // TxA: raw test connection acquiring row lock on player_sessions via SELECT ... FOR UPDATE
            Future<?> tx1Future = executor.submit(() -> {
                try (Connection tx1Conn = database.connection()) {
                    tx1Conn.setAutoCommit(false);
                    try (PreparedStatement ps = tx1Conn.prepareStatement(
                            "SELECT player_uuid FROM player_sessions WHERE player_uuid = ? FOR UPDATE")) {
                        ps.setString(1, player.value().toString());
                        ps.executeQuery();
                    }
                    tx1HoldingLock.countDown();
                    boolean released = releaseTx1.await(10, TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                    tx1Conn.rollback();
                } catch (Exception e) {
                    throw new RuntimeException("Tx1 failed", e);
                }
            });

            assertThat(tx1HoldingLock.await(10, TimeUnit.SECONDS))
                    .as("Tx1 must acquire row lock")
                    .isTrue();

            // TxB: calls real production commitMutation with NO test hook
            Future<InventoryMutationJournalOutcome> tx2Future = executor.submit(() -> {
                tx2Attempted.countDown();
                return journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, opId, v2Nbt);
            });

            assertThat(tx2Attempted.await(10, TimeUnit.SECONDS))
                    .as("Tx2 must start execution attempt")
                    .isTrue();

            // Proves Tx2 is blocked: trying to get result with short timeout MUST time out
            assertThatThrownBy(() -> tx2Future.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(tx2Future.isDone()).isFalse();

            // Release Tx1
            releaseTx1.countDown();
            tx1Future.get(5, TimeUnit.SECONDS);

            // Tx2 unblocks and executes, successfully advancing version to 2
            InventoryMutationJournalOutcome tx2Result = tx2Future.get(10, TimeUnit.SECONDS);
            assertThat(tx2Result.isSuccess()).isTrue();
            assertThat(tx2Result.version()).hasValue(2L);

            // Final state: version 2 and marker 2
            assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
            assertThat(invAdapter.loadInventory(profile).get().inventoryNbt()).isEqualTo(v2Nbt);
            assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void verifyAbortAndIdempotency(
            Database database,
            PlayerProfileInventoryAdapter invAdapter,
            PlayerProfileHandoffFinalizationAdapter finalAdapter,
            PlayerInventoryMutationJournalAdapter journalAdapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        InventoryMutationOperationId abortOp = InventoryMutationOperationId.random();

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        invAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {0}));

        // Abort flow
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, abortOp, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        InventoryMutationJournalOutcome abort = journalAdapter.abortIntent(player, profile, NODE_A, 1L, abortOp);
        assertThat(abort.isSuccess()).isTrue();
        assertThat(journalAdapter.loadJournal(abortOp).get().state()).isEqualTo(InventoryMutationJournalState.ABORTED);
        assertThat(journalAdapter.loadParticipant(abortOp, 0).get().durableApplyState())
                .isEqualTo(ParticipantApplyState.REVERTED);
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(1L);

        // The aborted operation is tried again over an inventory that has changed since
        InventoryMutationJournalOutcome retry = journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, abortOp, "TRADE", "fp3", "fp4", "{}", Duration.ofMinutes(1));
        assertThat(retry.isSuccess()).isTrue();
        assertThat(journalAdapter.loadJournal(abortOp).get().state()).isEqualTo(InventoryMutationJournalState.INTENT);
        assertThat(journalAdapter.loadParticipant(abortOp, 0).get().beforeFingerprint())
                .isEqualTo("fp3");
        assertThat(journalAdapter
                        .abortIntent(player, profile, NODE_A, 1L, abortOp)
                        .isSuccess())
                .isTrue();

        // Intents a crash left open are found, and settled either way
        InventoryMutationOperationId keptOp = InventoryMutationOperationId.random();
        InventoryMutationOperationId quarantinedOp = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, keptOp, "TRADE", "fp5", "fp6", "{}", Duration.ofMinutes(1));
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, quarantinedOp, "TRADE", "fp7", "fp8", "{}", Duration.ofMinutes(1));
        assertThat(journalAdapter.findOpenIntents(profile)).containsExactlyInAnyOrder(keptOp, quarantinedOp);
        assertThat(invAdapter
                        .checkpointInventory(
                                player,
                                profile,
                                NODE_A,
                                1L,
                                1L,
                                ProfileInventoryRecord.createDefault(profile, new byte[] {9}, new byte[0]))
                        .isSuccess())
                .describedAs("an ambient checkpoint while the journal holds the inventory")
                .isFalse();
        assertThat(journalAdapter
                        .settleOpenIntent(player, profile, NODE_A, 1L, keptOp, InventoryMutationJournalState.COMMITTED)
                        .isSuccess())
                .isTrue();
        assertThat(journalAdapter
                        .settleOpenIntent(
                                player,
                                profile,
                                NODE_A,
                                1L,
                                quarantinedOp,
                                InventoryMutationJournalState.RECOVERY_REQUIRED)
                        .isSuccess())
                .isTrue();
        assertThat(journalAdapter.loadParticipant(keptOp, 0).get().durableApplyState())
                .isEqualTo(ParticipantApplyState.APPLIED);
        assertThat(journalAdapter.loadJournal(quarantinedOp).get().state())
                .isEqualTo(InventoryMutationJournalState.RECOVERY_REQUIRED);
        assertThat(journalAdapter.findOpenIntents(profile)).isEmpty();
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(1L);

        // Idempotent commit replay
        InventoryMutationOperationId commitOp = InventoryMutationOperationId.random();
        journalAdapter.recordIntent(
                player, profile, NODE_A, 1L, 1L, commitOp, "TRADE", "fp1", "fp2", "{}", Duration.ofMinutes(1));
        InventoryMutationJournalOutcome first =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, commitOp, new byte[] {2});
        assertThat(first.version()).hasValue(2L);

        InventoryMutationJournalOutcome replay =
                journalAdapter.commitMutation(player, profile, NODE_A, 1L, 1L, commitOp, new byte[] {2});
        assertThat(replay.isSuccess()).isTrue();
        assertThat(replay.version()).hasValue(2L);
        assertThat(invAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
        assertThat(finalAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);
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

            String leaseExpr = expired
                    ? "TIMESTAMPADD(SECOND, -10, CURRENT_TIMESTAMP)"
                    : "TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)";
            if (database.dialect() == Dialect.POSTGRES) {
                leaseExpr = expired
                        ? "CURRENT_TIMESTAMP - INTERVAL '10 seconds'"
                        : "CURRENT_TIMESTAMP + INTERVAL '60 seconds'";
            }
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
            throw new RuntimeException("Failed to seed session", e);
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
}
