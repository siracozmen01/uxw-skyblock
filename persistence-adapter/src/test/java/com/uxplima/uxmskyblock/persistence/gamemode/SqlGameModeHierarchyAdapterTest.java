package com.uxplima.uxmskyblock.persistence.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlGameModeHierarchyAdapterTest {

    @TempDir
    Path tempDir;

    private Database database;
    private SqlGameModeHierarchyAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("hierarchy_test.db");
        database = Database.builder().sqlite(dbPath).build();

        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        adapter = new SqlGameModeHierarchyAdapter(database.dataSource());
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Persists and retrieves GameModeInstance and PrimaryGameplayRootRef")
    void testSaveAndRetrieveGameModeInstanceAndRootRef() throws Exception {
        PlayerUuid playerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        // Setup parent foreign key rows
        try (java.sql.Connection conn = database.connection()) {
            try (java.sql.PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, playerUuid.value().toString());
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }
        }

        GameModeInstanceId instanceId = GameModeInstanceId.random();
        Instant now = Instant.now();
        GameModeInstance instance = new GameModeInstance(
                instanceId, profileId, GameModeType.SKYBLOCK, "{\"difficulty\":\"NORMAL\"}", now, now);

        adapter.saveGameModeInstance(instance);

        Optional<GameModeInstance> optFound = adapter.findInstanceById(instanceId);
        assertThat(optFound).isPresent();
        assertThat(optFound.get().id()).isEqualTo(instanceId);
        assertThat(optFound.get().profileId()).isEqualTo(profileId);
        assertThat(optFound.get().gameModeType()).isEqualTo(GameModeType.SKYBLOCK);
        assertThat(optFound.get().rulesetConfig()).isEqualTo("{\"difficulty\":\"NORMAL\"}");

        Optional<GameModeInstance> optByProfile = adapter.findInstanceByProfileId(profileId);
        assertThat(optByProfile).isPresent();
        assertThat(optByProfile.get().id()).isEqualTo(instanceId);

        // Primary gameplay root ref
        String islandIdStr = UUID.randomUUID().toString();
        PrimaryGameplayRootRef rootRef = PrimaryGameplayRootRef.forIsland(instanceId, islandIdStr, now);
        adapter.savePrimaryGameplayRootRef(rootRef);

        Optional<PrimaryGameplayRootRef> optRoot = adapter.findRootRefByInstanceId(instanceId);
        assertThat(optRoot).isPresent();
        assertThat(optRoot.get().rootId()).isEqualTo(islandIdStr);
        assertThat(optRoot.get().rootType()).isEqualTo("ISLAND");

        Optional<PrimaryGameplayRootRef> optByRoot = adapter.findRootRefByRootId(islandIdStr, "ISLAND");
        assertThat(optByRoot).isPresent();
        assertThat(optByRoot.get().gameModeInstanceId()).isEqualTo(instanceId);
    }
}
