package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The TradeWinds vessels, held in memory so a check on a player never waits on the database. */
public final class VesselService {

    private final VesselsPort port;
    private final Set<IslandId> islands = ConcurrentHashMap.newKeySet();

    public VesselService(VesselsPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every TradeWinds vessel into memory. Off the main thread, when the server starts. */
    public int prime() {
        Set<IslandId> all = port.findAll();
        islands.addAll(all);
        return all.size();
    }

    /** Makes an island a TradeWinds vessel. Off the main thread: it writes a row. */
    public void start(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        port.add(islandId);
        islands.add(islandId);
    }

    /** Whether the island is a TradeWinds vessel. Memory only. */
    public boolean isVessel(IslandId islandId) {
        return islands.contains(islandId);
    }

    /**
     * Reads the island again after it changed: kept while its row stands, dropped once it is gone
     * because the island was erased. Off the main thread: it reads a row.
     */
    public void forget(IslandId islandId) {
        if (port.exists(islandId)) {
            islands.add(islandId);
        } else {
            islands.remove(islandId);
        }
    }
}
