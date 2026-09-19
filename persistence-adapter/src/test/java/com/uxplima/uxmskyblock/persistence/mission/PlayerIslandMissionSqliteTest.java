package com.uxplima.uxmskyblock.persistence.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandMissionSqliteTest {

    private Database database;
    private PlayerIslandMissionAdapter adapter;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final IslandId islandId = new IslandId(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());
    private final MissionId mission1 = MissionId.of("farming_wheat_1");
    private final MissionId mission2 = MissionId.of("slayer_zombie_1");

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandMissionAdapter(database);

        // Seed island to satisfy foreign keys
        UUID accountUuid = UUID.randomUUID();
        try (Connection conn = database.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, accountUuid.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                stmt.setString(1, profileId.value().toString());
                stmt.setString(2, accountUuid.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid, custom_name, lifecycle, economic_state, administrative_state, level_score, net_worth_minor_units, version) VALUES (?, ?, ?, 'Test Island', 'ACTIVE', 'NORMAL', 'NORMAL', 0, 0, 1)")) {
                stmt.setString(1, islandId.value().toString());
                stmt.setString(2, profileId.value().toString());
                stmt.setString(3, accountUuid.toString());
                stmt.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Empty queries return empty")
    void emptyQueries() {
        assertThat(adapter.findProgress(islandId, profileId, mission1)).isEmpty();
        assertThat(adapter.findAllProgress(islandId, profileId)).isEmpty();
    }

    @Test
    @DisplayName("Saves progress, finds by ID, updates count, and completes")
    void saveAndFindProgress() {
        MissionProgress p1 = new MissionProgress(mission1, 15L, false, null, now);
        adapter.saveProgress(islandId, profileId, p1);

        Optional<MissionProgress> found = adapter.findProgress(islandId, profileId, mission1);
        assertThat(found).isPresent();
        assertThat(found.get().missionId()).isEqualTo(mission1);
        assertThat(found.get().progressCount()).isEqualTo(15L);
        assertThat(found.get().completed()).isFalse();
        assertThat(found.get().completedAt()).isNull();

        // Update with completion
        Instant completedAt = now.plus(10, ChronoUnit.SECONDS);
        MissionProgress p1Completed = new MissionProgress(mission1, 50L, true, completedAt, completedAt);
        adapter.saveProgress(islandId, profileId, p1Completed);

        Optional<MissionProgress> foundCompleted = adapter.findProgress(islandId, profileId, mission1);
        assertThat(foundCompleted).isPresent();
        assertThat(foundCompleted.get().progressCount()).isEqualTo(50L);
        assertThat(foundCompleted.get().completed()).isTrue();
        assertThat(foundCompleted.get().completedAt()).isNotNull();
    }

    @Test
    @DisplayName("Batch saveAllProgress and findAllProgress round-trip")
    void batchSaveAllAndFindAll() {
        MissionProgress p1 = new MissionProgress(mission1, 20L, false, null, now);
        MissionProgress p2 = new MissionProgress(mission2, 10L, true, now, now);

        adapter.saveAllProgress(islandId, profileId, List.of(p1, p2));

        Map<MissionId, MissionProgress> all = adapter.findAllProgress(islandId, profileId);
        assertThat(all).hasSize(2);
        assertThat(all.get(mission1).progressCount()).isEqualTo(20L);
        assertThat(all.get(mission1).completed()).isFalse();
        assertThat(all.get(mission2).progressCount()).isEqualTo(10L);
        assertThat(all.get(mission2).completed()).isTrue();
    }
}
