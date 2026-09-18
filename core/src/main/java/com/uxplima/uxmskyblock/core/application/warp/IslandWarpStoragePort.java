package com.uxplima.uxmskyblock.core.application.warp;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;

/**
 * Storage port governing persistence and retrieval of island warps and island visitor bans.
 */
public interface IslandWarpStoragePort {

    /**
     * Saves or updates an island warp.
     */
    void saveWarp(IslandWarp warp);

    /**
     * Finds a warp by its unique warp identifier.
     */
    Optional<IslandWarp> findWarpById(IslandWarpId warpId);

    /**
     * Finds a warp by its owning island and unique warp name.
     */
    Optional<IslandWarp> findWarpByName(IslandId islandId, WarpName warpName);

    /**
     * Returns all warps configured for an island.
     */
    List<IslandWarp> findWarpsByIsland(IslandId islandId);

    /**
     * Returns all public (unlocked) warps across the network with pagination.
     */
    List<IslandWarp> findPublicWarps(int limit, int offset);

    /**
     * Returns public (unlocked) warps in a specific category with pagination.
     */
    List<IslandWarp> findPublicWarpsByCategory(WarpCategory category, int limit, int offset);

    /**
     * Counts the total number of warps configured on an island.
     */
    int countWarpsByIsland(IslandId islandId);

    /**
     * Deletes a warp by island ID and warp name.
     *
     * @return true if deleted, false if not found
     */
    boolean deleteWarp(IslandId islandId, WarpName warpName);

    /**
     * Persists an island visitor ban.
     */
    void banPlayer(IslandBan ban);

    /**
     * Removes an island visitor ban.
     *
     * @return true if an active ban was removed, false otherwise
     */
    boolean unbanPlayer(IslandId islandId, PlayerUuid playerUuid);

    /**
     * Checks whether a player is banned from visiting an island.
     */
    boolean isPlayerBanned(IslandId islandId, PlayerUuid playerUuid);

    /**
     * Returns all active visitor bans for an island.
     */
    List<IslandBan> findBansByIsland(IslandId islandId);
}
