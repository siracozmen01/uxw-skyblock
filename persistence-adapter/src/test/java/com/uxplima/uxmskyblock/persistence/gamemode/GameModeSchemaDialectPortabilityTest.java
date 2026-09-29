package com.uxplima.uxmskyblock.persistence.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.network.IslandPlacement;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategies;
import com.uxplima.uxmskyblock.core.application.network.PlacementStrategy;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.network.NodeHealth;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.PlayerIslandStorageAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.network.SqlClusterNodesAdapter;
import com.uxplima.uxmskyblock.persistence.oneblock.SqlOneBlockProgressAdapter;
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
 * The game mode schema, and the island authority beside it, does the same on every engine a customer
 * runs: PostgreSQL, MySQL, MariaDB and SQLite.
 *
 * <p>The game mode architecture names this test. Each engine was given its own schema and each was
 * trusted to read it the same way, and the lab server showed what that trust costs: PostgreSQL
 * refused a boolean compared with a number and every island creation failed there. Here every
 * migration runs on each engine from nothing, and the rows the game mode layer writes are written,
 * changed and read back: a profile's instance, the island it is bound to, a OneBlock island's count,
 * the island's authority lease with its epoch, which nodes are alive and where a placement sends a
 * visitor without touching that lease, and what a lifecycle event still owes a player.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class GameModeSchemaDialectPortabilityTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

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
    @DisplayName("SQLite: the game mode schema migrates, writes, changes and reads back")
    void sqlite() throws Exception {
        Database database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        assertTheSchemaIsPortable(database);
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: the game mode schema migrates, writes, changes and reads back")
    void mariaDb() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfMySql
    @DisplayName("MySQL: the game mode schema migrates, writes, changes and reads back")
    void mySql() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(mySqlContainer, Dialect.MYSQL));
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the game mode schema migrates, writes, changes and reads back")
    void postgres() throws Exception {
        assertTheSchemaIsPortable(DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES));
    }

    private static void assertTheSchemaIsPortable(Database database) throws Exception {
        try {
            new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));

            PlayerIslandStorageAdapter islands = new PlayerIslandStorageAdapter(database);
            SqlGameModeHierarchyAdapter hierarchy = new SqlGameModeHierarchyAdapter(database.dataSource());
            SqlOneBlockProgressAdapter oneBlock = new SqlOneBlockProgressAdapter(database.dataSource());

            PlayerUuid owner = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());
            account(database, owner, profile);
            IslandId islandId = IslandId.of(UUID.randomUUID());
            islands.saveIsland(
                    Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now()),
                    IslandLocation.fromCenterAndRadius(islandId, "skyblock", 0, 0, 100));

            // A profile's instance, written, moved to another mode and read back.
            Instant created = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            GameModeInstance instance = GameModeInstance.create(
                    GameModeInstanceId.random(), profile, GameModeType.SKYBLOCK, "classic", created);
            hierarchy.saveGameModeInstance(instance);
            hierarchy.saveGameModeInstance(new GameModeInstance(
                    instance.id(), profile, GameModeType.ONEBLOCK, "oneblock", created, created.plusSeconds(60)));
            assertThat(hierarchy.findInstanceByProfileId(profile)).get().satisfies(read -> {
                assertThat(read.id()).isEqualTo(instance.id());
                assertThat(read.gameModeType()).isEqualTo(GameModeType.ONEBLOCK);
                assertThat(read.rulesetConfig()).isEqualTo("oneblock");
                assertThat(read.updatedAt()).isAfter(read.createdAt());
            });

            // The island the instance is bound to, found from either side.
            hierarchy.savePrimaryGameplayRootRef(PrimaryGameplayRootRef.forIsland(
                    instance.id(), islandId.value().toString(), created));
            assertThat(hierarchy.findRootRefByInstanceId(instance.id()))
                    .get()
                    .extracting(PrimaryGameplayRootRef::rootId)
                    .isEqualTo(islandId.value().toString());
            assertThat(hierarchy.findRootRefByRootId(islandId.value().toString(), "ISLAND"))
                    .get()
                    .extracting(PrimaryGameplayRootRef::gameModeInstanceId)
                    .isEqualTo(instance.id());

            // A OneBlock island's block and count, added to twice.
            oneBlock.start(islandId, 0, 100, 0);
            oneBlock.addBreaks(islandId, 3);
            oneBlock.addBreaks(islandId, 4);
            assertThat(oneBlock.find(islandId)).get().satisfies(read -> {
                assertThat(read.y()).isEqualTo(100);
                assertThat(read.blocksBroken()).isEqualTo(7);
            });
            assertThat(oneBlock.findAll()).extracting(read -> read.islandId()).contains(islandId);

            // The island's authority: taken, renewed under its epoch, refused under a stale one.
            IslandAuthorityOutcome taken = islands.acquireAuthority(islandId, NODE, 60);
            assertThat(taken.isSuccess()).isTrue();
            long epoch = islands.findAuthority(islandId).orElseThrow().authorityEpoch();
            assertThat(islands.renewAuthority(islandId, NODE, epoch, 60).isSuccess())
                    .isTrue();
            assertThat(islands.renewAuthority(islandId, NODE, epoch + 5, 60).isRejected())
                    .isTrue();
            assertThat(islands.findAuthority(islandId).orElseThrow().leaseExpiresAt())
                    .isAfter(Instant.now());

            // Which nodes are alive: published twice, one in another world, one heard from long ago.
            SqlClusterNodesAdapter nodes = new SqlClusterNodesAdapter(database);
            nodes.publish(new NodeHealth(NODE, true, 3, 100, 12.5), "skyblock");
            nodes.publish(new NodeHealth(NODE, true, 4, 100, 11.0), "skyblock");
            nodes.publish(new NodeHealth(ServerNodeId.of("node-elsewhere"), true, 0, 100, 5.0), "elsewhere");
            try (Connection conn = database.connection();
                    PreparedStatement stmt = conn.prepareStatement("INSERT INTO cluster_nodes (node_id, world_name, "
                            + "hosted, capacity, average_mspt, last_seen) "
                            + "VALUES ('node-stopped', 'skyblock', 0, 100, 1.0, '2000-01-01 00:00:00')")) {
                stmt.executeUpdate();
            }
            assertThat(nodes.serving("skyblock", Duration.ofMinutes(4)))
                    .containsExactly(new NodeHealth(NODE, true, 4, 100, 11.0));

            // A placement reads those rows and the island's lease stays exactly as it was.
            IslandAuthorityRecord before = islands.findAuthority(islandId).orElseThrow();
            String leaseBefore = lease(database, islandId);
            IslandPlacement placement = new IslandPlacement(
                    PlacementStrategies.shipped(PlacementStrategy.DEFAULT_MSPT_CEILING),
                    PlacementStrategies.LEAST_LOADED,
                    nodes,
                    id -> islands.findLocationByIslandId(id).map(IslandLocation::worldName),
                    id -> hierarchy.findRootRefByRootId(id.value().toString(), "ISLAND"),
                    Duration.ofMinutes(4));
            assertThat(placement.recommend(islandId)).contains(NODE);
            assertThat(islands.findAuthority(islandId)).get().satisfies(after -> {
                assertThat(after.authoritativeNode()).isEqualTo(before.authoritativeNode());
                assertThat(after.authorityEpoch()).isEqualTo(before.authorityEpoch());
            });
            assertThat(lease(database, islandId)).isEqualTo(leaseBefore);

            // What a lifecycle event owes a player who was away: owed once however often, paid in part.
            com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter owedEffects =
                    new com.uxplima.uxmskyblock.persistence.lifecycle.SqlLifecycleOwedEffectsAdapter(
                            database.dataSource());
            owedEffects.owe(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY, LifecycleEffect.SEND_TO_SPAWN));
            owedEffects.owe(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY));
            assertThat(owedEffects.owed(owner))
                    .containsExactlyInAnyOrder(LifecycleEffect.CLEAR_INVENTORY, LifecycleEffect.SEND_TO_SPAWN);
            owedEffects.settle(owner, java.util.Set.of(LifecycleEffect.CLEAR_INVENTORY));
            assertThat(owedEffects.owed(owner)).containsExactly(LifecycleEffect.SEND_TO_SPAWN);
            assertThat(owedEffects.owed(PlayerUuid.of(UUID.randomUUID()))).isEmpty();

            // Deleting the island takes its OneBlock row with it.
            islands.deleteIsland(islandId);
            assertThat(oneBlock.find(islandId)).isEmpty();
        } finally {
            if (!database.isClosed()) {
                database.close();
            }
        }
    }

    /** The island's lease row as the database holds it, read without the adapter. */
    private static String lease(Database database, IslandId islandId) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("SELECT authoritative_node, authority_epoch, "
                        + "lease_expires_at, last_heartbeat_at FROM island_authorities WHERE island_id = ?")) {
            stmt.setString(1, islandId.value().toString());
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1) + " " + rs.getLong(2) + " " + rs.getString(3) + " " + rs.getString(4);
            }
        }
    }

    private static void account(Database database, PlayerUuid owner, ProfileId profile) throws Exception {
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
    }
}
