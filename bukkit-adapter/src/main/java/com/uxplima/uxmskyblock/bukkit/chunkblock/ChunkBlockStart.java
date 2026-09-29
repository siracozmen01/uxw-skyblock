package com.uxplima.uxmskyblock.bukkit.chunkblock;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;

/**
 * The creation action that makes an island a ChunkBlock island: its territory starts as the chunk
 * the island's centre stands in, where the magic block is set.
 */
public final class ChunkBlockStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:chunkblock";

    private final ChunkBlockService service;

    public ChunkBlockStart(ChunkBlockService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        service.start(start.islandId(), start.centerX(), start.centerZ());
    }
}
