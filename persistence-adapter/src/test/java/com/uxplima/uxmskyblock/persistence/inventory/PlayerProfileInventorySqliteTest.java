package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
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
 * Fast-lane SQLite test suite for {@link PlayerProfileInventoryAdapter} (WP2-003).
 *
 * <p>Verifies optimistic concurrency control (OCC), session authority fencing, active-profile binding,
 * DB lease checks, rollback guarantees, and SQLite writer serialization via {@code BEGIN IMMEDIATE}.
 */
class PlayerProfileInventorySqliteTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-bravo");

    private Database database;
    private PlayerProfileInventoryAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerProfileInventoryAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("1. Valid authority + expected version succeeds and increments version exactly once")
    void validAuthorityAndExpectedVersionSucceeds() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};
        byte[] updatedNbt = new byte[] {4, 5, 6};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9, 9}));

        // Initial state check
        Optional<ProfileInventoryRecord> initial = adapter.loadInventory(profile);
        assertThat(initial).isPresent();
        assertThat(initial.get().version()).isEqualTo(1L);
        assertThat(initial.get().inventoryNbt()).isEqualTo(initialNbt);

        // Perform authoritative mutation
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, updatedNbt);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        // Durable verification
        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(2L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(updatedNbt);
    }

    @Test
    @DisplayName("2. Subsequent valid mutation increments version to 3")
    void versionIncrementsMonotonically() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        // 1 -> 2
        ProfileInventoryMutationOutcome first =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {10});
        assertThat(first).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        // 2 -> 3
        ProfileInventoryMutationOutcome second =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 2L, new byte[] {20});
        assertThat(second).isEqualTo(ProfileInventoryMutationOutcome.success(3L));

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(3L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(new byte[] {20});
    }

    @Test
    @DisplayName("3. Stale OCC version is rejected without modifying aggregate")
    void staleVersionIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Call with stale expectedVersion 99L (current is 1L)
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 99L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("4. Wrong node is rejected and aggregate is untouched")
    void wrongNodeIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Calling from NODE_B when session is owned by NODE_A
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_B, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("5. Stale session epoch is rejected and aggregate is untouched")
    void staleEpochIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        // Seed with session epoch 2
        seedSession(database, player, profile, NODE_A, 2L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Calling with stale epoch 1L
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName(
            "6. Cross-profile mismatch is rejected: Player A session with active Profile A cannot mutate Profile B")
    void crossProfileMismatchIsRejected() {
        PlayerUuid playerA = PlayerUuid.of(UUID.randomUUID());
        ProfileId profileA = ProfileId.of(UUID.randomUUID());
        ProfileId profileB = ProfileId.of(UUID.randomUUID());
        byte[] nbtA = new byte[] {1, 1, 1};
        byte[] nbtB = new byte[] {2, 2, 2};

        // Seed playerA session with active_profile_id = profileA
        seedSession(database, playerA, profileA, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profileA, nbtA, new byte[] {0}));

        // Seed profileB with a valid inventory row
        seedSecondProfile(database, playerA, profileB);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profileB, nbtB, new byte[] {0}));

        // Calling routine checkpoint for playerA session but targeting profileB
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(playerA, profileB, NODE_A, 1L, 1L, new byte[] {9, 9, 9});

        assertThat(outcome.isRejected()).isTrue();

        // Verify Profile A unchanged
        Optional<ProfileInventoryRecord> loadedA = adapter.loadInventory(profileA);
        assertThat(loadedA).isPresent();
        assertThat(loadedA.get().version()).isEqualTo(1L);
        assertThat(loadedA.get().inventoryNbt()).isEqualTo(nbtA);

        // Verify Profile B unchanged
        Optional<ProfileInventoryRecord> loadedB = adapter.loadInventory(profileB);
        assertThat(loadedB).isPresent();
        assertThat(loadedB.get().version()).isEqualTo(1L);
        assertThat(loadedB.get().inventoryNbt()).isEqualTo(nbtB);
    }

    @Test
    @DisplayName("7. Expired lease by DB clock is rejected and aggregate is untouched")
    void expiredLeaseIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        // Seed expired session (lease_expires_at in past)
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", true);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("8. Non-ACTIVE session states (DRAINING, HANDOFF_READY, RECOVERING) are rejected")
    void nonActiveSessionStatesAreRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // DRAINING rejected
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        // HANDOFF_READY rejected
        setSessionState(database, player, "HANDOFF_READY");
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        // RECOVERING rejected
        setSessionState(database, player, "RECOVERING");
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("9. Transaction rollback on failure: failure after profile inventory update rolls back cleanly")
    void transactionRollbackPreservesInventoryWithoutProductionCallback() throws SQLException {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Install a test-only trigger on profile_inventories to fail the update
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_profile_inv AFTER UPDATE ON profile_inventories "
                    + "BEGIN "
                    + "  SELECT RAISE(ABORT, 'Simulated failure after profile inventory update'); "
                    + "END;");
        }

        try {
            assertThatThrownBy(() -> adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99}))
                    .isInstanceOf(InventoryPersistenceException.class);

            // Verify row was rolled back cleanly
            Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().version()).isEqualTo(1L);
            assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
        } finally {
            try (Connection conn = database.connection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS fail_profile_inv;");
            }
        }
    }

    @Test
    @DisplayName("10. SQLite writer serialization via BEGIN IMMEDIATE with temporal pre-release verification")
    void sqliteWriterSerialization(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("inventory-serialization.db");
        try (Database fileDb = DatabaseTestFixture.createSqliteFile(dbFile)) {
            new MigrationRunner(fileDb).apply(SkyblockMigrations.getMigrations(fileDb.dialect()));
            PlayerProfileInventoryAdapter fileAdapter = new PlayerProfileInventoryAdapter(fileDb);

            PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            byte[] v1 = new byte[] {1};
            byte[] v2 = new byte[] {10};

            seedSession(fileDb, player, profile, NODE_A, 1L, "ACTIVE", false);
            fileAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1, new byte[] {2}));

            String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch txAWriterAcquired = new CountDownLatch(1);
            CountDownLatch txBAttemptStarted = new CountDownLatch(1);
            CountDownLatch releaseTxA = new CountDownLatch(1);

            try (Connection connA = java.sql.DriverManager.getConnection(jdbcUrl)) {
                // TxA: raw test connection acquires BEGIN IMMEDIATE exclusive writer lock
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

                // TxB: calls the REAL production checkpointInventory (competing writer)
                Future<ProfileInventoryMutationOutcome> txBFuture = executor.submit(() -> {
                    txBAttemptStarted.countDown();
                    return fileAdapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, v2);
                });

                assertThat(txBAttemptStarted.await(5, TimeUnit.SECONDS))
                        .as("TxB must signal attempt started")
                        .isTrue();

                // TEMPORAL PRE-RELEASE PROOF:
                // While TxA holds BEGIN IMMEDIATE, TxB must either remain blocked or fail with busy.
                // It MUST NOT acquire and commit writer authority!
                boolean txBCompletedPrematurely = false;
                try {
                    ProfileInventoryMutationOutcome prematureResult = txBFuture.get(300, TimeUnit.MILLISECONDS);
                    assertThat(prematureResult.isSuccess())
                            .as("TxB must NOT commit writer authority while TxA holds BEGIN IMMEDIATE")
                            .isFalse();
                    txBCompletedPrematurely = true;
                } catch (TimeoutException e) {
                    // Allowed outcome A: TxB is blocked waiting for writer lock
                    assertThat(txBFuture.isDone()).isFalse();
                } catch (ExecutionException e) {
                    // Allowed outcome B: SQLite busy error rejected the competing writer
                    assertThat(e.getCause()).isInstanceOf(InventoryPersistenceException.class);
                    txBCompletedPrematurely = true;
                }

                // Release TxA so it relinquishes the exclusive writer lock
                releaseTxA.countDown();
                assertThat(txAFuture.get(5, TimeUnit.SECONDS)).isTrue();

                // After TxA releases, prove database remains usable and final canonical state is correct
                if (!txBCompletedPrematurely) {
                    ProfileInventoryMutationOutcome finalResult = txBFuture.get(5, TimeUnit.SECONDS);
                    assertThat(finalResult.isSuccess()).isTrue();
                    assertThat(finalResult).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
                    assertThat(fileAdapter.loadInventory(profile).get().version())
                            .isEqualTo(2L);
                } else {
                    // If TxB failed with busy, subsequent call now succeeds cleanly
                    ProfileInventoryMutationOutcome retryResult =
                            fileAdapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, v2);
                    assertThat(retryResult.isSuccess()).isTrue();
                    assertThat(retryResult).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
                    assertThat(fileAdapter.loadInventory(profile).get().version())
                            .isEqualTo(2L);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName(
            "11. Database lease boundary predicate: equality lease_expires_at >= CURRENT_TIMESTAMP evaluates to 1 deterministically")
    void databaseLeaseBoundaryEqualitySemantics() throws SQLException {
        // Deterministic equality evaluation: comparator >= considers exact timestamp match valid (1),
        // and strictly past timestamp invalid (0), with zero timing sleeps or JVM clock dependencies.
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT (CASE WHEN datetime('2026-09-15 12:00:00') >= datetime('2026-09-15 12:00:00') THEN 1 ELSE 0 END) AS equal_case, "
                            + "(CASE WHEN datetime('2026-09-15 11:59:59') >= datetime('2026-09-15 12:00:00') THEN 1 ELSE 0 END) AS past_case, "
                            + "(CASE WHEN datetime('2026-09-15 12:00:01') >= datetime('2026-09-15 12:00:00') THEN 1 ELSE 0 END) AS future_case")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("equal_case")).isEqualTo(1);
                    assertThat(rs.getInt("past_case")).isEqualTo(0);
                    assertThat(rs.getInt("future_case")).isEqualTo(1);
                }
            }
        }
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
            boolean expired) {
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
                    + ") VALUES (?, ?, ?, ?, ?, 1, " + leaseExpr + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.value().toString());
                ps.setString(2, profile.value().toString());
                ps.setString(3, node.value());
                ps.setLong(4, epoch);
                ps.setString(5, state);
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
}
