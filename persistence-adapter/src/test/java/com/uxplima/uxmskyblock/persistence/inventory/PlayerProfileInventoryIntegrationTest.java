package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
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
 * P2 Integration Lane test suite for {@link PlayerProfileInventoryAdapter} (WP2-003).
 *
 * <p>Runs against real MariaDB 10.11.11 and PostgreSQL 15.12-alpine Testcontainers.
 * Proves optimistic concurrency control (OCC), session authority fencing, active profile binding,
 * DB lease checks, and temporal row-level lock serialization with zero {@code Thread.sleep}.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerProfileInventoryIntegrationTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-bravo");

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerProfileInventoryAdapter mariaAdapter;
    private static PlayerProfileInventoryAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerProfileInventoryAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerProfileInventoryAdapter(postgresDatabase);
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

    // ==========================================
    // MariaDB Integration Tests
    // ==========================================

    @Test
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 1: Checkpoint Lifecycle and OCC Mutation")
    void mariaDbCheckpointLifecycleAndOcc() {
        verifyCheckpointLifecycleAndOcc(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 2: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void mariaDbRowLockSerialization() throws Exception {
        verifyRowLockSerialization(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 3: Cross-Profile Mismatch is Rejected without Mutating Profile")
    void mariaDbCrossProfileRejection() {
        verifyCrossProfileRejection(mariaDatabase, mariaAdapter);
    }

    // ==========================================
    // PostgreSQL Integration Tests
    // ==========================================

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 1: Checkpoint Lifecycle and OCC Mutation")
    void postgresCheckpointLifecycleAndOcc() {
        verifyCheckpointLifecycleAndOcc(postgresDatabase, postgresAdapter);
    }

    @Test
    @Order(5)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 2: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void postgresRowLockSerialization() throws Exception {
        verifyRowLockSerialization(postgresDatabase, postgresAdapter);
    }

    @Test
    @Order(6)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 3: Cross-Profile Mismatch is Rejected without Mutating Profile")
    void postgresCrossProfileRejection() {
        verifyCrossProfileRejection(postgresDatabase, postgresAdapter);
    }

    // ==========================================
    // Shared Verification Logic
    // ==========================================

    private static void verifyCheckpointLifecycleAndOcc(Database database, PlayerProfileInventoryAdapter adapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2Nbt = new byte[] {4, 5, 6};
        byte[] v3Nbt = new byte[] {7, 8, 9};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {10, 20}));

        // 1. Initial state
        Optional<ProfileInventoryRecord> initial = adapter.loadInventory(profile);
        assertThat(initial).isPresent();
        assertThat(initial.get().version()).isEqualTo(1L);
        assertThat(initial.get().inventoryNbt()).isEqualTo(v1Nbt);

        // 2. Valid OCC mutation (1 -> 2)
        ProfileInventoryMutationOutcome outcome1 = adapter.checkpointInventory(
                player, profile, NODE_A, 1L, 1L, ProfileInventoryRecord.createDefault(profile, v2Nbt, new byte[0]));
        assertThat(outcome1).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        Optional<ProfileInventoryRecord> loaded1 = adapter.loadInventory(profile);
        assertThat(loaded1).isPresent();
        assertThat(loaded1.get().version()).isEqualTo(2L);
        assertThat(loaded1.get().inventoryNbt()).isEqualTo(v2Nbt);

        // 3. Monotonic increment (2 -> 3)
        ProfileInventoryMutationOutcome outcome2 = adapter.checkpointInventory(
                player, profile, NODE_A, 1L, 2L, ProfileInventoryRecord.createDefault(profile, v3Nbt, new byte[0]));
        assertThat(outcome2).isEqualTo(ProfileInventoryMutationOutcome.success(3L));

        Optional<ProfileInventoryRecord> loaded2 = adapter.loadInventory(profile);
        assertThat(loaded2).isPresent();
        assertThat(loaded2.get().version()).isEqualTo(3L);
        assertThat(loaded2.get().inventoryNbt()).isEqualTo(v3Nbt);

        // 4. Stale OCC version rejected (expectedVersion 2 when version is 3)
        ProfileInventoryMutationOutcome stale = adapter.checkpointInventory(
                player,
                profile,
                NODE_A,
                1L,
                2L,
                ProfileInventoryRecord.createDefault(profile, new byte[] {99}, new byte[0]));
        assertThat(stale.isRejected()).isTrue();

        // 5. Wrong node rejected
        ProfileInventoryMutationOutcome wrongNode = adapter.checkpointInventory(
                player,
                profile,
                NODE_B,
                1L,
                3L,
                ProfileInventoryRecord.createDefault(profile, new byte[] {99}, new byte[0]));
        assertThat(wrongNode.isRejected()).isTrue();

        // 6. Stale epoch rejected
        ProfileInventoryMutationOutcome staleEpoch = adapter.checkpointInventory(
                player,
                profile,
                NODE_A,
                99L,
                3L,
                ProfileInventoryRecord.createDefault(profile, new byte[] {99}, new byte[0]));
        assertThat(staleEpoch.isRejected()).isTrue();

        // 7. Non-ACTIVE state rejected (DRAINING)
        setSessionState(database, player, "DRAINING");
        ProfileInventoryMutationOutcome draining = adapter.checkpointInventory(
                player,
                profile,
                NODE_A,
                1L,
                3L,
                ProfileInventoryRecord.createDefault(profile, new byte[] {99}, new byte[0]));
        assertThat(draining.isRejected()).isTrue();

        // 8. Expired lease rejected
        setSessionState(database, player, "ACTIVE");
        expireSession(database, player);
        ProfileInventoryMutationOutcome expired = adapter.checkpointInventory(
                player,
                profile,
                NODE_A,
                1L,
                3L,
                ProfileInventoryRecord.createDefault(profile, new byte[] {99}, new byte[0]));
        assertThat(expired.isRejected()).isTrue();

        // Verify final aggregate is untouched at version 3
        Optional<ProfileInventoryRecord> finalRecord = adapter.loadInventory(profile);
        assertThat(finalRecord).isPresent();
        assertThat(finalRecord.get().version()).isEqualTo(3L);
        assertThat(finalRecord.get().inventoryNbt()).isEqualTo(v3Nbt);
    }

    private static void verifyCrossProfileRejection(Database database, PlayerProfileInventoryAdapter adapter) {
        PlayerUuid playerA = PlayerUuid.of(UUID.randomUUID());
        ProfileId profileA = ProfileId.of(UUID.randomUUID());
        ProfileId profileB = ProfileId.of(UUID.randomUUID());
        byte[] vA = new byte[] {1, 1};
        byte[] vB = new byte[] {2, 2};
        byte[] payload = new byte[] {9, 9};

        // Player A has active session with profileA
        seedSession(database, playerA, profileA, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profileA, vA, new byte[] {0}));

        // Profile B exists in the database with version 1
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
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profileB, vB, new byte[] {0}));

        // Player A attempts to checkpoint Profile B with matching expected version (1L)
        ProfileInventoryMutationOutcome outcome = adapter.checkpointInventory(
                playerA,
                profileB,
                NODE_A,
                1L,
                1L,
                ProfileInventoryRecord.createDefault(profileB, payload, new byte[0]));

        assertThat(outcome.isRejected())
                .as("Cross-profile mutation must be rejected")
                .isTrue();

        // Verify Profile A is untouched
        Optional<ProfileInventoryRecord> loadedA = adapter.loadInventory(profileA);
        assertThat(loadedA).isPresent();
        assertThat(loadedA.get().version()).isEqualTo(1L);
        assertThat(loadedA.get().inventoryNbt()).isEqualTo(vA);

        // Verify Profile B is untouched
        Optional<ProfileInventoryRecord> loadedB = adapter.loadInventory(profileB);
        assertThat(loadedB).isPresent();
        assertThat(loadedB.get().version()).isEqualTo(1L);
        assertThat(loadedB.get().inventoryNbt()).isEqualTo(vB);
    }

    private static void verifyRowLockSerialization(Database database, PlayerProfileInventoryAdapter adapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 1, 1};
        byte[] v2Nbt = new byte[] {2, 2, 2};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aLockAcquired = new CountDownLatch(1);
        CountDownLatch bAttemptStarted = new CountDownLatch(1);
        CountDownLatch aReleaseSignal = new CountDownLatch(1);

        try {
            // TxA: raw test connection holding SELECT ... FOR UPDATE on player_sessions
            Future<?> txAFuture = executor.submit(() -> {
                try (Connection txAConn = database.connection()) {
                    txAConn.setAutoCommit(false);
                    try (PreparedStatement ps = txAConn.prepareStatement(
                            "SELECT player_uuid FROM player_sessions WHERE player_uuid = ? FOR UPDATE")) {
                        ps.setString(1, player.value().toString());
                        ps.executeQuery();
                    }
                    aLockAcquired.countDown();
                    boolean released = aReleaseSignal.await(10, TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                    txAConn.rollback();
                } catch (Exception e) {
                    throw new RuntimeException("TxA failed", e);
                }
            });

            // Wait for TxA to acquire the row lock
            assertThat(aLockAcquired.await(10, TimeUnit.SECONDS))
                    .as("TxA must acquire row lock")
                    .isTrue();

            // TxB: calls real production checkpointInventory on the SAME player_uuid
            Future<ProfileInventoryMutationOutcome> txBFuture = executor.submit(() -> {
                bAttemptStarted.countDown();
                return adapter.checkpointInventory(
                        player,
                        profile,
                        NODE_A,
                        1L,
                        1L,
                        ProfileInventoryRecord.createDefault(profile, v2Nbt, new byte[0]));
            });

            // Wait for TxB to begin its execution attempt
            assertThat(bAttemptStarted.await(10, TimeUnit.SECONDS))
                    .as("TxB must start attempt")
                    .isTrue();

            // Prove TxB is blocked behind TxA's row lock: short get() must time out, and txBFuture is not done
            assertThatThrownBy(() -> txBFuture.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(txBFuture.isDone()).isFalse();

            // Release TxA so it rolls back and relinquishes the lock
            aReleaseSignal.countDown();
            txAFuture.get(5, TimeUnit.SECONDS);

            // TxB unblocks, acquires row lock, executes OCC, and succeeds
            ProfileInventoryMutationOutcome outcomeB = txBFuture.get(10, TimeUnit.SECONDS);
            assertThat(outcomeB.isSuccess()).isTrue();
            assertThat(outcomeB).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

            // Final state: version 2 with v2Nbt
            Optional<ProfileInventoryRecord> loaded = adapter.loadInventory(profile);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().version()).isEqualTo(2L);
            assertThat(loaded.get().inventoryNbt()).isEqualTo(v2Nbt);
        } finally {
            executor.shutdownNow();
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

            String leaseExpr = getLeaseExpression(database.dialect(), expired);
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
            throw new RuntimeException("Failed to seed server DB test session", e);
        }
    }

    private static String getLeaseExpression(Dialect dialect, boolean expired) {
        int seconds = expired ? -10 : 60;
        return switch (dialect) {
            case MYSQL -> "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'";
            case SQLITE -> "DATETIME('now', '" + seconds + " seconds')";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
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
        String expr =
                switch (database.dialect()) {
                    case MYSQL -> "CURRENT_TIMESTAMP - INTERVAL 10 SECOND";
                    case POSTGRES -> "CURRENT_TIMESTAMP - INTERVAL '10 seconds'";
                    case SQLITE -> "DATETIME('now', '-10 seconds')";
                    case H2, GENERIC ->
                        throw new IllegalArgumentException("Unsupported dialect: " + database.dialect());
                };
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE player_sessions SET lease_expires_at = " + expr + " WHERE player_uuid = '"
                    + player.value() + "'");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire session", e);
        }
    }
}
