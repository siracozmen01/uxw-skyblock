package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One island is erased once, however many people ask at once.
 *
 * <p>The confirmation code is checked and then removed, not removed with the check, so two
 * confirmations arriving together both passed. An administrator's reset carries no code at all, so
 * two of those never had anything between them either. Two erasures of one island take a backup of
 * a world the other is deleting, release the same grid slot twice, and write two recycle operations
 * for one island.
 */
class OneErasurePerIslandTest {

    private IslandStoragePort islandStoragePort;
    private SpiralSlotPoolPort spiralSlotPoolPort;
    private IslandBackupPort backupPort;
    private IslandVoidingPort voidingPort;
    private IslandRecycleService service;

    private IslandId islandId;
    private ProfileId ownerProfileId;

    /**
     * The real asynchronous boundary in an erasure: the chunks are voided off the calling thread and
     * everything after that waits on it. Held open, the first erasure is still running.
     */
    private final CompletableFuture<Void> voiding = new CompletableFuture<>();

    @BeforeEach
    void setUp() {
        islandStoragePort = mock(IslandStoragePort.class);
        spiralSlotPoolPort = mock(SpiralSlotPoolPort.class);
        backupPort = mock(IslandBackupPort.class);
        voidingPort = mock(IslandVoidingPort.class);

        service = new IslandRecycleService(
                islandStoragePort,
                mock(WorldGridAllocationPort.class),
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                mock(OutboxPort.class),
                null,
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC));

        islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid ownerUuid = new PlayerUuid(UUID.randomUUID());
        ownerProfileId = new ProfileId(ownerUuid.value());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 50);
        Island island =
                Island.create(islandId, bounds, ownerUuid, ownerProfileId, Instant.parse("2026-09-21T12:00:00Z"));
        IslandLocation location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 100, 200, 50);

        when(islandStoragePort.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(islandStoragePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));

        when(backupPort.createPreDeletionBackup(any(), any())).thenReturn("backups/" + islandId.value() + ".zst");
        when(voidingPort.voidIslandChunks(any(), anyString(), any())).thenReturn(voiding);
    }

    @Test
    @DisplayName("A second erasure asked for while the first is running is refused")
    void aSecondErasureIsRefused() throws Exception {
        CompletableFuture<RecycleResult> first = service.executeReset(ownerProfileId, islandId, null, true);
        assertThat(first.isDone())
                .describedAs("the first erasure is still voiding chunks")
                .isFalse();

        RecycleResult second =
                service.executeReset(ownerProfileId, islandId, null, true).get(5, TimeUnit.SECONDS);

        assertThat(second)
                .describedAs("the answer the second caller gets")
                .isInstanceOf(RecycleResult.AlreadyRunning.class);

        voiding.complete(null);
        assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(RecycleResult.Success.class);

        verify(backupPort, times(1)).createPreDeletionBackup(any(), any());
        verify(spiralSlotPoolPort, times(1)).releaseSlot(anyLong(), anyString(), anyInt(), anyInt());
        verify(islandStoragePort, times(1)).deleteIsland(any(), any());
    }

    @Test
    @DisplayName("An erasure that finished lets the island be reset again")
    void theIslandIsNotLockedOutForEver() throws Exception {
        voiding.complete(null);

        assertThat(service.executeReset(ownerProfileId, islandId, null, true).get(10, TimeUnit.SECONDS))
                .isInstanceOf(RecycleResult.Success.class);
        assertThat(service.executeReset(ownerProfileId, islandId, null, true).get(10, TimeUnit.SECONDS))
                .describedAs("the island's slot was given back when the first erasure ended")
                .isInstanceOf(RecycleResult.Success.class);
    }

    @Test
    @DisplayName("An island that was never found never takes a slot")
    void anAbsentIslandTakesNothing() throws Exception {
        voiding.complete(null);
        IslandId absent = IslandId.of(UUID.randomUUID());
        when(islandStoragePort.findIslandById(absent)).thenReturn(Optional.empty());

        assertThat(service.executeReset(ownerProfileId, absent, null, true).get(5, TimeUnit.SECONDS))
                .isInstanceOf(RecycleResult.IslandNotFound.class);
        assertThat(service.executeReset(ownerProfileId, absent, null, true).get(5, TimeUnit.SECONDS))
                .describedAs("asking twice about an island that is not there is not a lockout")
                .isInstanceOf(RecycleResult.IslandNotFound.class);
    }
}
