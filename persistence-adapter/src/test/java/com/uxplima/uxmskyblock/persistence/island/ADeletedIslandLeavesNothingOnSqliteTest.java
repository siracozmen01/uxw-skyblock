package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A deleted island takes everything under it with it on SQLite, as it always did elsewhere.
 *
 * <p>Every table under an island declares {@code ON DELETE CASCADE}, and SQLite ignores that unless the
 * connection asks. A reset deleted the island row and nothing under it: the owner stayed a member of an
 * island that no longer existed and could not make another, and the next island given the freed slot
 * could not be written over the old location.
 */
class ADeletedIslandLeavesNothingOnSqliteTest {

    private static final String[] UNDER_AN_ISLAND = {
        "island_members", "island_locations", "island_roles", "island_role_permissions", "island_flags"
    };

    @TempDir
    Path directory;

    @Test
    @DisplayName("A reset on the server's own SQLite database removes the members, location, roles and flags")
    void aDeleteCascades() throws SQLException {
        Path file = directory.resolve("skyblock.db");
        ProfileId owner = ProfileId.of(UUID.randomUUID());
        IslandId gone;
        IslandId kept;
        try (PersistenceBootstrap persistence = PersistenceBootstrap.createSqlite(file)) {
            gone = store(new Store(persistence), owner, 0);
            kept = store(new Store(persistence), ProfileId.of(UUID.randomUUID()), 5120);

            persistence.islandStoragePort().deleteIsland(gone);

            assertThat(persistence.islandStoragePort().findIslandIdByProfileId(owner))
                    .describedAs("the owner of a deleted island belongs to no island and may make another")
                    .isEmpty();
        }
        assertNothingUnder(file, gone, kept);
    }

    @Test
    @DisplayName("V46 removes what deletes left before the keys were enforced, and nothing of a living island")
    void v46RemovesTheOrphans() throws SQLException {
        Path file = directory.resolve("old.db");
        ProfileId owner = ProfileId.of(UUID.randomUUID());
        IslandId gone;
        IslandId kept;
        // The database as a server left it: keys off, migrated to V45, one island deleted.
        try (Database old = Database.builder().sqlite(file).build()) {
            new MigrationRunner(old)
                    .apply(SkyblockMigrations.getMigrations(old.dialect()).stream()
                            .filter(migration -> migration.version() < 46)
                            .toList());
            PlayerIslandStorageAdapter islands = new PlayerIslandStorageAdapter(old);
            gone = store(new Store(islands), owner, 0);
            kept = store(new Store(islands), ProfileId.of(UUID.randomUUID()), 5120);
            islands.deleteIsland(gone);
            try (Connection conn = old.connection()) {
                assertThat(rowsOf(conn, "island_members", gone))
                        .describedAs("the delete left the members behind, as it did on every SQLite server")
                        .isPositive();
            }
        }

        try (PersistenceBootstrap persistence = PersistenceBootstrap.createSqlite(file)) {
            assertThat(persistence.islandStoragePort().findIslandIdByProfileId(owner))
                    .isEmpty();
        }
        assertNothingUnder(file, gone, kept);
    }

    /** Where a test island is written: the server's port, or the bare adapter over an old database. */
    private record Store(java.util.function.BiConsumer<Island, IslandLocation> save) {
        Store(PersistenceBootstrap persistence) {
            this(persistence.islandStoragePort()::saveIsland);
        }

        Store(PlayerIslandStorageAdapter islands) {
            this(islands::saveIsland);
        }
    }

    private static IslandId store(Store store, ProfileId owner, int centreX) {
        IslandId id = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(centreX, 0, 50);
        store.save()
                .accept(
                        Island.create(id, bounds, PlayerUuid.of(UUID.randomUUID()), owner, Instant.now()),
                        new IslandLocation(id, "skyblock_world", bounds, centreX, 100, 0, 0f, 0f));
        return id;
    }

    /** No row under {@code gone} is left, and every table still holds rows of {@code kept}. */
    private static void assertNothingUnder(Path file, IslandId gone, IslandId kept) throws SQLException {
        try (Database db = Database.builder().sqlite(file).build();
                Connection conn = db.connection()) {
            for (String table : UNDER_AN_ISLAND) {
                assertThat(rowsOf(conn, table, gone)).describedAs(table).isZero();
                assertThat(rowsOf(conn, table, kept))
                        .describedAs(table + " of another island")
                        .isPositive();
            }
        }
    }

    private static int rowsOf(Connection conn, String table, IslandId island) throws SQLException {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM " + table + " WHERE island_id = '" + island.value() + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
