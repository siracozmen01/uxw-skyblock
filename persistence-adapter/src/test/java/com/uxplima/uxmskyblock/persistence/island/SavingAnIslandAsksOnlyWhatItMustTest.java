package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Saving an island that is already stored does not ask, row by row, whether each row exists.
 *
 * <p>Every island save read whether the island, its location, each role and each flag existed and
 * then wrote it: one question and one answer for every row, and one statement for every permission
 * of every role. An island is saved whenever anything about it changes, so a flag toggled on an
 * island with thirty flags and four roles cost well over a hundred round trips. A row is now updated
 * first and inserted only when there was nothing to update, and a role's permissions go in one batch.
 */
class SavingAnIslandAsksOnlyWhatItMustTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Saving a stored island again asks no row whether it exists and sends each role's permissions once")
    void aSecondSaveAsksNothingRowByRow() throws Exception {
        Island island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                PlayerUuid.of(UUID.randomUUID()),
                ProfileId.of(UUID.randomUUID()),
                Instant.now());
        IslandLocation location = new IslandLocation(
                ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100.0, 0.5, 0.0f, 0.0f);
        try (Connection conn = database.connection()) {
            PlayerIslandWriter.saveIsland(conn, island, location, null);
        }

        List<String> sent = new ArrayList<>();
        try (Connection conn = database.connection()) {
            PlayerIslandWriter.saveIsland(counting(conn, sent), island, location, null);
        }

        assertThat(sent)
                .describedAs("existence questions asked of single rows")
                .noneMatch(sql -> sql.startsWith("query:SELECT 1 FROM"));
        assertThat(sent)
                .describedAs("permissions sent one statement at a time")
                .noneMatch(sql -> sql.startsWith("update:INSERT INTO island_role_permissions"));
        int roles = island.roles().size();
        int flags = island.flags().values().size();
        assertThat(sent)
                .describedAs("round trips for an island with %d roles and %d flags", roles, flags)
                .hasSizeLessThanOrEqualTo(2 + 3 * roles + 3 + flags);
        assertThat(new PlayerIslandStorageAdapter(database).findIslandById(ISLAND))
                .describedAs("the island still reads back after the second save")
                .isPresent();
    }

    @Test
    @DisplayName("Loading an island reads its permissions in one query, not one per role")
    void loadingReadsPermissionsOnce() throws Exception {
        Island island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                PlayerUuid.of(UUID.randomUUID()),
                ProfileId.of(UUID.randomUUID()),
                Instant.now());
        IslandLocation location = new IslandLocation(
                ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100.0, 0.5, 0.0f, 0.0f);
        try (Connection conn = database.connection()) {
            PlayerIslandWriter.saveIsland(conn, island, location, null);
        }
        assertThat(island.roles()).describedAs("roles to read permissions for").hasSizeGreaterThan(1);

        List<String> sent = new ArrayList<>();
        Island loaded;
        try (Connection conn = database.connection()) {
            loaded = PlayerIslandQueryHelper.loadIsland(counting(conn, sent), ISLAND)
                    .orElseThrow();
        }

        assertThat(sent.stream().filter(sql -> sql.contains("FROM island_role_permissions")))
                .describedAs("permission queries for %d roles", island.roles().size())
                .hasSize(1);
        assertThat(loaded.roles()).isEqualTo(island.roles());
    }

    /** A connection that writes down every statement it sends and how it sends it. */
    private static Connection counting(Connection real, List<String> sent) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement ps) {
                        String sql = ((String) args[0]).strip();
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "executeQuery" -> sent.add("query:" + sql);
                                        case "executeUpdate" -> sent.add("update:" + sql);
                                        case "executeBatch" -> sent.add("batch:" + sql);
                                        default -> {}
                                    }
                                    return invoke(m, ps, a);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
