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
 * Proves optimistic concurrency control (OCC), session authority fencing, DB lease checks,
 * and temporal row-level lock serialization with zero {@code Thread.sleep}.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaAdapter = new PlayerProfileInventoryAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresAdapter = new PlayerProfileInventoryAdapter(postgresDatabase);
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
    @DisplayName("MariaDB 1: Checkpoint Lifecycle and OCC Mutation")
    void mariaDbCheckpointLifecycleAndOcc() {
        verifyCheckpointLifecycleAndOcc(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("MariaDB 2: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void mariaDbRowLockSerialization() throws Exception {
        verifyRowLockSerialization(mariaDatabase, mariaAdapter);
    }

    // ==========================================
    // PostgreSQL Integration Tests
    // ==========================================

    @Test
    @Order(3)
    @DisplayName("PostgreSQL 1: Checkpoint Lifecycle and OCC Mutation")
    void postgresCheckpointLifecycleAndOcc() {
        verifyCheckpointLifecycleAndOcc(postgresDatabase, postgresAdapter);
    }

    @Test
    @Order(4)
    @DisplayName("PostgreSQL 2: Canonical Row Lock Serialization via SELECT ... FOR UPDATE")
    void postgresRowLockSerialization() throws Exception {
        verifyRowLockSerialization(postgresDatabase, postgresAdapter);
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
        ProfileInventoryMutationOutcome outcome1 = adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, v2Nbt);
        assertThat(outcome1).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        Optional<ProfileInventoryRecord> loaded1 = adapter.loadInventory(profile);
        assertThat(loaded1).isPresent();
        assertThat(loaded1.get().version()).isEqualTo(2L);
        assertThat(loaded1.get().inventoryNbt()).isEqualTo(v2Nbt);

        // 3. Monotonic increment (2 -> 3)
        ProfileInventoryMutationOutcome outcome2 = adapter.checkpointInventory(player, profile, NODE_A, 1L, 2L, v3Nbt);
        assertThat(outcome2).isEqualTo(ProfileInventoryMutationOutcome.success(3L));

        Optional<ProfileInventoryRecord> loaded2 = adapter.loadInventory(profile);
        assertThat(loaded2).isPresent();
        assertThat(loaded2.get().version()).isEqualTo(3L);
        assertThat(loaded2.get().inventoryNbt()).isEqualTo(v3Nbt);

        // 4. Stale OCC version rejected (expectedVersion 2 when version is 3)
        ProfileInventoryMutationOutcome stale =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 2L, new byte[] {99});
        assertThat(stale.isRejected()).isTrue();

        // 5. Wrong node rejected
        ProfileInventoryMutationOutcome wrongNode =
                adapter.checkpointInventory(player, profile, NODE_B, 1L, 3L, new byte[] {99});
        assertThat(wrongNode.isRejected()).isTrue();

        // 6. Stale epoch rejected
        ProfileInventoryMutationOutcome staleEpoch =
                adapter.checkpointInventory(player, profile, NODE_A, 99L, 3L, new byte[] {99});
        assertThat(staleEpoch.isRejected()).isTrue();

        // 7. Non-ACTIVE state rejected (DRAINING)
        setSessionState(database, player, "DRAINING");
        ProfileInventoryMutationOutcome draining =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 3L, new byte[] {99});
        assertThat(draining.isRejected()).isTrue();

        // 8. Expired lease rejected
        setSessionState(database, player, "ACTIVE");
        expireSession(database, player);
        ProfileInventoryMutationOutcome expired =
                adapter.checkpointInventory(player, profile, NODE_A, 1L, 3L, new byte[] {99});
        assertThat(expired.isRejected()).isTrue();

        // Verify final aggregate is untouched at version 3
        Optional<ProfileInventoryRecord> finalRecord = adapter.loadInventory(profile);
        assertThat(finalRecord).isPresent();
        assertThat(finalRecord.get().version()).isEqualTo(3L);
        assertThat(finalRecord.get().inventoryNbt()).isEqualTo(v3Nbt);
    }

    private static void verifyRowLockSerialization(Database database, PlayerProfileInventoryAdapter adapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 1, 1};
        byte[] v2Nbt = new byte[] {2, 2, 2};
        byte[] vBAttemptNbt = new byte[] {9, 9, 9};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false);
        adapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aLockAcquired = new CountDownLatch(1);
        CountDownLatch bAttemptStarted = new CountDownLatch(1);
        CountDownLatch aReleaseSignal = new CountDownLatch(1);

        try {
            // Transaction A: locks player_sessions row for player, validates authority, and holds lock
            Future<ProfileInventoryMutationOutcome> futureA = executor.submit(() -> {
                return adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, v2Nbt, () -> {
                    aLockAcquired.countDown();
                    try {
                        boolean release = aReleaseSignal.await(10, TimeUnit.SECONDS);
                        assertThat(release).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });

            // Wait for Transaction A to acquire the lock
            assertThat(aLockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            // Transaction B: attempts conflicting checkpoint on the SAME player while A holds the row lock
            Future<ProfileInventoryMutationOutcome> futureB = executor.submit(() -> {
                bAttemptStarted.countDown();
                // This call blocks on SELECT player_sessions ... FOR UPDATE until Transaction A commits
                return adapter.checkpointInventory(player, profile, NODE_A, 1L, 1L, vBAttemptNbt);
            });

            // Wait for Transaction B to enter its execution attempt
            assertThat(bAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // Transaction B demonstrably cannot pass while Transaction A holds the authority row
            assertThatThrownBy(() -> futureB.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            // Release Transaction A to complete its mutation and commit
            aReleaseSignal.countDown();

            // Both transactions complete
            ProfileInventoryMutationOutcome outcomeA = futureA.get(10, TimeUnit.SECONDS);
            ProfileInventoryMutationOutcome outcomeB = futureB.get(10, TimeUnit.SECONDS);

            // Transaction A succeeded, advancing version to 2
            assertThat(outcomeA).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

            // Transaction B unblocked after A committed, but its expectedVersion was 1L (stale),
            // so Transaction B was rejected without lost updates!
            assertThat(outcomeB.isRejected()).isTrue();

            // Final state: version 2 with A's committed NBT
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
