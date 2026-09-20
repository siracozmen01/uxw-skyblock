package com.uxplima.uxmskyblock.bukkit.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyStoragePort;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NbtIslandBackupAdapterTest {

    @Test
    @DisplayName(
            "createPreDeletionBackup creates canonical BackupSet folder with manifest, data.schem, and AVAILABLE.marker")
    void testCreatePreDeletionBackupCanonicalStructure(@TempDir Path tempDir) throws Exception {
        WorldDimensionSnapshotPort snapshotPort = mock(WorldDimensionSnapshotPort.class);
        GameModeHierarchyStoragePort hierarchyPort = mock(GameModeHierarchyStoragePort.class);

        NbtIslandBackupAdapter adapter = new NbtIslandBackupAdapter(tempDir.toFile(), snapshotPort, hierarchyPort);

        IslandId islandId = IslandId.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        PlayerUuid ownerPlayerUuid = PlayerUuid.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 16);

        Island island = Island.create(islandId, bounds, ownerPlayerUuid, ownerProfileId, Instant.now());
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "world", 0, 0, 16);

        String backupPath = adapter.createPreDeletionBackup(island, location);

        File backupDir = new File(backupPath);
        assertThat(backupDir).exists().isDirectory();

        File dataFile = new File(backupDir, NbtIslandBackupAdapter.DATA_FILE_NAME);
        File manifestFile = new File(backupDir, BackupService.MANIFEST_FILE_NAME);
        File markerFile = new File(backupDir, BackupService.AVAILABILITY_MARKER_FILE_NAME);

        assertThat(dataFile).exists().isFile();
        assertThat(manifestFile).exists().isFile();
        assertThat(markerFile).exists().isFile();

        // Verify manifest contains expected fields
        String manifestContent = Files.readString(manifestFile.toPath(), StandardCharsets.UTF_8);
        assertThat(manifestContent).contains("ROOT_BACKUP");
        assertThat(manifestContent).contains("ISLAND");
        assertThat(manifestContent).contains(islandId.value().toString());
        assertThat(manifestContent).contains(NbtIslandBackupAdapter.DATA_FILE_NAME);
        assertThat(manifestContent).contains("FULL_RESTORE_CONSISTENT");

        // Verify SHA-256 in manifest matches actual data file checksum
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(dataFile)) {
            byte[] buf = new byte[1024];
            int r;
            while ((r = fis.read(buf)) != -1) {
                digest.update(buf, 0, r);
            }
        }
        String actualSha256 = HexFormat.of().formatHex(digest.digest());
        assertThat(manifestContent).contains(actualSha256);
    }

    @Test
    @DisplayName("Resolves canonical PrimaryGameplayRootRef via GameModeHierarchyStoragePort")
    void testResolvesCanonicalRootRefViaHierarchy(@TempDir Path tempDir) {
        WorldDimensionSnapshotPort snapshotPort = mock(WorldDimensionSnapshotPort.class);
        GameModeHierarchyStoragePort hierarchyPort = mock(GameModeHierarchyStoragePort.class);

        NbtIslandBackupAdapter adapter = new NbtIslandBackupAdapter(tempDir.toFile(), snapshotPort, hierarchyPort);

        IslandId islandId = IslandId.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        PlayerUuid ownerPlayerUuid = PlayerUuid.of(UUID.randomUUID());
        GameModeInstanceId expectedInstanceId = GameModeInstanceId.random();
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 16);

        PrimaryGameplayRootRef canonicalRef = PrimaryGameplayRootRef.forIsland(
                expectedInstanceId, islandId.value().toString(), Instant.now());

        when(hierarchyPort.findRootRefByRootId(eq(islandId.value().toString()), eq("ISLAND")))
                .thenReturn(Optional.of(canonicalRef));

        Island island = Island.create(islandId, bounds, ownerPlayerUuid, ownerProfileId, Instant.now());
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "world", 0, 0, 16);

        adapter.createPreDeletionBackup(island, location);

        verify(hierarchyPort).findRootRefByRootId(eq(islandId.value().toString()), eq("ISLAND"));
    }
}
