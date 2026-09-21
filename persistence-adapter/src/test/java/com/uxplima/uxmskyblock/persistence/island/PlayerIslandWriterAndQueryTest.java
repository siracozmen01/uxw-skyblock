package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the island writer leaves behind and what the query helper reads back.
 *
 * <p>Two defects lived in the gap between the two. Roles were written and never deleted, so a role
 * an island stopped having was hydrated back on the next read and deleting one looked like it
 * worked until the server restarted. And the flags were read starting from an empty map, so a flag
 * added to the domain after an island was written read as off, whatever its declared default said:
 * VISITOR_ACCESS defaults to true and would have read as false, which is an island silently closed
 * to everybody.
 */
class PlayerIslandWriterAndQueryTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final PlayerUuid OWNER_UUID = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId OWNER = ProfileId.of(UUID.randomUUID());
    private static final ProfileId MATE = ProfileId.of(UUID.randomUUID());

    private Database database;
    private PlayerIslandStorageAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private static Island island() {
        return Island.create(ISLAND, IslandBounds.fromCenterAndRadius(0, 0, 100), OWNER_UUID, OWNER, Instant.now());
    }

    private static IslandLocation location() {
        return new IslandLocation(
                ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100.0, 0.5, 0.0f, 0.0f);
    }

    private Island reload() {
        Optional<Island> reloaded = adapter.findIslandById(ISLAND);
        assertThat(reloaded)
                .describedAs("the island was written, so it must read back")
                .isPresent();
        return reloaded.get();
    }

    @Test
    @DisplayName("Every flag the island carries survives the round trip, both ways up")
    void everyFlagSurvivesTheRoundTrip() {
        Island written = island().withFlags(
                        island().flags().withFlag(IslandFlags.PVP, true).withFlag(IslandFlags.LEAF_DECAY, false));
        adapter.saveIsland(written, location());

        IslandFlags read = reload().flags();
        assertThat(read.isEnabled(IslandFlags.PVP)).isTrue();
        assertThat(read.isEnabled(IslandFlags.LEAF_DECAY)).isFalse();
        assertThat(read.isEnabled(IslandFlags.VISITOR_ACCESS)).isTrue();
        assertThat(read.isEnabled(IslandFlags.LOCKED)).isFalse();
    }

    @Test
    @DisplayName("A flag with no stored row reads its declared default, not off")
    void aFlagWithNoRowReadsItsDefault() throws Exception {
        adapter.saveIsland(island(), location());

        // An island written before VISITOR_ACCESS existed has no row for it. Deleting the row is
        // exactly what that island looks like on disk.
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM island_flags WHERE flag_name = '" + IslandFlags.VISITOR_ACCESS + "'");
        }

        assertThat(reload().flags().isEnabled(IslandFlags.VISITOR_ACCESS))
                .describedAs("VISITOR_ACCESS defaults to true, and an island with no row for it is not closed")
                .isTrue();
    }

    @Test
    @DisplayName("A stored false still beats a default of true, which is what makes the flag worth storing")
    void aStoredFalseBeatsATrueDefault() {
        adapter.saveIsland(
                island().withFlags(island().flags().withFlag(IslandFlags.VISITOR_ACCESS, false)), location());

        assertThat(reload().flags().isEnabled(IslandFlags.VISITOR_ACCESS)).isFalse();
    }

    @Test
    @DisplayName("A role the island stopped having is really gone, not hydrated back on the next read")
    void aRemovedRoleIsReallyGone() {
        IslandRole custom = new IslandRole("BUILDER", 5, "Builder", Set.of(IslandPermission.BLOCK_PLACE), false);
        Map<String, IslandRole> withCustom = new HashMap<>(island().roles());
        withCustom.put(custom.id(), custom);
        adapter.saveIsland(withRoles(island(), withCustom), location());
        assertThat(reload().roles()).containsKey("BUILDER");

        adapter.saveIsland(withRoles(island(), island().roles()), location());

        assertThat(reload().roles())
                .describedAs("a role that was deleted must stay deleted across a restart")
                .doesNotContainKey("BUILDER");
    }

    @Test
    @DisplayName("A removed role's permissions go with it, so a rebuilt role does not inherit them")
    void aRemovedRolesPermissionsGoWithIt() throws Exception {
        IslandRole custom = new IslandRole("BUILDER", 5, "Builder", Set.of(IslandPermission.BLOCK_PLACE), false);
        Map<String, IslandRole> withCustom = new HashMap<>(island().roles());
        withCustom.put(custom.id(), custom);
        adapter.saveIsland(withRoles(island(), withCustom), location());

        adapter.saveIsland(withRoles(island(), island().roles()), location());

        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM island_role_permissions WHERE role_id = 'BUILDER'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    @DisplayName("A member who left is deleted, and one who stayed keeps the day they joined")
    void aMemberWhoLeftIsDeleted() {
        Instant joined = Instant.parse("2026-01-01T00:00:00Z");
        Island withMate = withMembers(
                island(), MATE, new IslandMember(PlayerUuid.of(UUID.randomUUID()), MATE, IslandRole.MEMBER, joined));
        adapter.saveIsland(withMate, location());
        assertThat(reload().members()).containsKey(MATE);

        adapter.saveIsland(island(), location());

        assertThat(reload().members()).doesNotContainKey(MATE).containsKey(OWNER);
    }

    @Test
    @DisplayName("A member's role change is written, and the day they joined is not rewritten with it")
    void aRoleChangeKeepsTheJoiningDay() {
        Instant joined = Instant.parse("2026-01-01T00:00:00Z");
        PlayerUuid mateUuid = PlayerUuid.of(UUID.randomUUID());
        adapter.saveIsland(
                withMembers(island(), MATE, new IslandMember(mateUuid, MATE, IslandRole.MEMBER, joined)), location());

        adapter.saveIsland(
                withMembers(island(), MATE, new IslandMember(mateUuid, MATE, IslandRole.MODERATOR, Instant.now())),
                location());

        IslandMember read =
                java.util.Objects.requireNonNull(reload().members().get(MATE), "the member is still on the island");
        assertThat(read.role().id()).isEqualTo(IslandRole.MODERATOR.id());
        assertThat(read.joinedAt())
                .describedAs("the day somebody joined is not something a promotion rewrites")
                .isEqualTo(joined);
    }

    @Test
    @DisplayName("The owner, the lifecycle and the states all survive the round trip")
    void theCoreRowSurvivesTheRoundTrip() {
        adapter.saveIsland(island(), location());

        Island read = reload();
        assertThat(read.ownerProfileId()).isEqualTo(OWNER);
        assertThat(read.ownerPlayerUuid()).isEqualTo(OWNER_UUID);
        assertThat(read.lifecycle()).isEqualTo(island().lifecycle());
        assertThat(read.economicState()).isEqualTo(island().economicState());
        assertThat(read.administrativeState()).isEqualTo(island().administrativeState());
    }

    @Test
    @DisplayName("The bounds come back as they were written, radius included")
    void theBoundsSurviveTheRoundTrip() {
        adapter.saveIsland(island(), location());

        IslandBounds read = reload().bounds();
        assertThat(read.minX()).isEqualTo(-100);
        assertThat(read.maxX()).isEqualTo(100);
        assertThat(read.radius()).isEqualTo(100);
    }

    @Test
    @DisplayName("An island nobody wrote reads back as nothing rather than as an empty island")
    void anUnknownIslandIsNothing() {
        assertThat(adapter.findIslandById(IslandId.of(UUID.randomUUID()))).isEmpty();
    }

    private static Island withRoles(Island base, Map<String, IslandRole> roles) {
        return new Island(
                base.id(),
                base.bounds(),
                base.ownerPlayerUuid(),
                base.ownerProfileId(),
                base.members(),
                roles,
                base.flags(),
                base.createdAt(),
                base.lifecycle(),
                base.residencyState(),
                base.economicState(),
                base.administrativeState(),
                base.freezeReason());
    }

    private static Island withMembers(Island base, ProfileId profileId, IslandMember member) {
        Map<ProfileId, IslandMember> members = new HashMap<>(base.members());
        members.put(profileId, member);
        return new Island(
                base.id(),
                base.bounds(),
                base.ownerPlayerUuid(),
                base.ownerProfileId(),
                members,
                base.roles(),
                base.flags(),
                base.createdAt(),
                base.lifecycle(),
                base.residencyState(),
                base.economicState(),
                base.administrativeState(),
                base.freezeReason());
    }
}
