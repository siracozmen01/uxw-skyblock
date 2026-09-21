package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
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
 * Reading an island back out of the database.
 *
 * <p>Every read of an island goes through this helper: its owner, where it is, who belongs to it,
 * what each of them may do and which flags are on. Nothing tested any of it, so a row that read back
 * as the wrong thing would have reached a player before it reached a test.
 *
 * <p>It runs against both engines because the answers differ where the types do: a timestamp, a
 * boolean column, and a null.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerIslandQueryHelperIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static final Instant CREATED = Instant.parse("2026-09-21T12:00:00Z");

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
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
    @DisplayName("MariaDB: an island reads back as the island that was written")
    void mariaDbRead() throws Exception {
        assertTheHelperReadsWhatWasWritten(mariaDatabase);
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: an island reads back as the island that was written")
    void postgresRead() throws Exception {
        assertTheHelperReadsWhatWasWritten(postgresDatabase);
    }

    private void assertTheHelperReadsWhatWasWritten(Database db) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        String owner = UUID.randomUUID().toString();
        String ownerProfile = UUID.randomUUID().toString();
        String mate = UUID.randomUUID().toString();
        String mateProfile = UUID.randomUUID().toString();

        try (Connection conn = db.connection()) {
            profile(conn, owner, ownerProfile);
            profile(conn, mate, mateProfile);
            island(conn, islandId, ownerProfile, owner, "ARCHIVED", "BANKRUPTCY_LOCKED", "FROZEN", "for testing");
            location(conn, islandId, 512, 1024, 40);
            role(conn, islandId, "captain", 90, "Captain", true);
            permission(conn, islandId, "captain", IslandPermission.BLOCK_BREAK.name());
            permission(conn, islandId, "captain", "A_PERMISSION_FROM_A_LATER_RELEASE");
            member(conn, islandId, mate, mateProfile, "captain");
            member(conn, islandId, owner, ownerProfile, "a_role_that_was_deleted");
            flag(conn, islandId, "VISITOR_ACCESS", false);

            Optional<Island> read = PlayerIslandQueryHelper.loadIsland(conn, islandId);

            assertThat(read).isPresent();
            Island island = read.orElseThrow();

            assertThat(island.ownerProfileId().value().toString()).isEqualTo(ownerProfile);
            assertThat(island.ownerPlayerUuid().value().toString()).isEqualTo(owner);
            assertThat(island.createdAt()).isEqualTo(CREATED);
            assertThat(island.lifecycle()).isEqualTo(IslandLifecycle.ARCHIVED);
            assertThat(island.economicState()).isEqualTo(EconomicState.BANKRUPTCY_LOCKED);
            assertThat(island.administrativeState()).isEqualTo(AdministrativeState.FROZEN);
            assertThat(island.freezeReason()).isEqualTo("for testing");

            assertThat(island.bounds().centerX()).isEqualTo(512);
            assertThat(island.bounds().centerZ()).isEqualTo(1024);
            assertThat(island.bounds().radius())
                    .describedAs("the radius is derived from the stored corners")
                    .isEqualTo(40);

            assertThat(island.roles()).containsKey("captain");
            assertThat(island.roles().get("captain").weight()).isEqualTo(90);
            assertThat(island.roles().get("captain").isSystem()).isTrue();
            assertThat(island.roles().get("captain").permissions())
                    .describedAs("a permission this release does not know is skipped, not fatal")
                    .containsExactly(IslandPermission.BLOCK_BREAK);

            assertThat(island.members()).hasSize(2);
            assertThat(island.members()
                            .get(com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(
                                    UUID.fromString(mateProfile)))
                            .role()
                            .id())
                    .isEqualTo("captain");
            assertThat(island.members()
                            .get(com.uxplima.uxmskyblock.core.domain.identity.ProfileId.of(
                                    UUID.fromString(ownerProfile)))
                            .role()
                            .id())
                    .describedAs("a member whose role was deleted falls to visitor rather than to nothing")
                    .isEqualTo(com.uxplima.uxmskyblock.core.domain.island.IslandRole.VISITOR.id());

            assertThat(island.flags().isEnabled("VISITOR_ACCESS"))
                    .describedAs("the stored value wins over the declared default")
                    .isFalse();
            for (var declared : IslandFlags.defaults().values().entrySet()) {
                if ("VISITOR_ACCESS".equals(declared.getKey())) {
                    continue;
                }
                assertThat(island.flags().isEnabled(declared.getKey()))
                        .describedAs("a flag with no stored row reads as its declared default: %s", declared.getKey())
                        .isEqualTo(declared.getValue());
            }
        }
    }

    @Test
    @Order(3)
    @EnabledIfMariaDb
    @DisplayName("MariaDB: an island with no place is answered as absent, not put at the world origin")
    void mariaDbNoLocation() throws Exception {
        assertAnIslandWithNoPlaceIsAbsent(mariaDatabase);
    }

    @Test
    @Order(4)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: an island with no place is answered as absent, not put at the world origin")
    void postgresNoLocation() throws Exception {
        assertAnIslandWithNoPlaceIsAbsent(postgresDatabase);
    }

    /**
     * An island row with no location row used to be handed back with bounds a hundred blocks across
     * the world origin, which is where the first island on the server sits.
     */
    private void assertAnIslandWithNoPlaceIsAbsent(Database db) throws Exception {
        IslandId placed = IslandId.of(UUID.randomUUID());
        IslandId misplaced = IslandId.of(UUID.randomUUID());

        try (Connection conn = db.connection()) {
            String ownerA = UUID.randomUUID().toString();
            String profileA = UUID.randomUUID().toString();
            profile(conn, ownerA, profileA);
            island(conn, placed, profileA, ownerA, "ACTIVE", "NORMAL", "NORMAL", null);
            location(conn, placed, 0, 0, 25);
            member(conn, placed, ownerA, profileA, "OWNER");

            String ownerB = UUID.randomUUID().toString();
            String profileB = UUID.randomUUID().toString();
            profile(conn, ownerB, profileB);
            island(conn, misplaced, profileB, ownerB, "ACTIVE", "NORMAL", "NORMAL", null);
            member(conn, misplaced, ownerB, profileB, "OWNER");

            assertThat(PlayerIslandQueryHelper.loadIsland(conn, misplaced))
                    .describedAs("an island with no row in island_locations")
                    .isEmpty();
            assertThat(PlayerIslandQueryHelper.loadIsland(conn, placed))
                    .describedAs("the island that really is at the origin is untouched")
                    .isPresent();
        }
    }

    @Test
    @Order(5)
    @EnabledIfMariaDb
    @DisplayName("MariaDB: an island that was never written reads as absent")
    void mariaDbUnknown() throws Exception {
        try (Connection conn = mariaDatabase.connection()) {
            assertThat(PlayerIslandQueryHelper.loadIsland(conn, IslandId.of(UUID.randomUUID())))
                    .isEmpty();
        }
    }

    @Test
    @Order(6)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: an island that was never written reads as absent")
    void postgresUnknown() throws Exception {
        try (Connection conn = postgresDatabase.connection()) {
            assertThat(PlayerIslandQueryHelper.loadIsland(conn, IslandId.of(UUID.randomUUID())))
                    .isEmpty();
        }
    }

    private static void profile(Connection conn, String playerUuid, String profileId) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
            stmt.setString(1, playerUuid);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt =
                conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
            stmt.setString(1, profileId);
            stmt.setString(2, playerUuid);
            stmt.executeUpdate();
        }
    }

    private static void island(
            Connection conn,
            IslandId islandId,
            String ownerProfile,
            String ownerAccount,
            String lifecycle,
            String economicState,
            String administrativeState,
            String freezeReason)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO islands (
                    id, owner_profile_id, owner_account_uuid, lifecycle,
                    economic_state, administrative_state, freeze_reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, ownerProfile);
            stmt.setString(3, ownerAccount);
            stmt.setString(4, lifecycle);
            stmt.setString(5, economicState);
            stmt.setString(6, administrativeState);
            stmt.setString(7, freezeReason);
            stmt.setTimestamp(8, Timestamp.from(CREATED));
            stmt.executeUpdate();
        }
    }

    private static void location(Connection conn, IslandId islandId, int centerX, int centerZ, int radius)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_locations (
                    island_id, world_name, center_x, center_z, min_x, min_z, max_x, max_z,
                    spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, "world");
            stmt.setInt(3, centerX);
            stmt.setInt(4, centerZ);
            stmt.setInt(5, centerX - radius);
            stmt.setInt(6, centerZ - radius);
            stmt.setInt(7, centerX + radius);
            stmt.setInt(8, centerZ + radius);
            stmt.setDouble(9, centerX + 0.5);
            stmt.setDouble(10, 101.0);
            stmt.setDouble(11, centerZ + 0.5);
            stmt.setDouble(12, 0.0);
            stmt.setDouble(13, 0.0);
            stmt.executeUpdate();
        }
    }

    private static void role(
            Connection conn, IslandId islandId, String roleId, int weight, String displayName, boolean isSystem)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_roles (island_id, role_id, weight, display_name, is_system)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, roleId);
            stmt.setInt(3, weight);
            stmt.setString(4, displayName);
            stmt.setBoolean(5, isSystem);
            stmt.executeUpdate();
        }
    }

    private static void permission(Connection conn, IslandId islandId, String roleId, String permission)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_role_permissions (island_id, role_id, permission) VALUES (?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, roleId);
            stmt.setString(3, permission);
            stmt.executeUpdate();
        }
    }

    private static void member(Connection conn, IslandId islandId, String playerUuid, String profileId, String roleId)
            throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_members (island_id, player_uuid, profile_id, role_id, joined_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, playerUuid);
            stmt.setString(3, profileId);
            stmt.setString(4, roleId);
            stmt.setTimestamp(5, Timestamp.from(CREATED));
            stmt.executeUpdate();
        }
    }

    private static void flag(Connection conn, IslandId islandId, String name, boolean value) throws Exception {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_flags (island_id, flag_name, flag_value) VALUES (?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, name);
            stmt.setBoolean(3, value);
            stmt.executeUpdate();
        }
    }
}
