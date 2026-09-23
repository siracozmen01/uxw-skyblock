package com.uxplima.uxmskyblock.persistence.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.upgrade.PaidTierMove;
import com.uxplima.uxmskyblock.core.application.upgrade.TierPurchase;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An upgrade's charge and the tier it buys happen in one transaction, or neither happens.
 *
 * <p>A purchase charged the bank and then moved the tier in a second step. A server that stopped
 * between the two kept the money and gave no tier, and when the second step lost a race the money
 * went back through a refund that could itself be refused. The race half runs on MariaDB and
 * PostgreSQL in {@link APaidTierMovesWithItsChargeIntegrationTest}, where a first-tier insert that
 * collides must not end the transaction it shares with the charge.
 */
class APaidTierMovesWithItsChargeTest {

    static final UpgradeId UPGRADE = UpgradeId.of("ore-generator");
    static final long COST = 400L;
    static final long FUNDS = 1_000L;
    private static final String NODE = "node-alpha";

    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A purchase takes the cost and moves the tier together")
    void aPurchaseChargesAndMoves() throws Exception {
        aPurchaseChargesAndMovesTogether(database);
    }

    @Test
    @DisplayName("A purchase another one beat to the tier charges nothing, and the next one still goes through")
    void aRaceChargesNothing() throws Exception {
        aRaceChargesNothing(database);
    }

    @Test
    @DisplayName("A tier that cannot be written takes the charge back with it")
    void aFailedTierWriteKeepsTheMoney() throws Exception {
        Seeded island = seed(database);
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE island_upgrades RENAME TO island_upgrades_gone");
        }

        assertThatThrownBy(() -> new PlayerIslandUpgradeAdapter(database).chargeAndMoveTier(island.purchase(0, 1)))
                .describedAs("the server stopping between the charge and the tier, as near as a test gets");

        assertThat(balance(database, island.id()))
                .describedAs("the money stays")
                .isEqualTo(FUNDS);
        assertThat(charges(database, island.id())).isZero();
    }

    static void aPurchaseChargesAndMovesTogether(Database database) throws Exception {
        Seeded island = seed(database);
        PlayerIslandUpgradeAdapter upgrades = new PlayerIslandUpgradeAdapter(database);

        assertThat(upgrades.chargeAndMoveTier(island.purchase(0, 1))).get().isInstanceOf(PaidTierMove.Moved.class);

        assertThat(upgrades.getUpgradeTier(island.id(), UPGRADE)).isEqualTo(1);
        assertThat(balance(database, island.id())).isEqualTo(FUNDS - COST);
        assertThat(charges(database, island.id())).isEqualTo(1);
    }

    static void aRaceChargesNothing(Database database) throws Exception {
        Seeded island = seed(database);
        PlayerIslandUpgradeAdapter upgrades = new PlayerIslandUpgradeAdapter(database);
        // The other purchase already wrote the first tier.
        upgrades.setUpgradeTier(island.id(), UPGRADE, 1);

        assertThat(upgrades.chargeAndMoveTier(island.purchase(0, 1))).get().isInstanceOf(PaidTierMove.Raced.class);
        assertThat(balance(database, island.id()))
                .describedAs("nothing was charged")
                .isEqualTo(FUNDS);
        assertThat(charges(database, island.id())).isZero();

        assertThat(upgrades.chargeAndMoveTier(island.purchase(1, 2)))
                .describedAs("the lost race left nothing half done behind it")
                .get()
                .isInstanceOf(PaidTierMove.Moved.class);
        assertThat(upgrades.getUpgradeTier(island.id(), UPGRADE)).isEqualTo(2);
        assertThat(balance(database, island.id())).isEqualTo(FUNDS - COST);
    }

    /** An island with an owner, a lease on {@link #NODE} and {@link #FUNDS} in its bank. */
    static Seeded seed(Database database) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        UUID owner = UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, owner.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profile.toString());
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_account_uuid, owner_profile_id, lifecycle, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                ps.setString(1, islandId.value().toString());
                ps.setString(2, owner.toString());
                ps.setString(3, profile.toString());
                ps.executeUpdate();
            }
        }
        IslandAuthorityOutcome lease =
                new PlayerIslandStorageAdapter(database).acquireAuthority(islandId, ServerNodeId.of(NODE), 600);
        assertThat(lease).isInstanceOf(IslandAuthorityOutcome.Success.class);
        long epoch = ((IslandAuthorityOutcome.Success) lease).epoch();

        PlayerIslandBankAdapter bank = new PlayerIslandBankAdapter(database);
        bank.createBank(islandId);
        assertThat(bank.executeTransaction(
                        islandId,
                        owner,
                        "PRIMARY",
                        2,
                        FUNDS,
                        "Seed",
                        NODE,
                        epoch,
                        bank.findBankByIslandId(islandId).orElseThrow().version(),
                        UUID.randomUUID(),
                        "seed-" + islandId.value()))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        return new Seeded(islandId, owner, epoch, bank);
    }

    record Seeded(IslandId id, UUID owner, long epoch, PlayerIslandBankAdapter bank) {
        TierPurchase purchase(int from, int to) {
            UUID operation = UUID.randomUUID();
            return new TierPurchase(
                    id,
                    UPGRADE,
                    from,
                    to,
                    owner,
                    "PRIMARY",
                    COST,
                    "Upgrade " + UPGRADE.key() + " to tier " + to,
                    NODE,
                    epoch,
                    bank.findBankByIslandId(id).orElseThrow().version(),
                    operation,
                    "upg-" + operation);
        }
    }

    static long balance(Database database, IslandId islandId) {
        return new PlayerIslandBankAdapter(database)
                .findBankByIslandId(islandId)
                .orElseThrow()
                .primaryBalanceMinorUnits();
    }

    static int charges(Database database, IslandId islandId) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM bank_transactions WHERE island_id = ? AND delta_amount_minor_units < 0")) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
