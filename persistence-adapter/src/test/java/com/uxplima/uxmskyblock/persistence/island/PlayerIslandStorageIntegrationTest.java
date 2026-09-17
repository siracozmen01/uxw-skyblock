package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
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
class PlayerIslandStorageIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandStorageAdapter mariaAdapter;
    private static PlayerIslandStorageAdapter postgresAdapter;

    private static final ServerNodeId NODE_ALPHA = new ServerNodeId("node-alpha");
    private static final ServerNodeId NODE_BETA = new ServerNodeId("node-beta");
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.newMariaDbContainer();
        mariaDbContainer.start();
        mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        mariaAdapter = new PlayerIslandStorageAdapter(mariaDatabase);

        postgresContainer = DatabaseTestFixture.newPostgresContainer();
        postgresContainer.start();
        postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
        postgresAdapter = new PlayerIslandStorageAdapter(postgresDatabase);
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
    @DisplayName("MariaDB: saveIsland, findIslandById, and member lifecycle")
    void mariaDbIslandLifecycle() {
        testIslandLifecycle(mariaAdapter);
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL: saveIsland, findIslandById, and member lifecycle")
    void postgresIslandLifecycle() {
        testIslandLifecycle(postgresAdapter);
    }

    @Test
    @Order(3)
    @DisplayName("MariaDB: Authority leasing lifecycle (acquire, renew, takeover)")
    void mariaDbAuthorityLifecycle() throws Exception {
        testAuthorityLifecycle(mariaAdapter, mariaDatabase);
    }

    @Test
    @Order(4)
    @DisplayName("PostgreSQL: Authority leasing lifecycle (acquire, renew, takeover)")
    void postgresAuthorityLifecycle() throws Exception {
        testAuthorityLifecycle(postgresAdapter, postgresDatabase);
    }

    private void testIslandLifecycle(PlayerIslandStorageAdapter adapter) {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());

        Island island = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(500, 500, 100), ownerUuid, ownerProfileId, NOW);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 500, 500, 100);

        adapter.saveIsland(island, location);

        // Verify retrieval
        Optional<Island> loaded = adapter.findIslandById(islandId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().ownerPlayerUuid()).isEqualTo(ownerUuid);
        assertThat(loaded.get().bounds().centerX()).isEqualTo(500);
        assertThat(loaded.get().bounds().radius()).isEqualTo(100);

        Optional<IslandLocation> loadedLoc = adapter.findLocationByIslandId(islandId);
        assertThat(loadedLoc).isPresent();
        assertThat(loadedLoc.get().worldName()).isEqualTo("skyblock_world");

        assertThat(adapter.findIslandIdByProfileId(ownerProfileId)).contains(islandId);

        // Add member
        PlayerUuid memberUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId memberProfile = ProfileId.of(UUID.randomUUID());
        IslandMember member = new IslandMember(memberUuid, memberProfile, IslandRole.MODERATOR, NOW);

        Island withMember = island.addMember(member);
        adapter.saveIsland(withMember, location);

        Island loadedMember = adapter.findIslandById(islandId).orElseThrow();
        assertThat(loadedMember.isMember(memberProfile)).isTrue();
        assertThat(loadedMember.roleOf(memberProfile)).isEqualTo(IslandRole.MODERATOR);

        // Delete island
        adapter.deleteIsland(islandId);
        assertThat(adapter.findIslandById(islandId)).isEmpty();
        assertThat(adapter.findLocationByIslandId(islandId)).isEmpty();
        assertThat(adapter.findIslandIdByProfileId(ownerProfileId)).isEmpty();
    }

    private void testAuthorityLifecycle(PlayerIslandStorageAdapter adapter, Database db) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());

        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), ownerUuid, ownerProfileId, NOW);
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 0, 0, 50);
        adapter.saveIsland(island, location);

        // 1. Initial acquire
        IslandAuthorityOutcome outcome = adapter.acquireAuthority(islandId, NODE_ALPHA, 15);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) outcome).epoch()).isEqualTo(1L);

        // 2. Duplicate acquire rejected
        assertThat(adapter.acquireAuthority(islandId, NODE_ALPHA, 15).isRejected())
                .isTrue();

        // 3. Renew authority by owner succeeds
        IslandAuthorityOutcome renewOutcome = adapter.renewAuthority(islandId, NODE_ALPHA, 1L, 15);
        assertThat(renewOutcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) renewOutcome).epoch()).isEqualTo(1L);

        // 4. Renew by wrong node rejected
        assertThat(adapter.renewAuthority(islandId, NODE_BETA, 1L, 15).isRejected())
                .isTrue();

        // 5. Takeover before lease expiry rejected
        assertThat(adapter.takeoverAuthority(islandId, NODE_BETA, 1L, 15).isRejected())
                .isTrue();

        // 6. Force expire lease in DB
        try (Connection conn = db.connection();
                Statement stmt = conn.createStatement()) {
            if (db.dialect() == Dialect.MYSQL) {
                stmt.execute(
                        "UPDATE island_authorities SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL 5 SECOND WHERE island_id = '"
                                + islandId + "'");
            } else {
                stmt.execute(
                        "UPDATE island_authorities SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '5 seconds' WHERE island_id = '"
                                + islandId + "'");
            }
        }

        // 7. Takeover after lease expiry succeeds and increments epoch
        IslandAuthorityOutcome takeoverOutcome = adapter.takeoverAuthority(islandId, NODE_BETA, 1L, 15);
        assertThat(takeoverOutcome.isSuccess()).isTrue();
        assertThat(((IslandAuthorityOutcome.Success) takeoverOutcome).epoch()).isEqualTo(2L);

        Optional<IslandAuthorityRecord> authority = adapter.findAuthority(islandId);
        assertThat(authority).isPresent();
        assertThat(authority.get().authoritativeNode()).isEqualTo(NODE_BETA);
        assertThat(authority.get().authorityEpoch()).isEqualTo(2L);
    }
}
