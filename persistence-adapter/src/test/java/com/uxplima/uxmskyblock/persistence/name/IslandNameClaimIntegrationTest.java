package com.uxplima.uxmskyblock.persistence.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
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
 * Taking an island name is one statement, and the database must be the one that says no.
 *
 * <p>The service read who held a name and then wrote it, which let two islands both read it free and
 * both write it. The statement here refuses to write when another active island already holds the
 * name, so the check cannot be separated from the write even by two servers.
 *
 * <p>Both engines run it because the form differs: MariaDB refuses a bare subquery over the table
 * being updated and needs the derived table, and PostgreSQL compares case sensitively where MariaDB
 * does not.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class IslandNameClaimIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static SqlIslandNameStorageAdapter mariaAdapter;
    private static SqlIslandNameStorageAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new SqlIslandNameStorageAdapter(mariaDatabase.dataSource());
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new SqlIslandNameStorageAdapter(postgresDatabase.dataSource());
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
    @DisplayName("MariaDB: one name goes to one active island")
    void mariaDbClaim() throws Exception {
        assertClaimIsExclusive(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: one name goes to one active island")
    void postgresClaim() throws Exception {
        assertClaimIsExclusive(postgresDatabase, postgresAdapter);
    }

    private void assertClaimIsExclusive(Database db, SqlIslandNameStorageAdapter adapter) throws Exception {
        String wanted = "Citadel" + suffix(db);
        IslandId first = newIsland(db, "ACTIVE");
        IslandId second = newIsland(db, "ACTIVE");
        IslandId erased = newIsland(db, "DELETED");

        assertThat(adapter.claimCustomName(first, IslandName.of(wanted), null))
                .describedAs("the first island takes a free name")
                .isTrue();
        assertThat(adapter.findIslandIdByName(wanted)).contains(first);

        assertThat(adapter.claimCustomName(second, IslandName.of(wanted), null))
                .describedAs("a second island takes a name another active island holds")
                .isFalse();
        assertThat(adapter.findCustomName(second))
                .describedAs("the refused island keeps no name")
                .isEmpty();

        assertThat(adapter.claimCustomName(second, IslandName.of(wanted.toUpperCase(java.util.Locale.ROOT)), null))
                .describedAs("the same name in other letters is the same name")
                .isFalse();

        assertThat(adapter.claimCustomName(first, IslandName.of(wanted), null))
                .describedAs("the island that holds the name may write it again")
                .isTrue();

        String freed = "OldHold" + suffix(db);
        assertThat(adapter.claimCustomName(erased, IslandName.of(freed), null)).isTrue();
        assertThat(adapter.claimCustomName(second, IslandName.of(freed), null))
                .describedAs("a name only a deleted island holds is free again")
                .isTrue();
        assertThat(adapter.findIslandIdByName(freed)).contains(second);
    }

    /** Keeps the two engines from fighting over one name, within what a name is allowed to be. */
    private static String suffix(Database db) {
        return Integer.toString(Math.floorMod(System.identityHashCode(db), 1000));
    }

    private IslandId newIsland(Database db, String lifecycle) throws Exception {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        String player = UUID.randomUUID().toString();
        String profile = UUID.randomUUID().toString();
        try (Connection conn = db.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
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
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                stmt.setString(1, islandId.value().toString());
                stmt.setString(2, profile);
                stmt.setString(3, player);
                stmt.setString(4, lifecycle);
                stmt.setTimestamp(5, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
        }
        return islandId;
    }
}
