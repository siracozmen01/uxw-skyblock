package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
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
 * A takeover waits for the transaction that locked the authority row, then takes the island.
 *
 * <p>TESTING_STANDARDS names this test, Scenario A: node A locks the canonical authority row inside a
 * bank mutation, the lease expires while that transaction is in flight, node B's takeover blocks on
 * the row lock, A commits under epoch E, and B then takes over with epoch E + 1. There was no such
 * test. A's transaction is driven by hand here, because the point is what the engine does with the
 * lock it holds while the lease runs out.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class AuthorityTakeoverAfterBlockedExpiredTxTest {

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
    @DisplayName("MariaDB: the takeover waits for the in-flight commit, then takes the island at E + 1")
    void mariaDbWaitsThenTakesOver() throws Exception {
        takeoverAfterBlockedExpiredTransaction(mariaDatabase);
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the takeover waits for the in-flight commit, then takes the island at E + 1")
    void postgresWaitsThenTakesOver() throws Exception {
        takeoverAfterBlockedExpiredTransaction(postgresDatabase);
    }

    private static void takeoverAfterBlockedExpiredTransaction(Database db) throws Exception {
        PlayerIslandStorageAdapter islands = new PlayerIslandStorageAdapter(db);
        PlayerIslandBankAdapter banks = new PlayerIslandBankAdapter(db);
        IslandId islandId = AuthorityRenewVsTakeoverRaceTest.newIsland(db, islands);
        banks.createBank(islandId);
        assertThat(islands.acquireAuthority(islandId, AuthorityRenewVsTakeoverRaceTest.NODE_A, 2)
                        .isSuccess())
                .isTrue();

        CompletableFuture<IslandAuthorityOutcome> takeover;
        try (Connection nodeA = db.connection()) {
            nodeA.setAutoCommit(false);
            long epochAtLock;
            try (PreparedStatement lock = nodeA.prepareStatement(
                    "SELECT authoritative_node, authority_epoch, lease_expires_at FROM island_authorities"
                            + " WHERE island_id = ? FOR UPDATE")) {
                lock.setString(1, islandId.value().toString());
                try (ResultSet rs = lock.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    epochAtLock = rs.getLong("authority_epoch");
                }
            }

            // The lease runs out while node A still holds the row.
            Thread.sleep(3000);
            takeover = CompletableFuture.supplyAsync(() ->
                    islands.takeoverAuthority(islandId, AuthorityRenewVsTakeoverRaceTest.NODE_B, epochAtLock, 600));
            Thread.sleep(1000);
            assertThat(takeover)
                    .describedAs("node B waits on the row node A holds")
                    .isNotDone();

            try (PreparedStatement mutate = nodeA.prepareStatement(
                    "UPDATE island_banks SET primary_balance_minor_units = primary_balance_minor_units + 700,"
                            + " version = version + 1 WHERE island_id = ?")) {
                mutate.setString(1, islandId.value().toString());
                assertThat(mutate.executeUpdate()).isEqualTo(1);
            }
            nodeA.commit();
            assertThat(epochAtLock).isEqualTo(1L);
        }

        IslandAuthorityOutcome taken = takeover.get(30, TimeUnit.SECONDS);
        assertThat(taken.isSuccess())
                .describedAs("the lease had run out, so B takes the island")
                .isTrue();
        var record = islands.findAuthority(islandId).orElseThrow();
        assertThat(record.authoritativeNode()).isEqualTo(AuthorityRenewVsTakeoverRaceTest.NODE_B);
        assertThat(record.authorityEpoch()).isEqualTo(2L);
        assertThat(banks.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits())
                .describedAs("node A's mutation under epoch E stands")
                .isEqualTo(700L);

        BankTransactionOutcome stale = banks.executeTransaction(
                islandId,
                UUID.randomUUID(),
                "PRIMARY",
                2,
                100L,
                "Node A, still believing it holds the island",
                AuthorityRenewVsTakeoverRaceTest.NODE_A.value(),
                1L,
                2L,
                UUID.randomUUID(),
                UUID.randomUUID().toString());
        assertThat(stale)
                .describedAs("node A writes nothing more under the epoch it lost")
                .isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
    }
}
