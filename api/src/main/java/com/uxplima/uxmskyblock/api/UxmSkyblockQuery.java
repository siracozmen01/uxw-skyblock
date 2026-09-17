package com.uxplima.uxmskyblock.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public read-only query port for UXPLIMA Skyblock.
 */
public interface UxmSkyblockQuery {

    CompletableFuture<Optional<IslandSnapshot>> getIsland(UUID islandId);

    CompletableFuture<Optional<IslandSnapshot>> getPlayerIsland(UUID playerId);

    CompletableFuture<List<IslandLeaderboardEntry>> getTopIslands(int limit);

    CompletableFuture<Optional<IslandBankBalance>> getBankBalance(UUID islandId);
}
