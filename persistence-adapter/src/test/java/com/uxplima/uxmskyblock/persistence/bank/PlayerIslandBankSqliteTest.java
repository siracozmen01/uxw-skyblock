package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandBankSqliteTest {

    private Database database;
    private PlayerIslandBankAdapter adapter;

    private final IslandId islandId = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final UUID actorUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final String currentNode = "node-alpha";
    private final long currentEpoch = 1L;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");

            // Seed island prerequisites
            stmt.execute(
                    "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, updated_at) "
                            + "VALUES ('" + islandId.value() + "', '" + actorUuid + "', '" + UUID.randomUUID()
                            + "', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // Seed valid unexpired authority lease
            stmt.execute(
                    "INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at) "
                            + "VALUES ('" + islandId.value() + "', '" + currentNode
                            + "', 1, DATETIME('now', '+60 seconds'), CURRENT_TIMESTAMP)");
        }

        adapter = new PlayerIslandBankAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("createBank initializes zero-balance account, and findBankByIslandId retrieves it")
    void createAndFindBank() {
        Optional<IslandBank> before = adapter.findBankByIslandId(islandId);
        assertThat(before).isEmpty();

        IslandBank created = adapter.createBank(islandId);
        assertThat(created.islandId()).isEqualTo(islandId);
        assertThat(created.primaryBalanceMinorUnits()).isEqualTo(0L);
        assertThat(created.crystalsBalance()).isEqualTo(0L);
        assertThat(created.expBalance()).isEqualTo(0L);
        assertThat(created.version()).isEqualTo(1L);

        Optional<IslandBank> after = adapter.findBankByIslandId(islandId);
        assertThat(after).isPresent();
        assertThat(after.get().version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("deposit on PRIMARY currency succeeds, increments version and logs transaction")
    void depositPrimaryCurrency() {
        adapter.createBank(islandId);

        UUID opId = UUID.randomUUID();
        BankTransactionOutcome outcome = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                5000L, // 50.00
                "Initial deposit",
                currentNode,
                currentEpoch,
                1L,
                opId,
                "idem-key-1");

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        BankTransactionOutcome.Success success = (BankTransactionOutcome.Success) outcome;
        assertThat(success.updatedBank().primaryBalanceMinorUnits()).isEqualTo(5000L);
        assertThat(success.updatedBank().version()).isEqualTo(2L);
        assertThat(success.transaction().deltaAmountMinorUnits()).isEqualTo(5000L);
        assertThat(success.transaction().resultingBalanceMinorUnits()).isEqualTo(5000L);

        List<BankTransaction> history = adapter.getTransactionHistory(islandId, 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).operationId()).isEqualTo(opId);
    }

    @Test
    @DisplayName("withdraw with sufficient funds succeeds, while insufficient funds is rejected")
    void withdrawFunds() {
        adapter.createBank(islandId);

        // First deposit 10000 minor units
        adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                10000L,
                "Seed money",
                currentNode,
                currentEpoch,
                1L,
                UUID.randomUUID(),
                "k1");

        // Withdraw 4000 minor units
        BankTransactionOutcome withdrawSuccess = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                -4000L,
                "Store purchase",
                currentNode,
                currentEpoch,
                2L,
                UUID.randomUUID(),
                "k2");

        assertThat(withdrawSuccess).isInstanceOf(BankTransactionOutcome.Success.class);
        BankTransactionOutcome.Success s = (BankTransactionOutcome.Success) withdrawSuccess;
        assertThat(s.updatedBank().primaryBalanceMinorUnits()).isEqualTo(6000L);
        assertThat(s.updatedBank().version()).isEqualTo(3L);

        // Attempt overdraft of 7000 (only 6000 available)
        BankTransactionOutcome overdraft = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                -7000L,
                "Overdraft attempt",
                currentNode,
                currentEpoch,
                3L,
                UUID.randomUUID(),
                "k3");

        assertThat(overdraft).isInstanceOf(BankTransactionOutcome.InsufficientFunds.class);
        BankTransactionOutcome.InsufficientFunds rej = (BankTransactionOutcome.InsufficientFunds) overdraft;
        assertThat(rej.currentBalance()).isEqualTo(6000L);
        assertThat(rej.requestedDelta()).isEqualTo(-7000L);

        // Verify balance remained 6000 and version remained 3
        IslandBank bank = adapter.findBankByIslandId(islandId).orElseThrow();
        assertThat(bank.primaryBalanceMinorUnits()).isEqualTo(6000L);
        assertThat(bank.version()).isEqualTo(3L);
    }

    @Test
    @DisplayName("stale OCC version is rejected without mutating balance")
    void staleVersionRejected() {
        adapter.createBank(islandId);

        BankTransactionOutcome outcome = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                1000L,
                "Wrong version",
                currentNode,
                currentEpoch,
                999L, // wrong expected version
                UUID.randomUUID(),
                "k-stale");

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.StaleVersion.class);
        BankTransactionOutcome.StaleVersion stale = (BankTransactionOutcome.StaleVersion) outcome;
        assertThat(stale.expectedVersion()).isEqualTo(999L);
        assertThat(stale.actualVersion()).isEqualTo(1L);

        IslandBank bank = adapter.findBankByIslandId(islandId).orElseThrow();
        assertThat(bank.version()).isEqualTo(1L);
        assertThat(bank.primaryBalanceMinorUnits()).isEqualTo(0L);
    }

    @Test
    @DisplayName("authority validation: node mismatch, stale epoch, and expired lease are rejected")
    void authorityValidation() throws Exception {
        adapter.createBank(islandId);

        // 1. Node mismatch
        BankTransactionOutcome nodeMismatch = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                1000L,
                "Test",
                "other-node",
                currentEpoch,
                1L,
                UUID.randomUUID(),
                "k-auth1");
        assertThat(nodeMismatch).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        // 2. Stale epoch
        BankTransactionOutcome staleEpoch = adapter.executeTransaction(
                islandId, actorUuid, "PRIMARY", 2, 1000L, "Test", currentNode, 999L, 1L, UUID.randomUUID(), "k-auth2");
        assertThat(staleEpoch).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        // 3. Expire lease in DB
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE island_authorities SET lease_expires_at = DATETIME('now', '-10 seconds') WHERE island_id = ?")) {
            ps.setString(1, islandId.value().toString());
            ps.executeUpdate();
        }

        BankTransactionOutcome expired = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                1000L,
                "Test",
                currentNode,
                currentEpoch,
                1L,
                UUID.randomUUID(),
                "k-auth3");
        assertThat(expired).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
    }

    @Test
    @DisplayName("duplicate operation returns DuplicateOperation outcome")
    void duplicateOperation() {
        adapter.createBank(islandId);
        UUID opId = UUID.randomUUID();

        BankTransactionOutcome first = adapter.executeTransaction(
                islandId, actorUuid, "PRIMARY", 2, 1000L, "Deposit 1", currentNode, currentEpoch, 1L, opId, "key-dup");
        assertThat(first).isInstanceOf(BankTransactionOutcome.Success.class);

        // Re-execute with identical opId
        BankTransactionOutcome second = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                1000L,
                "Deposit 1",
                currentNode,
                currentEpoch,
                2L,
                opId,
                "key-different");
        assertThat(second).isInstanceOf(BankTransactionOutcome.DuplicateOperation.class);

        // Re-execute with identical idempotency key
        BankTransactionOutcome third = adapter.executeTransaction(
                islandId,
                actorUuid,
                "PRIMARY",
                2,
                1000L,
                "Deposit 1",
                currentNode,
                currentEpoch,
                2L,
                UUID.randomUUID(),
                "key-dup");
        assertThat(third).isInstanceOf(BankTransactionOutcome.DuplicateOperation.class);
    }

    @Test
    @DisplayName("multi-currency operations: CRYSTALS and EXP mutate their respective balances")
    void multiCurrencySupport() {
        adapter.createBank(islandId);

        // Deposit Crystals
        BankTransactionOutcome crystalDep = adapter.executeTransaction(
                islandId,
                actorUuid,
                "CRYSTALS",
                0,
                500L,
                "Crystal reward",
                currentNode,
                currentEpoch,
                1L,
                UUID.randomUUID(),
                "c1");
        assertThat(crystalDep).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(((BankTransactionOutcome.Success) crystalDep).updatedBank().crystalsBalance())
                .isEqualTo(500L);

        // Deposit EXP
        BankTransactionOutcome expDep = adapter.executeTransaction(
                islandId,
                actorUuid,
                "EXP",
                0,
                2500L,
                "EXP grant",
                currentNode,
                currentEpoch,
                2L,
                UUID.randomUUID(),
                "e1");
        assertThat(expDep).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(((BankTransactionOutcome.Success) expDep).updatedBank().expBalance())
                .isEqualTo(2500L);

        // Verify history shows all transactions
        List<BankTransaction> history = adapter.getTransactionHistory(islandId, 10);
        assertThat(history).hasSize(2);
        assertThat(history).extracting(BankTransaction::currencyId).containsExactlyInAnyOrder("EXP", "CRYSTALS");
    }
}
