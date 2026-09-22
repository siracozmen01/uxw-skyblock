package com.uxplima.uxmskyblock.persistence.vault;

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
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
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
 * The vault audit log is trimmed by one statement, and both engines have to accept it.
 *
 * <p>The trim ranks the entries of every page inside a derived table and deletes what falls past the
 * retention. MariaDB refuses a bare subquery over the table a DELETE is working on, which is why the
 * ranking is wrapped, and this is the test that says the wrapping is enough. A window function that
 * only ever ran against SQLite proves nothing about the database a customer runs.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class VaultAuditTrimIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static SqlIslandVaultStorageAdapter mariaAdapter;
    private static SqlIslandVaultStorageAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new SqlIslandVaultStorageAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new SqlIslandVaultStorageAdapter(postgresDatabase);
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
    @DisplayName("MariaDB: the trim keeps the newest entries of every page")
    void mariaDbTrim() throws Exception {
        assertTrimKeepsTheNewest(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the trim keeps the newest entries of every page")
    void postgresTrim() throws Exception {
        assertTrimKeepsTheNewest(postgresDatabase, postgresAdapter);
    }

    private void assertTrimKeepsTheNewest(Database db, SqlIslandVaultStorageAdapter adapter) throws Exception {
        IslandId islandId = newIsland(db);
        adapter.createPage(islandId, 1, new byte[] {1}, "operator");
        adapter.createPage(islandId, 2, new byte[] {2}, "operator");

        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 6; i++) {
            adapter.appendAuditLog(entry(islandId, 1, base.plusSeconds(i), "PAGE1_" + i));
        }
        adapter.appendAuditLogs(List.of(
                entry(islandId, 2, base, "PAGE2_0"),
                entry(islandId, 2, base.plusSeconds(1), "PAGE2_1"),
                entry(islandId, 2, base.plusSeconds(2), "PAGE2_2")));

        assertThat(adapter.findRecentAuditLogs(islandId, 50))
                .describedAs("a batch of three goes in whole")
                .hasSize(9);

        assertThat(adapter.trimAuditLogs(2))
                .describedAs("four dropped off page one and one off page two")
                .isEqualTo(5);

        assertThat(adapter.findRecentAuditLogs(islandId, 50))
                .extracting(VaultAuditLogEntry::itemSummary)
                .containsExactlyInAnyOrder("PAGE1_5", "PAGE1_4", "PAGE2_2", "PAGE2_1");

        assertThat(adapter.trimAuditLogs(2))
                .describedAs("running it again drops nothing")
                .isZero();
    }

    private static VaultAuditLogEntry entry(IslandId islandId, int page, Instant createdAt, String summary) {
        return new VaultAuditLogEntry(
                UUID.randomUUID(),
                islandId,
                page,
                UUID.randomUUID().toString(),
                VaultActionType.DEPOSIT,
                0,
                summary,
                1,
                createdAt);
    }

    private IslandId newIsland(Database db) throws Exception {
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
                stmt.setString(4, "ACTIVE");
                stmt.setTimestamp(5, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
        }
        return islandId;
    }
}
