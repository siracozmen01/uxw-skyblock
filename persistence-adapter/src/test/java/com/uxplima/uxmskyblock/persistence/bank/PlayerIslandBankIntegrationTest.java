package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
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

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerIslandBankIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandBankAdapter mariaAdapter;
    private static PlayerIslandBankAdapter postgresAdapter;

    private static final String NODE_ALPHA = "node-alpha";
    private static final long EPOCH = 1L;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerIslandBankAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerIslandBankAdapter(postgresDatabase);
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
    @Order(1)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: createBank, deposit, withdraw, and transaction history")
    void mariaDbBankLifecycle() throws Exception {
        testBankLifecycle(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: createBank, deposit, withdraw, and transaction history")
    void postgresBankLifecycle() throws Exception {
        testBankLifecycle(postgresDatabase, postgresAdapter);
    }

    private void testBankLifecycle(Database db, PlayerIslandBankAdapter adapter) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        UUID actorUuid = UUID.randomUUID();

        // Seed island & authority prerequisites
        try (Connection conn = db.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, islandId.value().toString());
                ps.setString(2, actorUuid.toString());
                ps.setString(3, UUID.randomUUID().toString());
                ps.executeUpdate();
            }

            String plus60 = (db.dialect() == Dialect.POSTGRES)
                    ? "CURRENT_TIMESTAMP + INTERVAL '60 seconds'"
                    : "CURRENT_TIMESTAMP + INTERVAL 60 SECOND";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                        "INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at) "
                                + "VALUES ('" + islandId.value() + "', '" + NODE_ALPHA + "', " + EPOCH + ", " + plus60
                                + ", CURRENT_TIMESTAMP)");
            }
        }

        // 1. Create bank
        IslandBank bank = adapter.createBank(islandId);
        assertThat(bank.islandId()).isEqualTo(islandId);
        assertThat(bank.primaryBalanceMinorUnits()).isEqualTo(0L);
        assertThat(bank.version()).isEqualTo(1L);

        // 2. Deposit
        UUID depOp = UUID.randomUUID();
        BankTransactionOutcome depOutcome = adapter.executeTransaction(
                islandId, actorUuid, "PRIMARY", 2, 10000L, "Deposit $100.00", NODE_ALPHA, EPOCH, 1L, depOp, "dep-k");
        assertThat(depOutcome).isInstanceOf(BankTransactionOutcome.Success.class);
        BankTransactionOutcome.Success depSuccess = (BankTransactionOutcome.Success) depOutcome;
        assertThat(depSuccess.updatedBank().primaryBalanceMinorUnits()).isEqualTo(10000L);
        assertThat(depSuccess.updatedBank().version()).isEqualTo(2L);

        // 3. Withdraw
        UUID withOp = UUID.randomUUID();
        BankTransactionOutcome withOutcome = adapter.executeTransaction(
                islandId, actorUuid, "PRIMARY", 2, -3500L, "Withdraw $35.00", NODE_ALPHA, EPOCH, 2L, withOp, "with-k");
        assertThat(withOutcome).isInstanceOf(BankTransactionOutcome.Success.class);
        BankTransactionOutcome.Success withSuccess = (BankTransactionOutcome.Success) withOutcome;
        assertThat(withSuccess.updatedBank().primaryBalanceMinorUnits()).isEqualTo(6500L);
        assertThat(withSuccess.updatedBank().version()).isEqualTo(3L);

        // 4. Overdraft attempt
        BankTransactionOutcome overdraft = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                -8000L,
                "Overdraft",
                NODE_ALPHA,
                EPOCH,
                3L,
                UUID.randomUUID(),
                "over-k");
        assertThat(overdraft).isInstanceOf(BankTransactionOutcome.InsufficientFunds.class);

        // 5. Verify transaction history
        List<BankTransaction> history = adapter.getTransactionHistory(islandId, 10);
        assertThat(history).hasSize(2);
        assertThat(history)
                .extracting(BankTransaction::deltaAmountMinorUnits)
                .containsExactlyInAnyOrder(-3500L, 10000L);
    }
}
