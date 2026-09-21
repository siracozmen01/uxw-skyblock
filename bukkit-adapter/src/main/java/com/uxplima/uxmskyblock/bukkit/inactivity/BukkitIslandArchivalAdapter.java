package com.uxplima.uxmskyblock.bukkit.inactivity;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandArchivalPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Platform adapter implementing {@link IslandArchivalPort} to evict visitors and
 * unload archived islands from spatial caching.
 */
public final class BukkitIslandArchivalAdapter implements IslandArchivalPort {

    private final @Nullable IslandProtectionListener protectionListener;
    private final @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;

    public BukkitIslandArchivalAdapter(
            @Nullable IslandProtectionListener protectionListener,
            @Nullable BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter) {
        this.protectionListener = protectionListener;
        this.visitorEvictionAdapter = visitorEvictionAdapter;
    }

    @Override
    public void archiveIsland(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        if (visitorEvictionAdapter != null) {
            visitorEvictionAdapter.evictNonStaffVisitors(islandId, "inactivity.archived_reason");
        }
        if (protectionListener != null) {
            protectionListener.spatialIndex().removeIsland(islandId);
        }
    }
}
