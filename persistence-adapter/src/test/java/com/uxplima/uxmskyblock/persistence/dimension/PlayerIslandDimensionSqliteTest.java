package com.uxplima.uxmskyblock.persistence.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandDimensionSqliteTest {

    private Database database;
    private PlayerIslandDimensionAdapter adapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandDimensionAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("markDimensionGenerated persists dimension record idempotently")
    void markDimensionGeneratedIdempotent() {
        IslandId islandId = new IslandId(UUID.randomUUID());

        assertThat(adapter.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isFalse();

        adapter.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        assertThat(adapter.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();

        // Calling a second time must succeed without error (ON CONFLICT DO NOTHING)
        adapter.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        assertThat(adapter.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isTrue();
    }

    @Test
    @DisplayName("getGeneratedDimensions returns all marked dimensions for island")
    void getGeneratedDimensionsReturnsAllMarked() {
        IslandId islandId = new IslandId(UUID.randomUUID());

        adapter.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        adapter.markDimensionGenerated(islandId, IslandDimensionType.THE_END);

        Set<IslandDimensionType> dimensions = adapter.getGeneratedDimensions(islandId);
        assertThat(dimensions).containsExactlyInAnyOrder(IslandDimensionType.NETHER, IslandDimensionType.THE_END);
    }

    @Test
    @DisplayName("deleteIslandDimensions deletes all dimension records for island")
    void deleteIslandDimensionsRemovesAll() {
        IslandId islandId = new IslandId(UUID.randomUUID());

        adapter.markDimensionGenerated(islandId, IslandDimensionType.NETHER);
        adapter.markDimensionGenerated(islandId, IslandDimensionType.THE_END);

        assertThat(adapter.getGeneratedDimensions(islandId)).isNotEmpty();

        adapter.deleteIslandDimensions(islandId);
        assertThat(adapter.getGeneratedDimensions(islandId)).isEmpty();
        assertThat(adapter.hasGeneratedDimension(islandId, IslandDimensionType.NETHER))
                .isFalse();
    }
}
