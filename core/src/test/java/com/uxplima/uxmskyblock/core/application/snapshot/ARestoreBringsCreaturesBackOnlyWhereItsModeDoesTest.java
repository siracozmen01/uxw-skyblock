package com.uxplima.uxmskyblock.core.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Geometry only means blocks and biomes; the other modes bring back the creatures that carry nothing.
 */
class ARestoreBringsCreaturesBackOnlyWhereItsModeDoesTest {

    private static final byte[] WORLD = {7, 8, 9};

    @Test
    @DisplayName("A geometry only restore puts the blocks back and brings back no creature")
    void geometryOnlyBringsNone() {
        WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);

        restore(world, RestoreMode.GEOMETRY_ONLY);

        verify(world).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
        verify(world, never()).restoreEntities(any(), any(), any());
    }

    @Test
    @DisplayName("The safe default and a full island restore bring the creatures back after the blocks")
    void theOtherModesBringThemBack() {
        for (RestoreMode mode : new RestoreMode[] {RestoreMode.WORLD_CONTENT_SAFE, RestoreMode.FULL_ISLAND}) {
            WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);

            restore(world, mode);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(world);
            order.verify(world).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
            order.verify(world).restoreEntities(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
        }
        assertThat(RestoreMode.GEOMETRY_ONLY.restoresEntities()).isFalse();
    }

    private static void restore(WorldDimensionSnapshotPort world, RestoreMode mode) {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), any())).thenReturn(true);
        when(storage.getObject(any(), any())).thenReturn(Optional.of(WORLD));
        IslandRestoreService service = new IslandRestoreService(
                mock(BackupCatalogPort.class), storage, mock(RootRelationalSnapshotPort.class), world);
        service.executeRestore(
                new BackupManifest(
                        BackupSetId.random(),
                        BackupType.ROOT_BACKUP,
                        "ISLAND",
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        1L,
                        1L,
                        1,
                        "1.0.0",
                        Map.of(
                                "world_overworld.dat",
                                new BackupArtifact(
                                        "world_overworld.dat", WORLD.length, BackupService.computeSha256(WORLD))),
                        "CONSISTENT"),
                new StorageBucket("backups"),
                "backups/set",
                true,
                mode);
    }
}
