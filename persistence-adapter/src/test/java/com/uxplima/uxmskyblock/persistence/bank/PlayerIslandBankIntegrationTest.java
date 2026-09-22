package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: a settled operation old enough is swept, and one that never settled is not")
    void mariaDbSweepsSettled() throws Exception {
        assertOnlySettledOperationsGo(mariaDatabase, mariaAdapter, "maria");
    }

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: a settled operation old enough is swept, and one that never settled is not")
    void postgresSweepsSettled() throws Exception {
        assertOnlySettledOperationsGo(postgresDatabase, postgresAdapter, "postgres");
    }

    /**
     * Every money movement writes an idempotency record so a retry of it is answered rather than
     * applied twice, and nothing ever deleted one.
     *
     * <p>What must survive matters as much as what goes. A record that never settled is a
     * reservation a crash left behind, and deleting one is exactly what would let the operation it
     * was reserving run a second time.
     */
    private void assertOnlySettledOperationsGo(Database db, PlayerIslandBankAdapter adapter, String scope)
            throws Exception {
        Instant now = Instant.now();
        UUID oldApplied = operation(db, scope, "APPLIED", now.minus(Duration.ofDays(90)));
        UUID oldRejected = operation(db, scope, "REJECTED", now.minus(Duration.ofDays(90)));
        UUID recentApplied = operation(db, scope, "APPLIED", now.minus(Duration.ofMinutes(5)));
        UUID neverSettled = operation(db, scope, "PENDING", null);

        int swept = adapter.purgeSettledOperationsBefore(now.minus(Duration.ofDays(30)));

        assertThat(swept).describedAs("records swept").isEqualTo(2);
        assertThat(operationExists(db, oldApplied))
                .describedAs("an applied record three months old")
                .isFalse();
        assertThat(operationExists(db, oldRejected))
                .describedAs("a rejected record three months old")
                .isFalse();
        assertThat(operationExists(db, recentApplied))
                .describedAs("an applied record five minutes old")
                .isTrue();
        assertThat(operationExists(db, neverSettled))
                .describedAs("a reservation a crash left behind, which must never be swept")
                .isTrue();
    }

    /** Writes one idempotency record in a given state, settled at a moment the test chooses. */
    private UUID operation(
            Database db, String scope, String status, @org.jspecify.annotations.Nullable Instant completedAt)
            throws Exception {
        UUID operationId = UUID.randomUUID();
        try (Connection conn = db.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO processed_operations (
                            operation_id, operation_scope, actor_id, idempotency_key, operation_type,
                            resource_id, status, completed_at
                        ) VALUES (?, ?, ?, ?, 'BANK_TRANSACTION', ?, ?, ?)
                        """)) {
            stmt.setString(1, operationId.toString());
            stmt.setString(2, scope + "-sweep");
            stmt.setString(3, UUID.randomUUID().toString());
            stmt.setString(4, "key-" + operationId);
            stmt.setString(5, "isl-sweep");
            stmt.setString(6, status);
            if (completedAt == null) {
                stmt.setNull(7, java.sql.Types.TIMESTAMP);
            } else {
                stmt.setTimestamp(7, java.sql.Timestamp.from(completedAt));
            }
            stmt.executeUpdate();
        }
        return operationId;
    }

    private boolean operationExists(Database db, UUID operationId) throws Exception {
        try (Connection conn = db.connection();
                PreparedStatement stmt =
                        conn.prepareStatement("SELECT 1 FROM processed_operations WHERE operation_id = ?")) {
            stmt.setString(1, operationId.toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
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
