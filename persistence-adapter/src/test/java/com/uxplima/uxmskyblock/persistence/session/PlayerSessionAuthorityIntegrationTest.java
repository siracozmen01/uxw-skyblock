package com.uxplima.uxmskyblock.persistence.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real Server-SQL Integration Lane test suite for {@link PlayerSessionAuthorityAdapter}
 * exercising containerized MariaDB 10.11.11 and PostgreSQL 15.12-alpine.
 *
 * <p>Tagged {@code @Tag("database-integration")} to run under the dedicated
 * {@code :persistence-adapter:databaseIntegrationTest} task.
 *
 * <p>Validates all 5 source-proven transitions, fencing predicates, and cross-node concurrency
 * scenarios (A: renewal wins before expiry, B: expired takeover, C: planned acquire) using
 * deterministic latches/futures without {@code Thread.sleep}.
 */
@Tag("database-integration")
@SuppressWarnings("NullAway")
class PlayerSessionAuthorityIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerSessionAuthorityAdapter mariaAdapter;
    private static PlayerSessionAuthorityAdapter postgresAdapter;

    private static final ServerNodeId NODE_A = ServerNodeId.of("backend-node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("backend-node-b");

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(Dialect.MYSQL));
            mariaAdapter = new PlayerSessionAuthorityAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(Dialect.POSTGRES));
            postgresAdapter = new PlayerSessionAuthorityAdapter(postgresDatabase);
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
    // MariaDB Lifecycle & Concurrency Tests
    // ==========================================

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Full Player Session Authority Lifecycle (Renew, Drain, Handoff, Acquire, Takeover)")
    void mariaDbLifecyclePasses() throws Exception {
        verifyAuthorityLifecycle(mariaDatabase, mariaAdapter, "mariadb");
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB Concurrency Scenario A: Renewal wins before lease expiry; concurrent takeover is rejected")
    void mariaDbConcurrencyScenarioAWinsBeforeExpiry() throws Exception {
        verifyConcurrencyScenarioA(mariaDatabase, mariaAdapter);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB Concurrency Scenario B: Expired takeover succeeds, increments epoch, and fences stale owner")
    void mariaDbConcurrencyScenarioBExpiredTakeover() throws Exception {
        verifyConcurrencyScenarioB(mariaDatabase, mariaAdapter);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName(
            "MariaDB Concurrency Scenario C: Planned acquire transfers ownership, increments epoch, and fences stale owner")
    void mariaDbConcurrencyScenarioCPlannedAcquire() throws Exception {
        verifyConcurrencyScenarioC(mariaDatabase, mariaAdapter, "maria-concur-c");
    }

    // ==========================================
    // PostgreSQL Lifecycle & Concurrency Tests
    // ==========================================

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Full Player Session Authority Lifecycle (Renew, Drain, Handoff, Acquire, Takeover)")
    void postgresLifecyclePasses() throws Exception {
        verifyAuthorityLifecycle(postgresDatabase, postgresAdapter, "postgres");
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL Concurrency Scenario A: Renewal wins before lease expiry; concurrent takeover is rejected")
    void postgresConcurrencyScenarioAWinsBeforeExpiry() throws Exception {
        verifyConcurrencyScenarioA(postgresDatabase, postgresAdapter);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName(
            "PostgreSQL Concurrency Scenario B: Expired takeover succeeds, increments epoch, and fences stale owner")
    void postgresConcurrencyScenarioBExpiredTakeover() throws Exception {
        verifyConcurrencyScenarioB(postgresDatabase, postgresAdapter);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName(
            "PostgreSQL Concurrency Scenario C: Planned acquire transfers ownership, increments epoch, and fences stale owner")
    void postgresConcurrencyScenarioCPlannedAcquire() throws Exception {
        verifyConcurrencyScenarioC(postgresDatabase, postgresAdapter, "pg-concur-c");
    }

    // ==========================================
    // Shared Behavioral Verification Logic
    // ==========================================

    private static void verifyAuthorityLifecycle(
            Database database, PlayerSessionAuthorityAdapter adapter, String prefix) throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        // 1. Seed valid session
        seedSession(database, player, profile, NODE_A, 1L, SessionState.ACTIVE, false);

        // 2. Valid RENEW: does not increment epoch
        SessionAuthorityOutcome renewOutcome = adapter.renew(player, NODE_A, 1L);
        assertThat(renewOutcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        // 3. Stale epoch RENEW: rejected
        assertThat(adapter.renew(player, NODE_A, 99L).isRejected()).isTrue();

        // 4. Wrong node RENEW: rejected
        assertThat(adapter.renew(player, NODE_B, 1L).isRejected()).isTrue();

        // 5. Valid DRAIN: ACTIVE -> DRAINING, epoch unchanged
        SessionAuthorityOutcome drainOutcome = adapter.drain(player, NODE_A, 1L);
        assertThat(drainOutcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        // 6. Invalid DRAIN: cannot drain already DRAINING session
        assertThat(adapter.drain(player, NODE_A, 1L).isRejected()).isTrue();

        // 7. Valid PREPARE_HANDOFF: DRAINING -> HANDOFF_READY
        String handoffId = prefix + "-handoff-1";
        SessionAuthorityOutcome handoffOutcome = adapter.prepareHandoff(player, NODE_A, 1L, handoffId, NODE_B);
        assertThat(handoffOutcome).isEqualTo(SessionAuthorityOutcome.success(1L));

        // 8. Valid PLANNED_ACQUIRE: increments epoch, sets ACTIVE, clears handoff
        SessionAuthorityOutcome acquireOutcome = adapter.plannedAcquire(player, NODE_A, 1L, handoffId, NODE_B);
        assertThat(acquireOutcome).isEqualTo(SessionAuthorityOutcome.success(2L));

        // 9. Stale old owner Node A is fenced
        assertThat(adapter.renew(player, NODE_A, 1L).isRejected()).isTrue();
        assertThat(adapter.drain(player, NODE_A, 1L).isRejected()).isTrue();

        // 10. Node B cannot take over unexpired session
        assertThat(adapter.failureTakeover(player, 2L, ServerNodeId.of("node-c"))
                        .isRejected())
                .isTrue();

        // 11. Manually expire session to test FAILURE_TAKEOVER
        expireSession(database, player);

        // 12. Valid FAILURE_TAKEOVER: increments epoch, sets RECOVERING
        ServerNodeId recoveryNode = ServerNodeId.of("recovery-node");
        SessionAuthorityOutcome takeoverOutcome = adapter.failureTakeover(player, 2L, recoveryNode);
        assertThat(takeoverOutcome).isEqualTo(SessionAuthorityOutcome.success(3L, true));
        assertThat(takeoverOutcome.isRecovering()).isTrue();

        // Verify final row state
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT state, authoritative_node, session_epoch, handoff_id FROM player_sessions WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("RECOVERING");
                assertThat(rs.getString("authoritative_node")).isEqualTo(recoveryNode.value());
                assertThat(rs.getLong("session_epoch")).isEqualTo(3L);
                assertThat(rs.getString("handoff_id")).isNull();
            }
        }

        // 13. markRecoveredActive: RECOVERING -> ACTIVE
        SessionAuthorityOutcome recoveredOutcome = adapter.markRecoveredActive(player, recoveryNode, 3L);
        assertThat(recoveredOutcome).isEqualTo(SessionAuthorityOutcome.success(3L, false));

        // 14. drain then releaseToOffline: ACTIVE -> DRAINING -> OFFLINE
        SessionAuthorityOutcome offlineDrainOutcome = adapter.drain(player, recoveryNode, 3L);
        assertThat(offlineDrainOutcome).isEqualTo(SessionAuthorityOutcome.success(3L, false));
        SessionAuthorityOutcome offlineOutcome = adapter.releaseToOffline(player, recoveryNode, 3L);
        assertThat(offlineOutcome).isEqualTo(SessionAuthorityOutcome.success(3L, false));
        assertThat(adapter.findSession(player).orElseThrow().state()).isEqualTo(SessionState.OFFLINE);

        // Verify account and profile records were not modified
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT active_profile_id FROM player_accounts WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("active_profile_id"))
                        .isEqualTo(profile.value().toString());
            }
        }
    }

    private static void verifyConcurrencyScenarioA(Database database, PlayerSessionAuthorityAdapter adapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, SessionState.ACTIVE, false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);

        try {
            // Thread A: Valid owner renews lease
            Future<SessionAuthorityOutcome> futureA = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.renew(player, NODE_A, 1L);
            });

            // Thread B: Competing takeover attempts to steal authority while unexpired
            Future<SessionAuthorityOutcome> futureB = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.failureTakeover(player, 1L, NODE_B);
            });

            startSignal.countDown();

            SessionAuthorityOutcome outcomeA = futureA.get(5, TimeUnit.SECONDS);
            SessionAuthorityOutcome outcomeB = futureB.get(5, TimeUnit.SECONDS);

            // Renewal must succeed; takeover must be rejected
            assertThat(outcomeA).isEqualTo(SessionAuthorityOutcome.success(1L));
            assertThat(outcomeB.isRejected()).isTrue();

            // Final state: still NODE_A, epoch 1, ACTIVE
            try (Connection conn = database.connection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT authoritative_node, session_epoch, state FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, player.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_A.value());
                    assertThat(rs.getLong("session_epoch")).isEqualTo(1L);
                    assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void verifyConcurrencyScenarioB(Database database, PlayerSessionAuthorityAdapter adapter)
            throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        // Expired lease
        seedSession(database, player, profile, NODE_A, 1L, SessionState.ACTIVE, true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);

        try {
            // Thread B: Node B takes over expired lease
            Future<SessionAuthorityOutcome> futureTakeover = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.failureTakeover(player, 1L, NODE_B);
            });

            // Thread A: Stale owner attempts renew
            Future<SessionAuthorityOutcome> futureRenew = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.renew(player, NODE_A, 1L);
            });

            startSignal.countDown();

            SessionAuthorityOutcome takeoverOutcome = futureTakeover.get(5, TimeUnit.SECONDS);
            SessionAuthorityOutcome renewOutcome = futureRenew.get(5, TimeUnit.SECONDS);

            // Takeover must succeed (new epoch 2); stale renewal must fail
            assertThat(takeoverOutcome).isEqualTo(SessionAuthorityOutcome.success(2L, true));
            assertThat(renewOutcome.isRejected()).isTrue();

            // Final state: NODE_B, epoch 2, RECOVERING
            try (Connection conn = database.connection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT authoritative_node, session_epoch, state FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, player.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_B.value());
                    assertThat(rs.getLong("session_epoch")).isEqualTo(2L);
                    assertThat(rs.getString("state")).isEqualTo("RECOVERING");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void verifyConcurrencyScenarioC(
            Database database, PlayerSessionAuthorityAdapter adapter, String prefix) throws Exception {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        String handoffId = prefix + "-handoff-c";

        // Seed session in HANDOFF_READY
        seedSession(database, player, profile, NODE_A, 1L, SessionState.DRAINING, false);
        adapter.prepareHandoff(player, NODE_A, 1L, handoffId, NODE_B);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);

        try {
            // Thread B: Node B executes planned acquire
            Future<SessionAuthorityOutcome> futureAcquire = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.plannedAcquire(player, NODE_A, 1L, handoffId, NODE_B);
            });

            // Thread A: Stale Node A attempts renewal
            Future<SessionAuthorityOutcome> futureStaleRenew = executor.submit(() -> {
                startSignal.await(5, TimeUnit.SECONDS);
                return adapter.renew(player, NODE_A, 1L);
            });

            startSignal.countDown();

            SessionAuthorityOutcome acquireOutcome = futureAcquire.get(5, TimeUnit.SECONDS);
            SessionAuthorityOutcome staleOutcome = futureStaleRenew.get(5, TimeUnit.SECONDS);

            assertThat(acquireOutcome).isEqualTo(SessionAuthorityOutcome.success(2L));
            assertThat(staleOutcome.isRejected()).isTrue();

            // Final state: NODE_B, epoch 2, ACTIVE, handoff cleared
            try (Connection conn = database.connection();
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT authoritative_node, session_epoch, state, handoff_id FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, player.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("authoritative_node")).isEqualTo(NODE_B.value());
                    assertThat(rs.getLong("session_epoch")).isEqualTo(2L);
                    assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                    assertThat(rs.getString("handoff_id")).isNull();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void seedSession(
            Database database,
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId node,
            long epoch,
            SessionState state,
            boolean expired)
            throws SQLException {
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id) VALUES (?, NULL)")) {
                ps.setString(1, playerUuid.value().toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }

            String leaseSql;
            if (database.dialect() == Dialect.MYSQL) {
                leaseSql =
                        expired ? "CURRENT_TIMESTAMP - INTERVAL 30 SECOND" : "CURRENT_TIMESTAMP + INTERVAL 30 SECOND";
            } else {
                leaseSql = expired
                        ? "CURRENT_TIMESTAMP - INTERVAL '30 seconds'"
                        : "CURRENT_TIMESTAMP + INTERVAL '30 seconds'";
            }

            String sql = "INSERT INTO player_sessions ("
                    + "player_uuid, active_profile_id, authoritative_node, session_epoch, state, lease_expires_at"
                    + ") VALUES (?, ?, ?, ?, ?, " + leaseSql + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.value().toString());
                ps.setString(2, profileId.value().toString());
                ps.setString(3, node.value());
                ps.setLong(4, epoch);
                ps.setString(5, state.name());
                ps.executeUpdate();
            }
        }
    }

    private static void expireSession(Database database, PlayerUuid playerUuid) throws SQLException {
        try (Connection conn = database.connection()) {
            String expr = database.dialect() == Dialect.MYSQL
                    ? "CURRENT_TIMESTAMP - INTERVAL 60 SECOND"
                    : "CURRENT_TIMESTAMP - INTERVAL '60 seconds'";
            String sql = "UPDATE player_sessions SET lease_expires_at = " + expr + " WHERE player_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.value().toString());
                ps.executeUpdate();
            }
        }
    }
}
