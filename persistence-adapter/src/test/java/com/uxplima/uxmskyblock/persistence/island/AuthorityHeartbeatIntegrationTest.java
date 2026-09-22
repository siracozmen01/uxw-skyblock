package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
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

/**
 * The authority heartbeat against the databases a customer runs.
 *
 * <p>It is three statements, each restricted to one world through a subquery, and MariaDB and
 * PostgreSQL disagree about enough of that to be worth asking both. The lease also has to read back
 * as a moment on this machine: it is written by the database's clock, in the database's zone, and a
 * container's zone is not this machine's.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class AuthorityHeartbeatIntegrationTest {

    private static final ServerNodeId ALPHA = ServerNodeId.of("node-alpha");
    private static final ServerNodeId BETA = ServerNodeId.of("node-beta");

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandStorageAdapter mariaAdapter;
    private static PlayerIslandStorageAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerIslandStorageAdapter(mariaDatabase);
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerIslandStorageAdapter(postgresDatabase);
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
    @EnabledIfMariaDb
    @DisplayName("MariaDB: the heartbeat claims, renews and picks up, one world at a time")
    void mariaDbHeartbeat() throws Exception {
        assertTheHeartbeatWorks(mariaDatabase, mariaAdapter, "maria");
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the heartbeat claims, renews and picks up, one world at a time")
    void postgresHeartbeat() throws Exception {
        assertTheHeartbeatWorks(postgresDatabase, postgresAdapter, "postgres");
    }

    private void assertTheHeartbeatWorks(Database db, PlayerIslandStorageAdapter adapter, String tag) throws Exception {
        String here = "world-" + tag;
        String elsewhere = "other-" + tag;

        IslandId unclaimed = newIsland(db, adapter, here);
        IslandId otherWorld = newIsland(db, adapter, elsewhere);

        IslandAuthoritySweep first = adapter.sweepAuthority(ALPHA, here, 600);
        assertThat(first.acquired())
                .describedAs("the island in this world and no other")
                .isEqualTo(1);
        assertThat(adapter.findAuthority(otherWorld)).isEmpty();

        IslandAuthorityRecord claimed = adapter.findAuthority(unclaimed).orElseThrow();
        assertThat(claimed.authoritativeNode()).isEqualTo(ALPHA);
        assertThat(claimed.leaseExpiresAt())
                .describedAs("a ten minute lease reads as ten minutes away, whatever the container's zone is")
                .isAfter(Instant.now().plusSeconds(500))
                .isBefore(Instant.now().plusSeconds(700));

        IslandAuthoritySweep second = adapter.sweepAuthority(ALPHA, here, 600);
        assertThat(second.renewed()).isEqualTo(1);
        assertThat(second.acquired()).isZero();
        assertThat(adapter.findAuthority(unclaimed).orElseThrow().authorityEpoch())
                .describedAs("a renewal fences nobody off")
                .isEqualTo(1L);

        IslandId lapsed = newIsland(db, adapter, here);
        adapter.acquireAuthority(lapsed, BETA, 1);
        Thread.sleep(2200);

        IslandAuthoritySweep third = adapter.sweepAuthority(ALPHA, here, 600);
        assertThat(third.takenOver()).describedAs("the lease that ran out").isEqualTo(1);
        IslandAuthorityRecord takenOver = adapter.findAuthority(lapsed).orElseThrow();
        assertThat(takenOver.authoritativeNode()).isEqualTo(ALPHA);
        assertThat(takenOver.authorityEpoch()).isEqualTo(2L);
    }

    private static int nextSlot;

    private IslandId newIsland(Database db, PlayerIslandStorageAdapter adapter, String worldName) throws Exception {
        PlayerUuid owner = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = db.connection()) {
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
        IslandId islandId = IslandId.of(UUID.randomUUID());
        // One island per grid slot: the location table keeps coordinates unique per world.
        int centre = 1000 * ++nextSlot;
        adapter.saveIsland(
                Island.create(
                        islandId, IslandBounds.fromCenterAndRadius(centre, centre, 100), owner, profile, Instant.now()),
                IslandLocation.fromCenterAndRadius(islandId, worldName, centre, centre, 100));
        return islandId;
    }
}
