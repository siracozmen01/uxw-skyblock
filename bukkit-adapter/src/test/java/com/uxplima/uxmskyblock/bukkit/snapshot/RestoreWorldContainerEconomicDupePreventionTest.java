package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.persistence.backup.PlayerBackupCatalogAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.snapshot.SqlRootRelationalSnapshotAdapter;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import com.uxplima.uxmskyblock.persistence.vault.SqlIslandVaultStorageAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * A restore never hands back what a chest held when the backup was taken.
 *
 * <p>The testing standard's scenario, end to end: a backup is taken with 64 diamond blocks in a
 * chest, the owner moves them into the island vault, and an administrator rolls the island back.
 * The chest comes back as a chest and comes back empty, and the vault still holds the blocks. If the
 * chest came back full the blocks would exist twice. Everything here is the production pipeline:
 * the backup service, the file store, the catalogue, the relational and world snapshots and the
 * restore service. Only the island's bounds are handed in.
 */
class RestoreWorldContainerEconomicDupePreventionTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final byte[] DIAMONDS_IN_THE_VAULT = "64 DIAMOND_BLOCK".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path storageRoot;

    private ServerMock server;
    private Database database;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        database = Database.builder().sqliteInMemory().build();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        database.close();
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @EnumSource(
            value = RestoreMode.class,
            names = {"WORLD_CONTENT_SAFE", "FULL_ISLAND"})
    @DisplayName("After a rollback the chest is empty and the vault still holds the 64 diamond blocks")
    void theDiamondsExistOnce(RestoreMode mode) throws Exception {
        World world = server.addSimpleWorld("world");
        IslandId island = IslandId.of(UUID.randomUUID());
        seedIsland(island);

        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(island, "world", 0, 0, 16)));
        WorldDimensionSnapshotAdapter worlds = new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands);
        SqlRootRelationalSnapshotAdapter relational = new SqlRootRelationalSnapshotAdapter(database.dataSource());
        LocalFilesystemStorageAdapter files = new LocalFilesystemStorageAdapter(storageRoot);
        PlayerBackupCatalogAdapter catalog = new PlayerBackupCatalogAdapter(database);
        BackupService backups = new BackupService(catalog, files);
        SqlIslandVaultStorageAdapter vault = new SqlIslandVaultStorageAdapter(database);

        // T1: the chest holds 64 diamond blocks, and the island is backed up.
        Block chestBlock = world.getBlockAt(2, 64, 2);
        chestBlock.setType(Material.CHEST, false);
        Chest chest = (Chest) chestBlock.getState();
        chest.getInventory().addItem(new ItemStack(Material.DIAMOND_BLOCK, 64));
        chest.update(true, false);
        IslandBackupService.BackupOutcome backedUp = new IslandBackupService(backups, relational, worlds, "test")
                .backupIsland(island, BUCKET, List.of(DimensionId.OVERWORLD));
        assertThat(backedUp).isInstanceOf(IslandBackupService.BackupOutcome.Success.class);
        BackupSetId backupSet = ((IslandBackupService.BackupOutcome.Success) backedUp).backupSetId();

        // T2: the owner takes the blocks out of the chest and puts them in the vault.
        ((Chest) chestBlock.getState()).getInventory().clear();
        vault.createPage(island, 1, DIAMONDS_IN_THE_VAULT, UUID.randomUUID().toString());

        // T3: an administrator rolls the island back.
        String prefix = IslandBackupService.prefixFor(backupSet);
        BackupManifest manifest = backups.loadManifest(BUCKET, prefix).orElseThrow();
        IslandRestoreService.RestoreOutcome restored = new IslandRestoreService(catalog, files, relational, worlds)
                .executeRestore(manifest, BUCKET, prefix, true, mode);

        assertThat(restored).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        assertThat(world.getBlockAt(2, 64, 2).getType())
                .describedAs("the chest is geometry, and geometry comes back")
                .isEqualTo(Material.CHEST);
        assertThat(((Chest) world.getBlockAt(2, 64, 2).getState())
                        .getInventory()
                        .isEmpty())
                .describedAs("what the chest held at the backup is not handed back")
                .isTrue();
        Optional<VaultPage> page = vault.findPage(island, 1);
        assertThat(page)
                .describedAs("the vault page the owner filled after the backup")
                .isPresent();
        assertThat(page.orElseThrow().contentsNbt()).isEqualTo(DIAMONDS_IN_THE_VAULT);
    }

    private void seedIsland(IslandId island) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES (?, ?, ?)")) {
            stmt.setString(1, island.value().toString());
            stmt.setString(2, UUID.randomUUID().toString());
            stmt.setString(3, UUID.randomUUID().toString());
            stmt.executeUpdate();
        }
    }
}
