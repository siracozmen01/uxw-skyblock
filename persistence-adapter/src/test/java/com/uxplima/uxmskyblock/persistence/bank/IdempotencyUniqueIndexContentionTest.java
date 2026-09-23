package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
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
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Ten identical scoped requests racing each other move the money once.
 *
 * <p>TESTING_STANDARDS names this test and the contract behind it: identical requests under one
 * {@code (operation_scope, actor_id, idempotency_key)} arrive at once, one of them applies, and none of
 * the others applies a second time. The contract had no test. This one races ten real transactions on
 * each database engine. The standard's second half, a winner held past the lock timeout so a loser
 * sees the operation still in progress, needs a hook to hold the winner that the adapter does not
 * have, and is not claimed here.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class IdempotencyUniqueIndexContentionTest {

    private static final String NODE = "node-alpha";
    private static final long EPOCH = 1L;
    private static final long AMOUNT = 2_500L;
    private static final int RACERS = 10;

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;
    private static Database mariaDatabase;
    private static Database postgresDatabase;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
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
    @EnabledIfMariaDb
    @DisplayName("MariaDB: ten identical scoped deposits at once apply once")
    void mariaDbAppliesOnce() throws Exception {
        raceIdenticalDeposits(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: ten identical scoped deposits at once apply once")
    void postgresAppliesOnce() throws Exception {
        raceIdenticalDeposits(postgresDatabase);
    }

    private static void raceIdenticalDeposits(Database db) throws Exception {
        PlayerIslandBankAdapter adapter = new PlayerIslandBankAdapter(db);
        IslandId islandId = IslandId.of(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        seedIsland(db, islandId, actor);
        adapter.createBank(islandId);
        String key = UUID.randomUUID().toString();

        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        List<BankTransactionOutcome> outcomes = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<BankTransactionOutcome>> racing = new ArrayList<>();
            for (int i = 0; i < RACERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    // A fresh operation id each, the way a caller retrying one request looks: only
                    // the scope, the actor and the key say they are the same request.
                    return adapter.executeTransaction(
                            islandId,
                            actor,
                            "PRIMARY",
                            2,
                            AMOUNT,
                            "Webstore deposit",
                            NODE,
                            EPOCH,
                            1L,
                            UUID.randomUUID(),
                            key,
                            "REST_BANK_DEPOSIT",
                            null);
                }));
            }
            start.countDown();
            for (Future<BankTransactionOutcome> each : racing) {
                outcomes.add(each.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes.stream().filter(BankTransactionOutcome.Success.class::isInstance))
                .describedAs("exactly one of the identical requests applies: %s", outcomes)
                .hasSize(1);
        assertThat(outcomes.stream().filter(BankTransactionOutcome.DuplicateOperation.class::isInstance))
                .describedAs("every other one is answered as the same request, not failed: %s", outcomes)
                .hasSize(RACERS - 1);
        assertThat(adapter.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits())
                .describedAs("the money moved once")
                .isEqualTo(AMOUNT);
        assertThat(countTransactions(db, islandId))
                .describedAs("one ledger row")
                .isEqualTo(1);
    }

    static void seedIsland(Database db, IslandId islandId, UUID owner) throws Exception {
        try (Connection conn = db.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, islandId.value().toString());
                ps.setString(2, owner.toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.executeUpdate();
            }
            String plus60 = (db.dialect() == Dialect.POSTGRES)
                    ? "CURRENT_TIMESTAMP + INTERVAL '60 seconds'"
                    : "CURRENT_TIMESTAMP + INTERVAL 60 SECOND";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch,"
                        + " lease_expires_at, last_heartbeat_at) VALUES ('" + islandId.value() + "', '" + NODE + "', "
                        + EPOCH + ", " + plus60 + ", CURRENT_TIMESTAMP)");
            }
        }
    }

    private static int countTransactions(Database db, IslandId islandId) throws Exception {
        try (Connection conn = db.connection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT COUNT(*) FROM bank_transactions WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
