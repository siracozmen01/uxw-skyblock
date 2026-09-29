package com.uxplima.uxmskyblock.core.application.chunkblock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the chunks each ChunkBlock island has open are kept. */
public interface ChunkTerritoryPort {

    /** Every ChunkBlock island's territory, read once when the server starts. */
    Map<IslandId, ChunkTerritory> findAll();

    /** The island's territory, or empty for an island that is not a ChunkBlock island. */
    Optional<ChunkTerritory> find(IslandId islandId);

    /** Records the chunk the island starts with. A second start keeps the first. */
    void start(IslandId islandId, ChunkPos origin);

    /**
     * Records {@code chunk} as the island's {@code order}th opened chunk, counting from one.
     *
     * @return false when another opening took that place or that chunk first
     */
    boolean open(IslandId islandId, ChunkPos chunk, int order);

    /** Closes these opened chunks. The chunk the island started with is never closed. */
    void close(IslandId islandId, List<ChunkPos> chunks);
}
