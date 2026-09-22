package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One statement each, not one per island, and the right islands.
 *
 * <p>The heartbeat renews what this node holds, takes over what ran out inside this node's world,
 * and gives a row to an island here that has none. What it must not do matters as much: another
 * node's live lease is left alone, and another world's islands are left to the node that serves
 * them.
 */
class TheAuthorityLeaseIsKeptAliveTest {

    private static final ServerNodeId ALPHA = ServerNodeId.of("node-alpha");
    private static final ServerNodeId BETA = ServerNodeId.of("node-beta");
    private static final String HERE = "skyblock";
    private static final String ELSEWHERE = "skyblock_two";

    private Database database;
    private PlayerIslandStorageAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        adapter = new PlayerIslandStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A lease this node holds is pushed forward rather than left to run out")
    void aHeldLeaseIsPushedForward() throws Exception {
        IslandId islandId = newIsland(HERE);
        adapter.acquireAuthority(islandId, ALPHA, 60);
        Instant firstExpiry = authority(islandId).leaseExpiresAt();

        Thread.sleep(1100);
        IslandAuthoritySweep swept = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(swept.renewed()).isEqualTo(1);
        assertThat(swept.takenOver()).isZero();
        assertThat(swept.acquired()).isZero();
        assertThat(authority(islandId).leaseExpiresAt())
                .describedAs("the lease moved out to the new window")
                .isAfter(firstExpiry);
        assertThat(authority(islandId).authorityEpoch())
                .describedAs("a renewal does not fence anybody off, so the epoch stands still")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("A lease that ran out is picked up, and the epoch moves so the old holder is fenced off")
    void anExpiredLeaseIsPickedUp() throws Exception {
        IslandId islandId = newIsland(HERE);
        adapter.acquireAuthority(islandId, BETA, 1);
        // The database writes the expiry to the whole second, so a lease of one second is only
        // strictly in the past once two have gone by.
        Thread.sleep(2200);

        IslandAuthoritySweep swept = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(swept.takenOver()).isEqualTo(1);
        IslandAuthorityRecord record = authority(islandId);
        assertThat(record.authoritativeNode()).isEqualTo(ALPHA);
        assertThat(record.authorityEpoch()).describedAs("the epoch moved").isEqualTo(2L);
        assertThat(record.leaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("Another node's live lease is left where it is")
    void aLiveLeaseOfAnotherNodeIsLeftAlone() throws Exception {
        IslandId islandId = newIsland(HERE);
        adapter.acquireAuthority(islandId, BETA, 600);

        IslandAuthoritySweep swept = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(swept.total()).describedAs("nothing to do").isZero();
        assertThat(authority(islandId).authoritativeNode())
                .describedAs("taking a live lease off another node is the split brain the lease exists to stop")
                .isEqualTo(BETA);
    }

    @Test
    @DisplayName("An island in this world with no authority row at all gets one")
    void anIslandWithNoRowGetsOne() throws Exception {
        IslandId islandId = newIsland(HERE);

        IslandAuthoritySweep swept = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(swept.acquired()).isEqualTo(1);
        IslandAuthorityRecord record = authority(islandId);
        assertThat(record.authoritativeNode()).isEqualTo(ALPHA);
        assertThat(record.authorityEpoch()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Another world's islands are left to the node that serves them")
    void anotherWorldIsLeftAlone() throws Exception {
        IslandId here = newIsland(HERE);
        IslandId elsewhere = newIsland(ELSEWHERE);

        IslandAuthoritySweep swept = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(swept.acquired())
                .describedAs("only the island in this world")
                .isEqualTo(1);
        assertThat(adapter.findAuthority(here)).isPresent();
        assertThat(adapter.findAuthority(elsewhere))
                .describedAs("an island this node does not serve is none of its business")
                .isEmpty();
    }

    @Test
    @DisplayName("A second beat over the same islands renews rather than claiming again")
    void asecondBeatRenews() throws Exception {
        newIsland(HERE);
        assertThat(adapter.sweepAuthority(ALPHA, HERE, 600).acquired()).isEqualTo(1);

        IslandAuthoritySweep second = adapter.sweepAuthority(ALPHA, HERE, 600);

        assertThat(second.acquired()).isZero();
        assertThat(second.takenOver()).isZero();
        assertThat(second.renewed()).isEqualTo(1);
    }

    @Test
    @DisplayName("A sweep with no islands to sweep does nothing and says so")
    void anEmptyWorldIsNotAFailure() {
        assertThat(adapter.sweepAuthority(ALPHA, HERE, 600)).isEqualTo(IslandAuthoritySweep.NOTHING);
    }

    private IslandAuthorityRecord authority(IslandId islandId) {
        Optional<IslandAuthorityRecord> record = adapter.findAuthority(islandId);
        assertThat(record).describedAs("an authority row for %s", islandId).isPresent();
        return record.orElseThrow();
    }

    private IslandId newIsland(String worldName) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid owner = PlayerUuid.of(UUID.randomUUID());
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
        Island island =
                Island.create(islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), owner, profile, Instant.now());
        adapter.saveIsland(island, IslandLocation.fromCenterAndRadius(islandId, worldName, 0, 0, 100));
        return islandId;
    }
}
