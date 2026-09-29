package com.uxplima.uxmskyblock.core.application.stranger;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The StrangerRealms islands, held in memory so a check on a player never waits on the database. */
public final class StrangerRealmsService {

    private final StrangerRealmsPort port;
    private final Set<IslandId> islands = ConcurrentHashMap.newKeySet();

    public StrangerRealmsService(StrangerRealmsPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every StrangerRealms island into memory. Off the main thread, when the server starts. */
    public int prime() {
        Set<IslandId> all = port.findAll();
        islands.addAll(all);
        return all.size();
    }

    /** Makes an island a StrangerRealms island. Off the main thread: it writes a row. */
    public void start(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        port.add(islandId);
        islands.add(islandId);
    }

    /** How far the furthest StrangerRealms island reaches, across the network. Off the main thread. */
    public int farthestReach() {
        return port.farthestReach();
    }

    /** Whether the island is a StrangerRealms island. Memory only. */
    public boolean isStranger(IslandId islandId) {
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
