package com.uxplima.uxmskyblock.core.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * An island is closed while it is being put back, and nothing is put back from a set that fails
 * its checksums.
 *
 * <p>The persistence specification locks an island under restore: visitors are sent away and no
 * block or inventory can be edited until the restore is done. A restore works through the island a
 * slice at a time, so without the lock a player kept building on it, and an item put into a chest
 * the restore had not reached yet was cleared with the chest a moment later.
 */
class ARestoreQuarantinesTheIslandTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final byte[] WORLD = {1, 2, 3};

    private final IslandId island = IslandId.of(UUID.randomUUID());
    private IslandAdminFreezeService freeze;
    private RootRelationalSnapshotPort relational;
    private WorldDimensionSnapshotPort world;

    @BeforeEach
    void setUp() {
        freeze = mock(IslandAdminFreezeService.class);
        relational = mock(RootRelationalSnapshotPort.class);
        world = mock(WorldDimensionSnapshotPort.class);
    }

    @Test
    @DisplayName("The island is frozen before the world is touched and unfrozen after it is back")
    void theIslandIsClosedForTheWholeRestore() {
        IslandRestoreService.RestoreOutcome outcome = restore(manifestOf(Map.of("world_overworld.dat", WORLD)));

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        InOrder order = inOrder(freeze, world);
        order.verify(freeze).freezeIsland(eq(island), eq(IslandRestoreService.QUARANTINE_REASON), anyString());
        order.verify(world).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
        order.verify(freeze).unfreezeIsland(eq(island), anyString());
    }

    @Test
    @DisplayName("An island an administrator froze stays frozen after the restore")
    void anAdministratorsFreezeIsLeftAlone() {
        when(freeze.isFrozen(island)).thenReturn(true);

        restore(manifestOf(Map.of("world_overworld.dat", WORLD)));

        verify(world).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
        verify(freeze, never()).unfreezeIsland(any(), anyString());
    }

    @Test
    @DisplayName("A restore that fails half way still opens the island again")
    void aFailedRestoreStillLetsGo() {
        doThrow(new IllegalStateException("the world would not load"))
                .when(world)
                .restoreWorldDimension(any(), any(), any());

        assertThatThrownBy(() -> restore(manifestOf(Map.of("world_overworld.dat", WORLD))))
                .isInstanceOf(IllegalStateException.class);

        verify(freeze).unfreezeIsland(eq(island), anyString());
    }

    @Test
    @DisplayName("An island that cannot be frozen is still restored")
    void anIslandThatCannotBeFrozenIsStillRestored() {
        when(freeze.freezeIsland(any(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Island not found"));

        IslandRestoreService.RestoreOutcome outcome = restore(manifestOf(Map.of("world_overworld.dat", WORLD)));

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        verify(world).restoreWorldDimension(any(), eq(DimensionId.OVERWORLD), eq(WORLD));
        verify(freeze, never()).unfreezeIsland(any(), anyString());
    }

    @Test
    @DisplayName("One artifact failing its checksum puts back none of the others")
    void aBadArtifactStopsTheRestoreBeforeItStarts() {
        Map<String, byte[]> artifacts = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            artifacts.put("world_overworld_" + i + ".dat", WORLD);
        }
        artifacts.put("relational.json", "{}".getBytes(StandardCharsets.UTF_8));
        BackupManifest manifest = manifestOf(artifacts);
        ObjectStoragePort storage = storageHolding(artifacts);
        when(storage.getObject(any(), eq("backups/set/relational.json")))
                .thenReturn(Optional.of("tampered".getBytes(StandardCharsets.UTF_8)));

        IslandRestoreService service =
                new IslandRestoreService(mock(BackupCatalogPort.class), storage, relational, world);
        service.quarantineWith(freeze);
        IslandRestoreService.RestoreOutcome outcome =
                service.executeRestore(manifest, BUCKET, "backups/set", true, RestoreMode.FULL_ISLAND);

        assertThat(outcome).isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verifyNoInteractions(world, relational);
        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
    }

    private IslandRestoreService.RestoreOutcome restore(BackupManifest manifest) {
        Map<String, byte[]> contents = new HashMap<>();
        manifest.artifacts().keySet().forEach(name -> contents.put(name, WORLD));
        IslandRestoreService service =
                new IslandRestoreService(mock(BackupCatalogPort.class), storageHolding(contents), relational, world);
        service.quarantineWith(freeze);
        return service.executeRestore(manifest, BUCKET, "backups/set", true, RestoreMode.WORLD_CONTENT_SAFE);
    }

    private static ObjectStoragePort storageHolding(Map<String, byte[]> artifacts) {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), any())).thenReturn(true);
        artifacts.forEach((name, bytes) ->
                when(storage.getObject(any(), eq("backups/set/" + name))).thenReturn(Optional.of(bytes)));
        return storage;
    }

    private BackupManifest manifestOf(Map<String, byte[]> artifacts) {
        Map<String, BackupArtifact> described = new HashMap<>();
        artifacts.forEach((name, bytes) ->
                described.put(name, new BackupArtifact(name, bytes.length, BackupService.computeSha256(bytes))));
        return new BackupManifest(
                BackupSetId.random(),
                BackupType.ROOT_BACKUP,
                "ISLAND",
                island.value().toString(),
                Instant.now(),
                1L,
                1L,
                1,
                "1.0.0",
                described,
                "CONSISTENT");
    }
}
