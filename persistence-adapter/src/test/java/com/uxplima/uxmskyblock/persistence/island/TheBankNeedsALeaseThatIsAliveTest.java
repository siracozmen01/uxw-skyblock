package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bank.PlayerIslandBankAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island can bank while this node holds a live lease, and only then.
 *
 * <p>This is the shape of the whole authority mechanism seen from the player's side: a deposit is
 * refused unless the node making it holds an unexpired lease on the island. Two things were wrong
 * with that. The lease was written by the database's clock and compared against this machine's, so
 * on a server whose zone is ahead of the database's every live lease read as already expired and
 * the bank refused from the moment the island was made. And nothing ever renewed a lease, so even
 * where the clocks agreed the island stopped banking one lease after it was created.
 */
class TheBankNeedsALeaseThatIsAliveTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");
    private static final String WORLD = "skyblock";

    private Database database;
    private PlayerIslandStorageAdapter islandAdapter;
    private PlayerIslandBankAdapter bankAdapter;
    private IslandBankService bankService;

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
        bankService = new IslandBankService(bankAdapter, islandAdapter, islandAdapter);

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
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private BankTransactionOutcome deposit(long amount) {
        return bankService.depositToIsland(islandId, owner, amount, NODE);
    }

    @Test
    @DisplayName("A lease taken a moment ago is live, whatever this machine's clock is set to")
    void afreshLeaseIsLive() {
        islandAdapter.acquireAuthority(islandId, NODE, 600);

        assertThat(deposit(500))
                .describedAs("the lease runs for ten minutes and was taken a moment ago")
                .isInstanceOf(BankTransactionOutcome.Success.class);
    }

    @Test
    @DisplayName("A lease that has run out refuses the deposit, which is what the lease is for")
    void anExpiredLeaseRefuses() throws Exception {
        islandAdapter.acquireAuthority(islandId, NODE, 1);
        Thread.sleep(2200);

        assertThat(deposit(500)).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
    }

    @Test
    @DisplayName("A heartbeat puts an island that had stopped banking back to work")
    void theHeartbeatPutsItRight() throws Exception {
        islandAdapter.acquireAuthority(islandId, NODE, 1);
        Thread.sleep(2200);
        assertThat(deposit(500))
                .describedAs("refused while the lease is out")
                .isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        islandAdapter.sweepAuthority(NODE, WORLD, 600);

        assertThat(deposit(500))
                .describedAs("this is the whole point of the heartbeat")
                .isInstanceOf(BankTransactionOutcome.Success.class);
    }

    @Test
    @DisplayName("An island with no authority row at all banks once the heartbeat has given it one")
    void anIslandWithNoRowIsPickedUpToo() {
        assertThat(deposit(500))
                .describedAs("nothing has claimed this island yet")
                .isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        islandAdapter.sweepAuthority(NODE, WORLD, 600);

        assertThat(deposit(500)).isInstanceOf(BankTransactionOutcome.Success.class);
    }
}
