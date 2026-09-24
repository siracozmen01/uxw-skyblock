package com.uxplima.uxmskyblock.persistence.vault;

import java.nio.file.Path;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closing a vault window writes the page and the player's state in one transaction, or neither.
 *
 * <p>The page was written alone, and the player's inventory at the next checkpoint. A crash in between
 * kept what the player put in the vault both in the vault and in their inventory, or lost what they
 * took out. The close now carries the player's whole state, and the page is written only if the
 * player's session is still this node's at the epoch it read and their state is still the version it
 * read. {@link AVaultCloseWritesThePlayerWithThePageIntegrationTest} runs the same on MariaDB and
 * PostgreSQL.
 */
class AVaultCloseWritesThePlayerWithThePageTest {

    @TempDir
    Path dir;

    private Database database;
    private VaultCloseScene scene;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("vault.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        scene = new VaultCloseScene(database);
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("The page and the player's whole state are written together")
    void bothAreWritten() {
        scene.bothAreWritten();
    }

    @Test
    @DisplayName("A session this node no longer holds writes neither the page nor the player")
    void aStaleSessionWritesNeither() {
        scene.aStaleSessionWritesNeither();
    }

    @Test
    @DisplayName("A player state at a version the store is not at writes neither the page nor the player")
    void aMovedVersionWritesNeither() {
        scene.aMovedVersionWritesNeither();
    }
}
