package com.uxplima.uxmskyblock.core.domain.chunkblock;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/** A chunk, by the chunk coordinates the server uses: a block's coordinate shifted right by four. */
public record ChunkPos(int x, int z) {

    /** The chunk a block stands in. */
    public static ChunkPos ofBlock(int blockX, int blockZ) {
        return new ChunkPos(blockX >> 4, blockZ >> 4);
    }

    /** The four chunks that share a side with this one. */
    public List<ChunkPos> neighbours() {
        return List.of(new ChunkPos(x + 1, z), new ChunkPos(x - 1, z), new ChunkPos(x, z + 1), new ChunkPos(x, z - 1));
    }

    public boolean touches(ChunkPos other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z) == 1;
    }

    /** Whether any block of this chunk lies inside the island. */
    public boolean overlaps(IslandBounds bounds) {
        int minX = x << 4;
        int minZ = z << 4;
        return minX <= bounds.maxX()
                && minX + 15 >= bounds.minX()
                && minZ <= bounds.maxZ()
                && minZ + 15 >= bounds.minZ();
    }
}
