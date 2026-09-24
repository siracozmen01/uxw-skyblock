package com.uxplima.uxmskyblock.core.application.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupArtifact;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * An island a stop left half put back stays closed until the rest of it is back.
 *
 * <p>The persistence specification: the island must not leave quarantine until every unit is
 * verified and the restore has committed. The freeze is durable, so after a stop the island is still
 * frozen under the restore's own reason; the restore that picks it up is the one that lifts it.
 */
class AnInterruptedRestoreKeepsTheIslandClosedUntilItCommitsTest {

    private static final StorageBucket BUCKET = new StorageBucket("backups");
    private static final byte[] WORLD = {4, 5, 6};

    private final IslandId island = IslandId.of(UUID.randomUUID());
    private final BackupSetId setId = BackupSetId.random();
    private final Progress progress = new Progress();
    private final IslandAdminFreezeService freeze = mock(IslandAdminFreezeService.class);
    private final WorldDimensionSnapshotPort world = mock(WorldDimensionSnapshotPort.class);
    private IslandRestoreService service = restoreService();

    @BeforeEach
    void setUp() {
        service = restoreService();
        progress.unfinished.add(new RestoreProgressPort.RestoreOperation(
                UUID.randomUUID(),
                island.value().toString(),
                setId,
                "backups/" + setId,
                RestoreMode.WORLD_CONTENT_SAFE));
        when(freeze.isFrozen(island)).thenReturn(true);
    }

    @Test
    @DisplayName("The restore's own freeze is lifted once the resumed restore has committed, not before")
    void theRestoresOwnFreezeIsLiftedAtTheEnd() {
        frozenFor(IslandRestoreService.QUARANTINE_REASON);

        List<IslandRestoreService.RestoreOutcome> outcomes =
                service.resumeUnfinished(BUCKET, (bucket, prefix) -> Optional.of(manifest()));

        assertThat(outcomes).singleElement().isInstanceOf(IslandRestoreService.RestoreOutcome.Success.class);
        InOrder order = inOrder(world, freeze);
        order.verify(world).restoreWorldDimension(any(), any(), eq(WORLD));
        order.verify(freeze).unfreezeIsland(eq(island), anyString());
        assertThat(progress.committed).isTrue();
    }

    @Test
    @DisplayName("A freeze an administrator made is not the restore's to lift, resumed or not")
    void anAdministratorsFreezeStays() {
        frozenFor("staff investigation");

        service.resumeUnfinished(BUCKET, (bucket, prefix) -> Optional.of(manifest()));

        verify(freeze, never()).unfreezeIsland(any(), anyString());
    }

    @Test
    @DisplayName(
            "A resumed restore whose backup set is gone leaves the island closed, and writes the restore down as failed")
    void aMissingSetLeavesTheIslandClosed() {
        frozenFor(IslandRestoreService.QUARANTINE_REASON);

        List<IslandRestoreService.RestoreOutcome> outcomes =
                service.resumeUnfinished(BUCKET, (bucket, prefix) -> Optional.empty());

        assertThat(outcomes).singleElement().isInstanceOf(IslandRestoreService.RestoreOutcome.Failure.class);
        verifyNoInteractions(world);
        verify(freeze, never()).unfreezeIsland(any(), anyString());
        assertThat(progress.failed).isTrue();
    }

    private void frozenFor(String reason) {
        when(freeze.getFreezeRecord(island))
                .thenReturn(Optional.of(
                        new IslandFreezeRecord(island, AdministrativeState.FROZEN, reason, "someone", Instant.now())));
    }

    private IslandRestoreService restoreService() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        when(storage.exists(any(), any())).thenReturn(true);
        when(storage.getObject(any(), any())).thenReturn(Optional.of(WORLD));
        IslandRestoreService restore = new IslandRestoreService(
                mock(BackupCatalogPort.class), storage, mock(RootRelationalSnapshotPort.class), world);
        restore.quarantineWith(freeze);
        restore.recordProgressIn(progress);
        return restore;
    }

    private BackupManifest manifest() {
        Map<String, BackupArtifact> artifacts = new HashMap<>();
        artifacts.put(
                "world_overworld.dat",
                new BackupArtifact("world_overworld.dat", WORLD.length, BackupService.computeSha256(WORLD)));
        return new BackupManifest(
                setId,
                BackupType.ROOT_BACKUP,
                "ISLAND",
                island.value().toString(),
                Instant.now(),
                1L,
                1L,
                1,
                "1.0.0",
                artifacts,
                "CONSISTENT");
    }

    /** The progress tables, as far as a resumed restore reads and writes them. */
    private static final class Progress implements RestoreProgressPort {
        final List<RestoreOperation> unfinished = new ArrayList<>();
        final Set<String> verified = new HashSet<>();
        boolean committed;
        boolean failed;

        @Override
        public void begin(RestoreOperation operation) {
            unfinished.add(operation);
        }

        @Override
        public void intend(UUID restoreId, String unitId, String sourceChecksum) {}

        @Override
        public void verified(UUID restoreId, String unitId) {
            verified.add(unitId);
        }

        @Override
        public Set<String> verifiedUnits(UUID restoreId) {
            return Set.copyOf(verified);
        }

        @Override
        public void finish(UUID restoreId, OperationState state, @Nullable String failureReason) {
            committed = state == OperationState.COMMITTED;
            failed = state == OperationState.FAILED;
        }

        @Override
        public List<RestoreOperation> unfinished() {
            return List.copyOf(unfinished);
        }
    }
}
