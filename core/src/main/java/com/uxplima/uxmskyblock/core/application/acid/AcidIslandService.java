package com.uxplima.uxmskyblock.core.application.acid;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The AcidIsland islands, held in memory so a check on a player never waits on the database.
 */
public final class AcidIslandService {

    private final AcidIslandsPort port;
    private final Map<IslandId, Integer> seaLevels = new ConcurrentHashMap<>();

    public AcidIslandService(AcidIslandsPort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** Reads every AcidIsland island into memory. Off the main thread, when the server starts. */
    public int prime() {
        Map<IslandId, Integer> all = port.findAll();
        all.forEach(seaLevels::putIfAbsent);
        return all.size();
    }

    /** Makes an island an AcidIsland island whose sea's surface stands at {@code seaLevel}. */
    public void add(IslandId islandId, int seaLevel) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        port.add(islandId, seaLevel);
        seaLevels.putIfAbsent(islandId, seaLevel);
    }

    /** The level of the island's sea, or empty for an island that is not an AcidIsland island. Memory only. */
    public OptionalInt seaLevel(IslandId islandId) {
        Integer level = seaLevels.get(islandId);
        return level == null ? OptionalInt.empty() : OptionalInt.of(level);
    }

    /**
     * Reads the island again after it changed. An island that still has its row stays an AcidIsland
     * island and one whose row is gone, because it was erased, is dropped.
     *
     * <p>Forgetting is also what a node does when it hears an island changed, and it hears its own
     * island being created. The row is what says whether the island is gone. Off the main thread: it
     * reads a row.
     */
    public void forget(IslandId islandId) {
        OptionalInt stored = port.find(islandId);
        if (stored.isPresent()) {
            seaLevels.put(islandId, stored.getAsInt());
        } else {
            seaLevels.remove(islandId);
        }
    }

    public boolean isAcid(IslandId islandId) {
        return seaLevels.containsKey(islandId);
    }
}
