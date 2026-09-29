package com.uxplima.uxmskyblock.persistence.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMySql;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A root that is not an island, a game mode instance or a root a mode owns, gets fenced authority
 * through the island's algorithm, with no branch for any mode.
 *
 * <p>The game mode architecture asks whether a non-island mutable root can obtain fenced authority
 * without core mode-specific branches. Here a Boxed instance and a TradeWinds vessel, named by its
 * mode, are each taken by one node and refused to another, renewed only under their live epoch, taken
 * over only once the lease has run out, and the node that lost one is fenced by its old epoch. The
 * instance's lease goes with the instance. Every engine a customer runs does the same.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class NonIslandRootAuthorityTest {

    private static final ServerNodeId ALPHA = ServerNodeId.of("node-alpha");
    private static final ServerNodeId BETA = ServerNodeId.of("node-beta");

    private static MariaDBContainer<?> mariaDbContainer;
    private static MySQLContainer<?> mySqlContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void startEngines() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        mySqlContainer = DatabaseTestFixture.startMySqlIfEnabled();
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
    }

    @AfterAll
    static void stopEngines() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (mySqlContainer != null) {
            mySqlContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @DisplayName("SQLite: an instance and a mode-owned root are leased and fenced as an island is")
    void sqlite() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        assertLeasedAndFenced(database);
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: an instance and a mode-owned root are leased and fenced as an island is")
    void mariaDb() throws Exception {
        assertLeasedAndFenced(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: an instance and a mode-owned root are leased and fenced as an island is")
    void mySql() throws Exception {
        assertLeasedAndFenced(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: an instance and a mode-owned root are leased and fenced as an island is")
    void postgres() throws Exception {
        assertLeasedAndFenced(DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }

    private static void assertLeasedAndFenced(Database database) throws Exception {
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            account(database, profile);
            GameModeInstanceId boxed = GameModeInstanceId.random();
            new SqlGameModeHierarchyAdapter(database.dataSource())
                    .saveGameModeInstance(
                            GameModeInstance.create(boxed, profile, GameModeType.SKYBLOCK, "boxed", Instant.now()));
            RootAuthorityAdapter leases = new RootAuthorityAdapter(database);

            for (AuthorityRoot root : new AuthorityRoot[] {
                AuthorityRoot.instance(boxed), AuthorityRoot.modeOwned("tradewinds:vessel", "vessel-7")
            }) {
                // Taken once; the second node is refused.
                assertThat(leases.acquire(root, ALPHA, 1).isSuccess())
                        .as("%s taken", root)
                        .isTrue();
                assertThat(leases.acquire(root, BETA, 60).isRejected()).isTrue();
                assertThat(leases.find(root)).get().satisfies(read -> {
                    assertThat(read.authoritativeNode()).isEqualTo(ALPHA);
                    assertThat(read.authorityEpoch()).isEqualTo(1);
                });
                // Renewed only by the holder, under its epoch; not taken over while it runs.
                assertThat(leases.renew(root, ALPHA, 1, 1).isSuccess()).isTrue();
                assertThat(leases.renew(root, BETA, 1, 60).isRejected()).isTrue();
                assertThat(leases.renew(root, ALPHA, 2, 60).isRejected()).isTrue();
                assertThat(leases.takeover(root, BETA, 1, 60).isRejected())
                        .as("a running lease is never taken over")
                        .isTrue();
            }
            // Both leases run out.
            Thread.sleep(2_500);
            for (AuthorityRoot root : new AuthorityRoot[] {
                AuthorityRoot.instance(boxed), AuthorityRoot.modeOwned("tradewinds:vessel", "vessel-7")
            }) {
                assertThat(leases.find(root).orElseThrow().isLive(Instant.now()))
                        .isFalse();
                assertThat(leases.renew(root, ALPHA, 1, 60).isRejected())
                        .as("a lease that ran out is not renewed, even by its holder")
                        .isTrue();
                assertThat(leases.takeover(root, BETA, 1, 60)).isEqualTo(IslandAuthorityOutcome.success(2));
                assertThat(leases.takeover(root, ALPHA, 1, 60).isRejected())
                        .as("the epoch moved on, and a second takeover under the old one is refused")
                        .isTrue();
                assertThat(leases.renew(root, ALPHA, 1, 60).isRejected())
                        .as("the node that lost the root is fenced by its old epoch")
                        .isTrue();
                assertThat(leases.find(root)).get().satisfies(read -> {
                    assertThat(read.authoritativeNode()).isEqualTo(BETA);
                    assertThat(read.authorityEpoch()).isEqualTo(2);
                    assertThat(read.isLive(Instant.now())).isTrue();
                });
            }
            // Another provider's root under the same key is its own.
            assertThat(leases.acquire(AuthorityRoot.modeOwned("parkour:course", "vessel-7"), ALPHA, 60)
                            .isSuccess())
                    .isTrue();
            // The instance's lease goes with the instance.
            try (Connection conn = database.connection();
                    PreparedStatement stmt = conn.prepareStatement("DELETE FROM game_mode_instances WHERE id = ?")) {
                stmt.setString(1, boxed.value().toString());
                stmt.executeUpdate();
            }
            assertThat(leases.find(AuthorityRoot.instance(boxed))).isEmpty();
        } finally {
            if (!database.isClosed()) {
                database.close();
            }
        }
    }

    private static void account(Database database, ProfileId profile) throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, player.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, player.toString());
                stmt.executeUpdate();
            }
        }
    }
}
