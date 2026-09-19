package com.uxplima.uxmskyblock.persistence.name;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlIslandNameStorageAdapterTest {

    private Database database;
    private SqlIslandNameStorageAdapter adapter;

    private IslandId islandId1;
    private IslandId islandId2;

    @BeforeEach
    void setUp() throws Exception {
        database = Database.builder().sqliteInMemory().build();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        adapter = new SqlIslandNameStorageAdapter(database.dataSource());

        islandId1 = new IslandId(UUID.randomUUID());
        islandId2 = new IslandId(UUID.randomUUID());

        insertIsland(islandId1, UUID.randomUUID(), UUID.randomUUID());
        insertIsland(islandId2, UUID.randomUUID(), UUID.randomUUID());
    }

    private void insertIsland(IslandId id, UUID profileId, UUID accountUuid) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO islands (
                            id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                            economic_state, administrative_state, version, created_at, updated_at
                        ) VALUES (?, ?, ?, NULL, 'ACTIVE', 'NORMAL', 'NORMAL', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)) {
            stmt.setString(1, id.value().toString());
            stmt.setString(2, profileId.toString());
            stmt.setString(3, accountUuid.toString());
            stmt.executeUpdate();
        }
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Successfully assigns, reads, updates, and resets custom island name")
    void crudCustomName() {
        // Initially empty
        assertThat(adapter.findCustomName(islandId1)).isEmpty();

        // Assign name
        IslandName name1 = IslandName.of("SkySanctuary");
        adapter.updateCustomName(islandId1, name1);

        Optional<IslandName> retrieved = adapter.findCustomName(islandId1);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().value()).isEqualTo("SkySanctuary");

        // Case-insensitive search by name
        Optional<IslandId> found = adapter.findIslandIdByName("skysanctuary");
        assertThat(found).isPresent().contains(islandId1);

        // Update name
        IslandName updatedName = IslandName.of("MegaCitadel");
        adapter.updateCustomName(islandId1, updatedName);
        assertThat(adapter.findCustomName(islandId1)).contains(updatedName);
        assertThat(adapter.findIslandIdByName("SkySanctuary")).isEmpty();
        assertThat(adapter.findIslandIdByName("MegaCitadel")).contains(islandId1);

        // Reset name (null)
        adapter.updateCustomName(islandId1, null);
        assertThat(adapter.findCustomName(islandId1)).isEmpty();
        assertThat(adapter.findIslandIdByName("MegaCitadel")).isEmpty();
    }
}
