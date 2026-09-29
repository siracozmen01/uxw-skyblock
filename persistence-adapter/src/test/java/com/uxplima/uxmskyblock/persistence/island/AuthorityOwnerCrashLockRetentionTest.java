package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.sql.SqlDeadlines;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A node that dies holding an island's authority row keeps its lock until the database lets it go, and
 * the node that wants the island waits a bounded time, fails closed, and takes it once the lock is gone.
 *
 * <p>The testing standard names this test. Node B's takeover waited behind the dead node's lock for
 * fifty seconds on MariaDB and without end on PostgreSQL, holding a pool connection the whole time. The
 * connection now carries a lock wait of its own: the takeover gives up after it, moves no epoch, and the
 * next try after the database has rolled the dead transaction back moves the epoch by one.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class AuthorityOwnerCrashLockRetentionTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    private static final Duration LOCK_WAIT = Duration.ofSeconds(1);

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: the waiter gives up in its lock wait, moves nothing, and takes over once the lock is gone")
    void mariaDb() throws Exception {
        crashWhileHoldingTheRow(mariaDbContainer);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName(
            "PostgreSQL: the waiter gives up in its lock wait, moves nothing, and takes over once the lock is gone")
    void postgres() throws Exception {
        crashWhileHoldingTheRow(postgresContainer);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a transaction left open and idle is ended by the server, and its lock with it")
    void postgresEndsAnAbandonedTransaction() throws Exception {
        try (Database nodeB = connect(postgresContainer)) {
            IslandId island = islandWithAnExpiredLease(nodeB);
            // Node A connected as the plugin does, and stopped in the middle of its transaction.
            Connection nodeA = DriverManager.getConnection(
                    SqlDeadlines.apply(postgresContainer.getJdbcUrl(), LOCK_WAIT, Duration.ofSeconds(2)),
                    postgresContainer.getUsername(),
                    postgresContainer.getPassword());
            holdTheRow(nodeA, island);

            IslandAuthorityOutcome taken = assertTimeoutPreemptively(
                    Duration.ofSeconds(20), () -> takeOverWhenFree(new PlayerIslandAuthorityAdapter(nodeB), island));

            assertThat(taken).isEqualTo(IslandAuthorityOutcome.success(2L));
            assertThat(nodeA.isValid(1))
                    .describedAs("the server closed the idle transaction")
                    .isFalse();
            nodeA.close();
        }
    }

    private static void crashWhileHoldingTheRow(JdbcDatabaseContainer<?> container) throws Exception {
        try (Database nodeB = connect(container)) {
            IslandId island = islandWithAnExpiredLease(nodeB);
            PlayerIslandAuthorityAdapter authorityB = new PlayerIslandAuthorityAdapter(nodeB);
            Connection nodeA = DriverManager.getConnection(
                    container.getJdbcUrl(), container.getUsername(), container.getPassword());
            long nodeAId = connectionId(nodeA);
            holdTheRow(nodeA, island);

            long started = System.nanoTime();
            IslandAuthorityOutcome refused = assertTimeoutPreemptively(
                    Duration.ofSeconds(15), () -> authorityB.takeoverAuthority(island, NODE_B, 1L, 60));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(refused).isEqualTo(IslandAuthorityOutcome.rejected());
            assertThat(waited).isLessThan(Duration.ofSeconds(5));
            assertThat(epoch(nodeB, island))
                    .describedAs("the waiter moves no epoch")
                    .isEqualTo(1L);

            // The node holding the lock is gone: the server drops its connection and rolls it back.
            sever(nodeB, nodeAId);

            IslandAuthorityOutcome taken =
                    assertTimeoutPreemptively(Duration.ofSeconds(15), () -> takeOverWhenFree(authorityB, island));
            assertThat(taken).isEqualTo(IslandAuthorityOutcome.success(2L));
            assertThat(epoch(nodeB, island)).isEqualTo(2L);
        }
    }

    /** Node B as the plugin connects it: through a pool, with the lock wait on every connection. */
    private static Database connect(JdbcDatabaseContainer<?> container) {
        Database database = Database.builder()
                .jdbcUrl(
                        SqlDeadlines.apply(container.getJdbcUrl(), LOCK_WAIT, SqlDeadlines.DEFAULT_IDLE_IN_TRANSACTION))
                .username(container.getUsername())
                .password(container.getPassword())
                .maxPoolSize(3)
                .build();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        return database;
    }

    private static IslandId islandWithAnExpiredLease(Database database) throws Exception {
        IslandId island = IslandId.of(UUID.randomUUID());
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, lifecycle, economic_state,"
                            + " administrative_state, level_score, net_worth_minor_units, version)"
                            + " VALUES (?, ?, ?, 'ACTIVE', 'NORMAL', 'NORMAL', 0, 0, 1)")) {
                ps.setString(1, island.value().toString());
                ps.setString(2, UUID.randomUUID().toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
        }
        assertThat(new PlayerIslandAuthorityAdapter(database).acquireAuthority(island, NODE_A, 60))
                .isEqualTo(IslandAuthorityOutcome.success(1L));
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE island_authorities SET lease_expires_at = ? WHERE island_id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf("2000-01-01 00:00:00"));
            ps.setString(2, island.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        return island;
    }

    /** The server's own number for {@code connection}, by which it can be dropped. */
    private static long connectionId(Connection connection) throws Exception {
        boolean postgres = connection.getMetaData().getURL().startsWith("jdbc:postgresql:");
        try (PreparedStatement ps =
                        connection.prepareStatement(postgres ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()");
                ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    /** Drops a connection from the server side, as the server does with a socket it finds dead. */
    private static void sever(Database database, long connectionId) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        database.dialect() == com.uxplima.uxmlib.storage.sql.Dialect.POSTGRES
                                ? "SELECT pg_terminate_backend(CAST(? AS integer))"
                                : "KILL ?")) {
            ps.setLong(1, connectionId);
            ps.execute();
        }
    }

    /** Node A in the middle of an authoritative transaction: the island's row locked and nothing more. */
    private static void holdTheRow(Connection nodeA, IslandId island) throws Exception {
        nodeA.setAutoCommit(false);
        try (PreparedStatement ps = nodeA.prepareStatement(
                "SELECT authority_epoch FROM island_authorities WHERE island_id = ? FOR UPDATE")) {
            ps.setString(1, island.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    /** Tries the takeover until the lock is free, as the sweep does on each of its turns. */
    private static IslandAuthorityOutcome takeOverWhenFree(PlayerIslandAuthorityAdapter authority, IslandId island)
            throws InterruptedException {
        while (true) {
            IslandAuthorityOutcome outcome = authority.takeoverAuthority(island, NODE_B, 1L, 60);
            if (!outcome.equals(IslandAuthorityOutcome.rejected())) {
                return outcome;
            }
            Thread.sleep(200);
        }
    }

    private static long epoch(Database database, IslandId island) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT authority_epoch FROM island_authorities WHERE island_id = ?")) {
            ps.setString(1, island.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
