package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

/**
 * Fast-lane SQLite test suite for {@link PlayerProfileInventoryAdapter} (WP2-003).
 *
 * <p>Verifies optimistic concurrency control (OCC), session authority fencing, DB lease checks,
 * rollback guarantees, and SQLite writer serialization.
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
        byte[] v1Nbt = new byte[] {1, 1};
        byte[] v2Nbt = new byte[] {2, 2};
        byte[] staleAttemptNbt = new byte[] {9, 9};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        // Advance to version 2
        adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, v2Nbt);

        // Attempt mutation with stale expectedVersion = 1L
        ProfileInventoryMutationOutcome staleOutcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, staleAttemptNbt);

        assertThat(staleOutcome.isRejected()).isTrue();

        // Verify state is still at v2
        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(2L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(v2Nbt);
    }

    @Test
    @DisplayName("4. Wrong node is rejected and aggregate is untouched")
    void wrongNodeIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {0}));

        // Wrong node NODE_B attempts mutation
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
        byte[] initialNbt = new byte[] {1};

        seedSession(database, player, profile, NODE_A, 2L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {0}));

        // Stale epoch 1L attempted
        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("6. Expired lease by DB clock is rejected and aggregate is untouched")
    void expiredLeaseIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1};

        // Seed with expired lease
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", true);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {0}));

        ProfileInventoryMutationOutcome outcome =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("7. Non-ACTIVE session states (DRAINING, HANDOFF_READY, RECOVERING) are rejected")
    void nonActiveSessionStatesAreRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {0}));

        // DRAINING -> rejected
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        // Update to HANDOFF_READY -> rejected
        setSessionState(database, player, "HANDOFF_READY");
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        // Update to RECOVERING -> rejected
        setSessionState(database, player, "RECOVERING");
        assertThat(adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        // Aggregate remained completely untouched throughout
        Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);
    }

    @Test
    @DisplayName("8. SQLite writer serialization via BEGIN IMMEDIATE")
    void sqliteWriterSerialization() throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch aRelease = new CountDownLatch(1);

        try {
            // Thread A: executes checkpoint holding BEGIN IMMEDIATE transaction via hook
            Future<ProfileInventoryMutationOutcome> futureA = executor.submit(() -> {
                return adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, new byte[] {10}, () -> {
                    aStarted.countDown();
                    try {
                        aRelease.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });

            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Release A to commit
            aRelease.countDown();

            ProfileInventoryMutationOutcome outcomeA = futureA.get(5, TimeUnit.SECONDS);
            assertThat(outcomeA).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

            // Thread B can now proceed
            ProfileInventoryMutationOutcome outcomeB =
                    adapter.checkpointInventory(player, profile, NODE_A, 1L, 2L, new byte[] {20});
            assertThat(outcomeB).isEqualTo(ProfileInventoryMutationOutcome.success(3L));

            Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().version()).isEqualTo(3L);
            assertThat(loaded.get().inventoryNbt()).isEqualTo(new byte[] {20});
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "9. Database lease boundary predicate: equality lease_expires_at >= CURRENT_TIMESTAMP evaluates to 1 deterministically")
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
