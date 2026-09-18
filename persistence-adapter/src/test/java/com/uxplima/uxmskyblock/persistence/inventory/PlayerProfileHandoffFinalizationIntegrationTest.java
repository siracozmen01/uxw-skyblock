package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalLong;
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
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
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
 * P2 Integration Lane test suite for {@link PlayerProfileHandoffFinalizationAdapter} (WP2-004).
 *
 * <p>Runs against real MariaDB 10.11.11 and PostgreSQL 15.12-alpine Testcontainers.
 * Proves handoff finalization durability, optimistic concurrency control (OCC), session authority fencing,
 * active profile binding, atomic synchronization of {@code profile_inventories.profile_inventory_version} with
 * {@code player_sessions.last_durable_inventory_version}, crash-window recoverability,
 * and temporal row-level lock serialization with zero {@code Thread.sleep}.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlayerProfileHandoffFinalizationIntegrationTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-bravo");

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerProfileInventoryAdapter mariaInventoryAdapter;
    private static PlayerProfileHandoffFinalizationAdapter mariaFinalizationAdapter;
    private static PlayerSessionAuthorityAdapter mariaSessionAdapter;

    private static PlayerProfileInventoryAdapter postgresInventoryAdapter;
    private static PlayerProfileHandoffFinalizationAdapter postgresFinalizationAdapter;
    private static PlayerSessionAuthorityAdapter postgresSessionAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaInventoryAdapter = new PlayerProfileInventoryAdapter(mariaDatabase);
        mariaFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(mariaDatabase);
        mariaSessionAdapter = new PlayerSessionAuthorityAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresInventoryAdapter = new PlayerProfileInventoryAdapter(postgresDatabase);
        postgresFinalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(postgresDatabase);
        postgresSessionAdapter = new PlayerSessionAuthorityAdapter(postgresDatabase);
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

    // ==========================================
    // MariaDB Integration Tests
    // ==========================================

    @Test
    @Order(1)
    @DisplayName("MariaDB 1: Handoff Finalization Lifecycle and Session Marker Synchronization")
    void mariaDbFinalizationLifecycleAndMarkerSync() {
        verifyFinalizationLifecycleAndMarkerSync(mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("MariaDB 2: Cross-Profile Mismatch is Rejected without Mutating Profile")
    void mariaDbCrossProfileRejection() {
        verifyCrossProfileRejection(mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter);
    }

    @Test
    @Order(3)
    @DisplayName("MariaDB 3: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void mariaDbRowLockSerialization() throws Exception {
        verifyRowLockSerialization(mariaDatabase, mariaFinalizationAdapter);
    }

    @Test
    @Order(4)
    @DisplayName("MariaDB 4: Crash Window Recovery and Full Handoff Protocol")
    void mariaDbCrashWindowRecoveryAndFullHandoff() {
        verifyCrashWindowRecoveryAndFullHandoff(
                mariaDatabase, mariaInventoryAdapter, mariaFinalizationAdapter, mariaSessionAdapter);
    }

    // ==========================================
    // PostgreSQL Integration Tests
    // ==========================================

    @Test
    @Order(5)
    @DisplayName("PostgreSQL 1: Handoff Finalization Lifecycle and Session Marker Synchronization")
    void postgresFinalizationLifecycleAndMarkerSync() {
        verifyFinalizationLifecycleAndMarkerSync(
                postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter);
    }

    @Test
    @Order(6)
    @DisplayName("PostgreSQL 2: Cross-Profile Mismatch is Rejected without Mutating Profile")
    void postgresCrossProfileRejection() {
        verifyCrossProfileRejection(postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter);
    }

    @Test
    @Order(7)
    @DisplayName("PostgreSQL 3: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void postgresRowLockSerialization() throws Exception {
        verifyRowLockSerialization(postgresDatabase, postgresFinalizationAdapter);
    }

    @Test
    @Order(8)
    @DisplayName("PostgreSQL 4: Crash Window Recovery and Full Handoff Protocol")
    void postgresCrashWindowRecoveryAndFullHandoff() {
        verifyCrashWindowRecoveryAndFullHandoff(
                postgresDatabase, postgresInventoryAdapter, postgresFinalizationAdapter, postgresSessionAdapter);
    }

    // ==========================================
    // Shared Verification Logic
    // ==========================================

    private static void verifyFinalizationLifecycleAndMarkerSync(
            Database database,
            PlayerProfileInventoryAdapter inventoryAdapter,
            PlayerProfileHandoffFinalizationAdapter finalizationAdapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {4, 5, 6};
        byte[] v3Nbt = new byte[] {7, 8, 9};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {10, 20}));

        // 1. Initial state verification
        Optional<ProfileInventoryRecord> initial = inventoryAdapter.loadInventory(profile);
        assertThat(initial).isPresent();
        assertThat(initial.get().version()).isEqualTo(1L);
        assertThat(initial.get().inventoryNbt()).isEqualTo(v1Nbt);

        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);

        // 2. Valid finalization flush (1 -> 2) under DRAINING
        ProfileInventoryMutationOutcome outcome1 =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, v2Nbt);
        assertThat(outcome1).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        Optional<ProfileInventoryRecord> loaded1 = inventoryAdapter.loadInventory(profile);
        assertThat(loaded1).isPresent();
        assertThat(loaded1.get().version()).isEqualTo(2L);
        assertThat(loaded1.get().inventoryNbt()).isEqualTo(v2Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // 3. Monotonic increment (2 -> 3)
        ProfileInventoryMutationOutcome outcome2 =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 2L, v3Nbt);
        assertThat(outcome2).isEqualTo(ProfileInventoryMutationOutcome.success(3L));

        Optional<ProfileInventoryRecord> loaded2 = inventoryAdapter.loadInventory(profile);
        assertThat(loaded2).isPresent();
        assertThat(loaded2.get().version()).isEqualTo(3L);
        assertThat(loaded2.get().inventoryNbt()).isEqualTo(v3Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        // 4. Stale OCC version rejected (expectedVersion 2 when version is 3)
        ProfileInventoryMutationOutcome stale =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 2L, new byte[] {99});
        assertThat(stale.isRejected()).isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        // 5. Wrong node rejected
        ProfileInventoryMutationOutcome wrongNode =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_B, 1L, 3L, new byte[] {99});
        assertThat(wrongNode.isRejected()).isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        // 6. Stale epoch rejected
        ProfileInventoryMutationOutcome staleEpoch =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 99L, 3L, new byte[] {99});
        assertThat(staleEpoch.isRejected()).isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        // 7. Non-DRAINING states rejected (ACTIVE and HANDOFF_READY)
        setSessionState(database, player, "ACTIVE");
        ProfileInventoryMutationOutcome active =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 3L, new byte[] {99});
        assertThat(active.isRejected()).isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        setSessionState(database, player, "HANDOFF_READY");
        ProfileInventoryMutationOutcome handoffReady =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 3L, new byte[] {99});
        assertThat(handoffReady.isRejected()).isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        // 8. Expired lease rejected
        setSessionState(database, player, "DRAINING");
        expireSession(database, player);
        ProfileInventoryMutationOutcome expired =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 3L, new byte[] {99});
        assertThat(expired.isRejected()).isTrue();

        // Verify final aggregate and marker are untouched at version 3
        Optional<ProfileInventoryRecord> finalRecord = inventoryAdapter.loadInventory(profile);
        assertThat(finalRecord).isPresent();
        assertThat(finalRecord.get().version()).isEqualTo(3L);
        assertThat(finalRecord.get().inventoryNbt()).isEqualTo(v3Nbt);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);
    }

    private static void verifyCrossProfileRejection(
            Database database,
            PlayerProfileInventoryAdapter inventoryAdapter,
            PlayerProfileHandoffFinalizationAdapter finalizationAdapter) {
        PlayerUuid playerA = PlayerUuid.of(UUID.randomUUID());
        ProfileId profileA = ProfileId.of(UUID.randomUUID());
        ProfileId profileB = ProfileId.of(UUID.randomUUID());
        byte[] vA = new byte[] {1, 1};
        byte[] vB = new byte[] {2, 2};
        byte[] payload = new byte[] {9, 9};

        // Player A has active session with profileA in DRAINING state
        seedSession(database, playerA, profileA, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profileA, vA, new byte[] {0}));

        // Profile B exists in database with version 1
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profileB.value().toString());
                ps.setString(2, playerA.value().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed profile B", e);
        }
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profileB, vB, new byte[] {0}));

        // Player A attempts to finalize flush on Profile B with matching expected version (1L)
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(playerA, profileB, NODE_A, 1L, 1L, payload);

        assertThat(outcome.isRejected())
                .as("Cross-profile finalization must be rejected")
                .isTrue();

        // Verify Profile A is untouched
        Optional<ProfileInventoryRecord> loadedA = inventoryAdapter.loadInventory(profileA);
        assertThat(loadedA).isPresent();
        assertThat(loadedA.get().version()).isEqualTo(1L);
        assertThat(loadedA.get().inventoryNbt()).isEqualTo(vA);

        // Verify Profile B is untouched
        Optional<ProfileInventoryRecord> loadedB = inventoryAdapter.loadInventory(profileB);
        assertThat(loadedB).isPresent();
        assertThat(loadedB.get().version()).isEqualTo(1L);
        assertThat(loadedB.get().inventoryNbt()).isEqualTo(vB);

        // Verify Player A last_durable_inventory_version is untouched at 1L
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(playerA)).hasValue(1L);
    }

    private static void verifyRowLockSerialization(Database database, PlayerProfileHandoffFinalizationAdapter adapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {10, 20};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        PlayerProfileInventoryAdapter invAdapter = new PlayerProfileInventoryAdapter(database);
        invAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

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

            // TxB: calls real production finalizeHandoffFlush with NO test hook
            Future<ProfileInventoryMutationOutcome> tx2Future = executor.submit(() -> {
                tx2Attempted.countDown();
                return adapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, v2Nbt);
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
            ProfileInventoryMutationOutcome tx2Result = tx2Future.get(10, TimeUnit.SECONDS);
            assertThat(tx2Result.isSuccess()).isTrue();
            assertThat(tx2Result).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

            // Final state: version 2 and marker 2
            Optional<ProfileInventoryRecord> loaded = invAdapter.loadInventory(profile);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().version()).isEqualTo(2L);
            assertThat(loaded.get().inventoryNbt()).isEqualTo(v2Nbt);
            assertThat(adapter.loadLastDurableInventoryVersion(player)).hasValue(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void verifyCrashWindowRecoveryAndFullHandoff(
            Database database,
            PlayerProfileInventoryAdapter inventoryAdapter,
            PlayerProfileHandoffFinalizationAdapter finalizationAdapter,
            PlayerSessionAuthorityAdapter sessionAdapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2FinalNbt = new byte[] {4, 5, 6};

        // Node A runs session at epoch 1
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {10}));

        // 1. Node A DRAIN
        SessionAuthorityOutcome drainOutcome = sessionAdapter.drain(player, NODE_A, 1L);
        assertThat(drainOutcome.isSuccess()).isTrue();

        // 2. Node A final durable inventory flush (version 2, marker 2)
        ProfileInventoryMutationOutcome flushOutcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, v2FinalNbt);
        assertThat(flushOutcome.isSuccess()).isTrue();
        assertThat(flushOutcome).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // 3. Simulated crash of Node A!
        // Lease expires on Node A:
        expireSession(database, player);

        // 4. Node B detects crash and executes failure takeover
        SessionAuthorityOutcome takeover = sessionAdapter.failureTakeover(player, 1L, NODE_B);
        assertThat(takeover.isSuccess()).isTrue();
        assertThat(takeover).isEqualTo(SessionAuthorityOutcome.success(2L, true)); // Epoch increments 1 -> 2, recovering=true

        // 5. Node B loads durable inventory state matching last_durable_inventory_version
        OptionalLong sessionMarker = finalizationAdapter.loadLastDurableInventoryVersion(player);
        assertThat(sessionMarker).hasValue(2L);

        Optional<ProfileInventoryRecord> restoredRecord = inventoryAdapter.loadInventory(profile);
        assertThat(restoredRecord).isPresent();
        assertThat(restoredRecord.get().version()).isEqualTo(sessionMarker.getAsLong());
        assertThat(restoredRecord.get().inventoryNbt()).isEqualTo(v2FinalNbt);

        // 6. Test full clean handoff protocol on another fresh player
        PlayerUuid player2 = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile2 = ProfileId.of(UUID.randomUUID());
        String handoffId = UUID.randomUUID().toString();
        byte[] cleanFinalNbt = new byte[] {9, 8, 7};

        seedSession(database, player2, profile2, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile2, v1Nbt, new byte[] {0}));

        // DRAIN -> Final Flush -> prepareHandoff -> plannedAcquire
        assertThat(sessionAdapter.drain(player2, NODE_A, 1L).isSuccess()).isTrue();
        assertThat(finalizationAdapter
                        .finalizeHandoffFlush(player2, profile2, NODE_A, 1L, 1L, cleanFinalNbt)
                        .isSuccess())
                .isTrue();
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player2)).hasValue(2L);

        assertThat(sessionAdapter
                        .prepareHandoff(player2, NODE_A, 1L, handoffId, NODE_B)
                        .isSuccess())
                .isTrue();
        SessionAuthorityOutcome acquireOutcome = sessionAdapter.plannedAcquire(player2, NODE_A, 1L, handoffId, NODE_B);
        assertThat(acquireOutcome.isSuccess()).isTrue();
        assertThat(acquireOutcome).isEqualTo(SessionAuthorityOutcome.success(2L));

        Optional<ProfileInventoryRecord> player2Record = inventoryAdapter.loadInventory(profile2);
        assertThat(player2Record).isPresent();
        assertThat(player2Record.get().version()).isEqualTo(2L);
        assertThat(player2Record.get().inventoryNbt()).isEqualTo(cleanFinalNbt);
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

            String leaseExpr;
            if (database.dialect() == Dialect.MYSQL) {
                leaseExpr =
                        expired ? "CURRENT_TIMESTAMP - INTERVAL 10 SECOND" : "CURRENT_TIMESTAMP + INTERVAL 60 SECOND";
            } else {
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
            throw new RuntimeException("Failed to seed server DB test session", e);
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
        String expr = database.dialect() == Dialect.MYSQL
                ? "CURRENT_TIMESTAMP - INTERVAL 10 SECOND"
                : "CURRENT_TIMESTAMP - INTERVAL '10 seconds'";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = " + expr + " WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire session", e);
        }
    }
}
