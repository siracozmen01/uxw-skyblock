package com.uxplima.uxmskyblock.bukkit.schematic;

import java.util.Objects;

import org.bukkit.World;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;

/**
 * Where a new island's creation actions run: its world, its centre and the height of the block its
 * players arrive standing on, which is one below their spawn.
 */
public record IslandStart(World world, IslandId islandId, int centerX, int y, int centerZ, StarterPreset preset) {

    public IslandStart {
        Objects.requireNonNull(world, "world must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(preset, "preset must not be null");
    }
}
