package com.uxplima.uxmskyblock.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public mutating actions port for UXPLIMA Skyblock.
 */
public interface UxmSkyblockActions {

    CompletableFuture<IslandResult<IslandSnapshot>> createIsland(UUID ownerId, String presetId);

    CompletableFuture<IslandResult<IslandBankBalance>> depositBank(UUID islandId, UUID actorId, long amountMinorUnits);

    CompletableFuture<IslandResult<IslandBankBalance>> withdrawBank(UUID islandId, UUID actorId, long amountMinorUnits);
}
