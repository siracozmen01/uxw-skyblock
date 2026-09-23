package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyCycleResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.bank.SqlIslandBankruptcyStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island pays its upkeep once for each period, whoever runs the cycle and however often.
 *
 * <p>The cycle drew a fresh key for every charge and ran once an interval from whenever the server
 * started. A restart charged every island again a minute later, a cluster charged every island once
 * per node, and an island whose bank moved under the charge was put into grace while it held the
 * money. A service made afresh stands for a restart here: it knows only what the database knows.
 */
class UpkeepIsChargedOncePerPeriodTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");
    private static final ServerNodeId OTHER_NODE = ServerNodeId.of("node-beta");
    private static final String WORLD = "skyblock";
    private static final IslandUpkeepPolicy POLICY =
            new IslandUpkeepPolicy(true, Duration.ofDays(1), 50_000L, 10_000L, Duration.ofDays(3), true);
    private static final long FEE = POLICY.calculateUpkeepFee(1);

    private Database database;
    private PlayerIslandStorageAdapter islandAdapter;
    private PlayerIslandBankAdapter bankAdapter;
    private SqlIslandBankruptcyStorageAdapter bankruptcies;
    private IslandId islandId;
    private PlayerUuid owner;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        islandAdapter = new PlayerIslandStorageAdapter(database);
        bankAdapter = new PlayerIslandBankAdapter(database);
        bankruptcies = new SqlIslandBankruptcyStorageAdapter(database);

        owner = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, owner.value().toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, owner.value().toString());
                stmt.executeUpdate();
            }
        }
        islandId = IslandId.of(UUID.randomUUID());
        islandAdapter.saveIsland(
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now()),
                IslandLocation.fromCenterAndRadius(islandId, WORLD, 0, 0, 100));
        bankAdapter.createBank(islandId);
        islandAdapter.acquireAuthority(islandId, NODE, 600);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A restart inside a period charges nothing, and the next period is charged once")
    void aRestartChargesNothing() {
        deposit(3 * FEE);
        Instant now = Instant.now();

        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .isInstanceOf(BankruptcyCycleResult.Paid.class);
        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .describedAs("the same period after a restart")
                .isInstanceOf(BankruptcyCycleResult.AlreadyCharged.class);
        assertThat(balance()).isEqualTo(2 * FEE);

        IslandBankruptcyService service = freshService();
        Instant tomorrow = now.plus(POLICY.interval());
        assertThat(service.processUpkeepCycle(islandId, 1, tomorrow, NODE))
                .isInstanceOf(BankruptcyCycleResult.Paid.class);
        assertThat(service.processUpkeepCycle(islandId, 1, tomorrow, NODE))
                .isInstanceOf(BankruptcyCycleResult.AlreadyCharged.class);
        assertThat(balance()).isEqualTo(FEE);
    }

    @Test
    @DisplayName("A charge that went through is neither taken again nor owed when its record is lost")
    void aPaidPeriodIsKnownByItsKey() {
        deposit(FEE);
        Instant now = Instant.now();
        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .isInstanceOf(BankruptcyCycleResult.Paid.class);

        // The server stopped between the charge and writing down the period it paid.
        bankruptcies.deleteByIslandId(islandId);

        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .describedAs("an empty bank would otherwise owe the period a second time")
                .isInstanceOf(BankruptcyCycleResult.AlreadyCharged.class);
        IslandBankruptcyRecord record = bankruptcies.findByIslandId(islandId).orElseThrow();
        assertThat(record.status()).isEqualTo(BankruptcyStatus.SOLVENT);
        assertThat(record.debtMinorUnits()).isZero();
    }

    @Test
    @DisplayName("An island that cannot pay owes the period once, however often the cycle runs")
    void anUnpaidPeriodIsOwedOnce() {
        Instant now = Instant.now();

        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .isInstanceOf(BankruptcyCycleResult.GraceEntered.class);
        assertThat(freshService().processUpkeepCycle(islandId, 1, now, NODE))
                .isInstanceOf(BankruptcyCycleResult.AlreadyCharged.class);

        assertThat(bankruptcies.findByIslandId(islandId).orElseThrow().debtMinorUnits())
                .isEqualTo(FEE);
    }

    @Test
    @DisplayName("A node that does not hold the island leaves its bank and its debt alone")
    void anotherNodeLeavesItAlone() {
        deposit(3 * FEE);

        assertThat(freshService().processUpkeepCycle(islandId, 1, Instant.now(), OTHER_NODE))
                .isInstanceOf(BankruptcyCycleResult.HeldElsewhere.class);

        assertThat(balance()).isEqualTo(3 * FEE);
        assertThat(bankruptcies.findByIslandId(islandId)).isEmpty();
    }

    private IslandBankruptcyService freshService() {
        return new IslandBankruptcyService(bankruptcies, bankAdapter, islandAdapter, () -> POLICY);
    }

    private void deposit(long amount) {
        assertThat(new IslandBankService(bankAdapter, islandAdapter, islandAdapter)
                        .depositToIsland(islandId, owner, amount, NODE))
                .isInstanceOf(BankTransactionOutcome.Success.class);
    }

    private long balance() {
        return bankAdapter.findBankByIslandId(islandId).orElseThrow().primaryBalanceMinorUnits();
    }
}
