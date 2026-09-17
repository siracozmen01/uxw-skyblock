package com.uxplima.uxmskyblock.core.application.biome;

import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound application port for applying biome changes across an island's territory.
 */
public interface BiomeModificationPort {

    CompletableFuture<Boolean> applyBiome(IslandId islandId, IslandBiome biome);
}
