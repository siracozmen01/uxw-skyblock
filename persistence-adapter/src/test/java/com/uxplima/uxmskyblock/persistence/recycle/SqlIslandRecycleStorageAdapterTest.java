package com.uxplima.uxmskyblock.persistence.recycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlIslandRecycleStorageAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Durable recording, state transitions, and restart durability of recycle operations")
    void recycleOperationsPersistAcrossRestarts() {
        Path dbFile = tempDir.resolve("recycle_restart_test.db");
        IslandId islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid initiator = new PlayerUuid(UUID.randomUUID());
        String opId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Phase 1: Record initial operation and progress states
        try (Database db1 = DatabaseTestFixture.createSqliteFile(dbFile)) {
            MigrationRunner runner = new MigrationRunner(db1);
            runner.apply(SkyblockMigrations.getMigrations(db1.dialect()));

            SqlIslandRecycleStorageAdapter adapter1 = new SqlIslandRecycleStorageAdapter(db1);

            IslandRecycleOperation op = new IslandRecycleOperation(
                    opId, islandId, initiator, 15L, IslandRecycleState.REQUESTED, null, null, now, now);
            adapter1.recordOperation(op);

            Optional<IslandRecycleOperation> loaded = adapter1.findOperationById(opId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().state()).isEqualTo(IslandRecycleState.REQUESTED);
            assertThat(loaded.get().targetSlot()).isEqualTo(15L);

            adapter1.updateState(
                    opId, IslandRecycleState.BACKUP_COMPLETE, "/backups/test.schem", null, now.plusSeconds(5));

            loaded = adapter1.findOperationById(opId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().state()).isEqualTo(IslandRecycleState.BACKUP_COMPLETE);
            assertThat(loaded.get().backupPath()).isEqualTo("/backups/test.schem");
        }

        // Phase 2: Restart database and verify data persists, update to COMPLETED
        try (Database db2 = DatabaseTestFixture.createSqliteFile(dbFile)) {
            SqlIslandRecycleStorageAdapter adapter2 = new SqlIslandRecycleStorageAdapter(db2);

            Optional<IslandRecycleOperation> loaded = adapter2.findOperationById(opId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().state()).isEqualTo(IslandRecycleState.BACKUP_COMPLETE);
            assertThat(loaded.get().backupPath()).isEqualTo("/backups/test.schem");

            adapter2.updateState(opId, IslandRecycleState.COMPLETED, null, null, now.plusSeconds(10));

            loaded = adapter2.findOperationById(opId);
            assertThat(loaded).isPresent();
            assertThat(loaded.get().state()).isEqualTo(IslandRecycleState.COMPLETED);
            assertThat(loaded.get().backupPath()).isEqualTo("/backups/test.schem"); // COALESCE preserved

            List<IslandRecycleOperation> list = adapter2.findOperationsByIslandId(islandId);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).operationId()).isEqualTo(opId);
        }
    }
}
