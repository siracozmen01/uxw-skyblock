package com.uxplima.uxmskyblock.bukkit.inactivity;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandRecyclePort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Platform adapter implementing {@link IslandRecyclePort} to evict visitors, purge spatial
 * caching, trigger chunk voiding, and release the Archimedean spiral slot on team abandonment.
 */
public final class BukkitIslandRecycleAdapter implements IslandRecyclePort {

    private final IslandStoragePort islandStoragePort;
    private final WorldGridAllocationPort worldGridAllocationPort;
    private final SpiralSlotPoolPort spiralSlotPoolPort;
    private final @Nullable FoliaIslandVoidingAdapter voidingAdapter;
    private final @Nullable IslandProtectionListener protectionListener;
    private final @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;

    public BukkitIslandRecycleAdapter(
            IslandStoragePort islandStoragePort,
            WorldGridAllocationPort worldGridAllocationPort,
            SpiralSlotPoolPort spiralSlotPoolPort,
            @Nullable FoliaIslandVoidingAdapter voidingAdapter,
            @Nullable IslandProtectionListener protectionListener,
            @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.worldGridAllocationPort =
                Objects.requireNonNull(worldGridAllocationPort, "worldGridAllocationPort must not be null");
        this.spiralSlotPoolPort = Objects.requireNonNull(spiralSlotPoolPort, "spiralSlotPoolPort must not be null");
        this.voidingAdapter = voidingAdapter;
        this.protectionListener = protectionListener;
        this.visitorEvictionAdapter = visitorEvictionAdapter;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void recycleIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        if (visitorEvictionAdapter != null) {
            visitorEvictionAdapter.evictNonStaffVisitors(
                    islandId, "<red>This island has been deleted and recycled due to team inactivity.</red>");
        }
        if (protectionListener != null) {
            protectionListener.spatialIndex().removeIsland(islandId);
        }

        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optLocation.isPresent() && voidingAdapter != null) {
            IslandLocation loc = optLocation.get();
            voidingAdapter.voidIslandChunks(islandId, loc.worldName(), loc.bounds());
        }

        Optional<WorldGridAllocation> optAlloc = worldGridAllocationPort.findByIslandId(islandId);
        if (optAlloc.isPresent()) {
            WorldGridAllocation alloc = optAlloc.get();
            spiralSlotPoolPort.releaseSlot(alloc.sequenceIndex(), alloc.worldName(), alloc.centerX(), alloc.centerZ());
        }
    }
}
