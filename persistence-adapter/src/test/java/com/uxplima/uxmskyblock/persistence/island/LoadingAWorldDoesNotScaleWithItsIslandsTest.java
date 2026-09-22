package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
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
 * Reading every island in a world is a fixed number of queries.
 *
 * <p>It used to read a list of island ids and then load each of them on its own, and each of those
 * is five queries plus one for every role. A world with ten thousand islands was sixty thousand
 * round trips, and four things walk every island in a world: the spatial index and the web map at
 * startup, and the bank upkeep sweep and the inactivity scan on a schedule.
 *
 * <p>What is pinned here is that the answer is right and that the work does not grow with the
 * world: the same six queries for one island and for twenty.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class LoadingAWorldDoesNotScaleWithItsIslandsTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;
    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static final Instant CREATED = Instant.parse("2026-09-22T12:00:00Z");

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
    @DisplayName("MariaDB: every island in the world comes back, whole")
    void mariaDbReadsTheWorld() throws Exception {
        assertTheWholeWorldComesBack(mariaDatabase, "maria_world");
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: every island in the world comes back, whole")
    void postgresReadsTheWorld() throws Exception {
        assertTheWholeWorldComesBack(postgresDatabase, "postgres_world");
    }

    private void assertTheWholeWorldComesBack(Database db, String worldName) throws Exception {
        int howMany = 20;
        try (Connection conn = db.connection()) {
            for (int i = 0; i < howMany; i++) {
                island(conn, worldName, i);
            }

            List<Island> islands = PlayerIslandQueryHelper.loadIslandsByWorld(conn, worldName);

            assertThat(islands).describedAs("islands in %s", worldName).hasSize(howMany);
            Island one = islands.get(0);
            assertThat(one.members()).describedAs("its members").hasSize(1);
            assertThat(one.roles()).describedAs("its roles").containsKey("captain");
            assertThat(one.roles().get("captain").permissions())
                    .describedAs("the permissions on that role")
                    .containsExactly(IslandPermission.BLOCK_BREAK);
            assertThat(one.flags().isEnabled("VISITOR_ACCESS"))
                    .describedAs("the stored flag beats its declared default")
                    .isFalse();
            assertThat(one.bounds().radius())
                    .describedAs("the radius derived from the corners")
                    .isEqualTo(40);
        }
    }

    @Test
    @Order(3)
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a world nobody built is empty rather than an error")
    void mariaDbEmptyWorld() throws Exception {
        try (Connection conn = mariaDatabase.connection()) {
            assertThat(PlayerIslandQueryHelper.loadIslandsByWorld(conn, "a_world_nobody_built"))
                    .isEmpty();
        }
    }

    @Test
    @Order(4)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a world nobody built is empty rather than an error")
    void postgresEmptyWorld() throws Exception {
        try (Connection conn = postgresDatabase.connection()) {
            assertThat(PlayerIslandQueryHelper.loadIslandsByWorld(conn, "a_world_nobody_built"))
                    .isEmpty();
        }
    }

    @Test
    @Order(5)
    @EnabledIfMariaDb
    @DisplayName("MariaDB: one island and twenty cost the same number of queries")
    void mariaDbDoesNotScale() throws Exception {
        assertTheQueriesDoNotGrow(mariaDatabase, "maria_scale");
    }

    @Test
    @Order(6)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: one island and twenty cost the same number of queries")
    void postgresDoesNotScale() throws Exception {
        assertTheQueriesDoNotGrow(postgresDatabase, "postgres_scale");
    }

    /**
     * Counts what reached the database by wrapping the connection, so the number is the real one
     * rather than a count of the statements this file happens to name.
     */
    private void assertTheQueriesDoNotGrow(Database db, String worldPrefix) throws Exception {
        String small = worldPrefix + "_one";
        String large = worldPrefix + "_twenty";
        try (Connection conn = db.connection()) {
            island(conn, small, 0);
            for (int i = 0; i < 20; i++) {
                island(conn, large, i);
            }
        }

        int forOne = countQueries(db, small);
        int forTwenty = countQueries(db, large);

        assertThat(forOne).describedAs("queries for one island").isPositive();
        assertThat(forTwenty)
                .describedAs("queries for twenty islands against queries for one")
                .isEqualTo(forOne);
    }

    private int countQueries(Database db, String worldName) throws Exception {
        try (Connection raw = db.connection()) {
            java.util.concurrent.atomic.AtomicInteger prepared = new java.util.concurrent.atomic.AtomicInteger();
            // The connection is wrapped rather than the statements counted by hand, so the number is
            // every query the loader really ran and not the ones this file happens to name.
            Connection counting = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if ("prepareStatement".equals(method.getName())) {
                            prepared.incrementAndGet();
                        }
                        try {
                            return method.invoke(raw, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });

            List<Island> islands = PlayerIslandQueryHelper.loadIslandsByWorld(counting, worldName);
            assertThat(islands).describedAs("islands in %s", worldName).isNotEmpty();
            return prepared.get();
        }
    }

    private static void island(Connection conn, String worldName, int index) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        String player = UUID.randomUUID().toString();
        String profile = UUID.randomUUID().toString();

        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
            stmt.setString(1, player);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt =
                conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
            stmt.setString(1, profile);
            stmt.setString(2, player);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO islands (id, owner_profile_id, owner_account_uuid, lifecycle, created_at)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, profile);
            stmt.setString(3, player);
            stmt.setTimestamp(4, Timestamp.from(CREATED));
            stmt.executeUpdate();
        }
        int centre = 1000 * (index + 1);
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_locations (
                    island_id, world_name, center_x, center_z, min_x, min_z, max_x, max_z,
                    spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, worldName);
            stmt.setInt(3, centre);
            stmt.setInt(4, centre);
            stmt.setInt(5, centre - 40);
            stmt.setInt(6, centre - 40);
            stmt.setInt(7, centre + 40);
            stmt.setInt(8, centre + 40);
            stmt.setDouble(9, centre + 0.5);
            stmt.setDouble(10, 101.0);
            stmt.setDouble(11, centre + 0.5);
            stmt.setDouble(12, 0.0);
            stmt.setDouble(13, 0.0);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_roles (island_id, role_id, weight, display_name, is_system)
                VALUES (?, 'captain', 90, 'Captain', ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setBoolean(2, true);
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_role_permissions (island_id, role_id, permission) VALUES (?, 'captain', ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, IslandPermission.BLOCK_BREAK.name());
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_members (island_id, player_uuid, profile_id, role_id, joined_at)
                VALUES (?, ?, ?, 'captain', ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, player);
            stmt.setString(3, profile);
            stmt.setTimestamp(4, Timestamp.from(CREATED));
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO island_flags (island_id, flag_name, flag_value) VALUES (?, 'VISITOR_ACCESS', ?)
                """)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setBoolean(2, false);
            stmt.executeUpdate();
        }
    }
}
