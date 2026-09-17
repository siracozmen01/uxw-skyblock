package com.uxplima.uxmskyblock.bukkit.biome;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;

import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Bukkit/Paper implementation of the biome modification outbound port.
 */
public final class BukkitBiomeAdapter implements BiomeModificationPort {

    private final IslandStoragePort islandStoragePort;
    private final String worldName;

    public BukkitBiomeAdapter(IslandStoragePort islandStoragePort, String worldName) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
    }

    @Override
    public CompletableFuture<Boolean> applyBiome(IslandId islandId, IslandBiome biome) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(biome, "biome");

        return CompletableFuture.supplyAsync(() -> {
            var optIsland = islandStoragePort.findIslandById(islandId);
            if (optIsland.isEmpty()) {
                return false;
            }
            Island island = optIsland.get();
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return false;
            }

            Biome targetBiome = mapToBukkitBiome(biome);
            int minX = island.bounds().minX();
            int maxX = island.bounds().maxX();
            int minZ = island.bounds().minZ();
            int maxZ = island.bounds().maxZ();

            for (int x = minX; x <= maxX; x += 4) {
                for (int z = minZ; z <= maxZ; z += 4) {
                    for (int y = world.getMinHeight(); y < world.getMaxHeight(); y += 4) {
                        world.setBiome(x, y, z, targetBiome);
                    }
                }
            }
            return true;
        });
    }

    public static Biome mapToBukkitBiome(IslandBiome biome) {
        return switch (biome) {
            case PLAINS -> Biome.PLAINS;
            case DESERT -> Biome.DESERT;
            case NETHER_WASTES -> Biome.NETHER_WASTES;
            case JUNGLE -> Biome.JUNGLE;
            case SNOWY_PLAINS -> Biome.SNOWY_PLAINS;
            case FLOWER_FOREST -> Biome.FLOWER_FOREST;
            case SWAMP -> Biome.SWAMP;
            case THE_END -> Biome.THE_END;
        };
    }
}
