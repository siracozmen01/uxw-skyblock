package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
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
 * A request waiting behind an identical one that then rolls back goes through on its own.
 *
 * <p>The testing standard's scenario: transaction A reserves {@code (scope, actor, key)} and holds
 * it, and transaction B, the same request, blocks on the unique index. A fails and rolls back. B must
 * not be stranded and must not be told it is a duplicate of something that never happened: it
 * finds no record, reserves the key itself and moves the money once.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class IdempotencyWinnerRollbackRaceTest {

    private static final String SCOPE = "REST_BANK_DEPOSIT";
    private static final long AMOUNT = 2_500L;

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
    @DisplayName("MariaDB: the request behind a rolled back twin applies, once")
    void mariaRecovers() throws Exception {
        theWaitingRequestApplies(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the request behind a rolled back twin applies, once")
    void postgresRecovers() throws Exception {
        theWaitingRequestApplies(postgresDatabase);
    }

    private static void theWaitingRequestApplies(Database db) throws Exception {
        PlayerIslandBankAdapter adapter = new PlayerIslandBankAdapter(db);
        IslandId islandId = IslandId.of(UUID.randomUUID());
        UUID actor = UUID.randomUUID();
        IdempotencyUniqueIndexContentionTest.seedIsland(db, islandId, actor);
        adapter.createBank(islandId);
        String key = UUID.randomUUID().toString();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = db.connection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement reserve = winner.prepareStatement("""
                    INSERT INTO processed_operations (
                        operation_id, operation_scope, actor_id, idempotency_key,
                        operation_type, resource_id, status, created_at
                    ) VALUES (?, ?, ?, ?, 'BANK_TRANSACTION', ?, 'PENDING', CURRENT_TIMESTAMP)
                    """)) {
                reserve.setString(1, UUID.randomUUID().toString());
                reserve.setString(2, SCOPE);
                reserve.setString(3, actor.toString());
                reserve.setString(4, key);
                reserve.setString(5, islandId.value().toString());
                reserve.executeUpdate();
            }

            Future<BankTransactionOutcome> waiting = pool.submit(() -> adapter.executeTransaction(
                    islandId,
                    actor,
                    "PRIMARY",
                    2,
                    AMOUNT,
                    "Webstore deposit",
                    "node-alpha",
                    1L,
                    1L,
                    UUID.randomUUID(),
                    key,
                    SCOPE,
                    null));
            Thread.sleep(1_000);
            assertThat(waiting.isDone())
                    .describedAs("the twin waits on the unique index while the winner holds it")
                    .isFalse();

            winner.rollback();

            BankTransactionOutcome outcome = waiting.get(30, TimeUnit.SECONDS);
            assertThat(outcome)
                    .describedAs("the request is not stranded and not called a duplicate")
                    .isInstanceOf(BankTransactionOutcome.Success.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(adapter.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits())
                .describedAs("the money moved once")
                .isEqualTo(AMOUNT);
        assertThat(reservations(db, actor, key))
                .describedAs("one settled record for the key")
                .isEqualTo(1);
    }

    private static int reservations(Database db, UUID actor, String key) throws Exception {
        try (Connection conn = db.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM processed_operations"
                                + " WHERE operation_scope = ? AND actor_id = ? AND idempotency_key = ? AND status = 'APPLIED'")) {
            ps.setString(1, SCOPE);
            ps.setString(2, actor.toString());
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
