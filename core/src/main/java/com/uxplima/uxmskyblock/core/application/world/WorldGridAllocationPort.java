package com.uxplima.uxmskyblock.core.application.world;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port for persistent, cluster-safe world grid territory allocations.
 *
 * <p>Guarantees monotonic sequence allocation and prevents coordinate collisions across
 * server restarts and distributed cluster nodes.
 */
public interface WorldGridAllocationPort {

    /**
     * Atomically reserves the next available grid sequence index and associates it with the given
     * world coordinates and optional island ID.
     *
     * @param nodeId authoritative server node
     * @param worldName target world identifier
     * @param centerX center X coordinate
     * @param centerZ center Z coordinate
     * @param islandId optional island identifier
     * @return monotonic sequence index assigned to the allocation
     */
    long reserveNextSequence(
            ServerNodeId nodeId, String worldName, int centerX, int centerZ, @Nullable IslandId islandId);

    /**
     * Atomically calculates and reserves the next available grid slot using spiral coordinates.
     *
     * @param nodeId authoritative server node
     * @param worldName target world identifier
     * @param islandId optional island identifier
     * @return fully persisted allocation record
     */
    WorldGridAllocation allocateNext(ServerNodeId nodeId, String worldName, @Nullable IslandId islandId);

    /**
     * Looks up an allocation by its unique sequence index.
     *
     * @param sequenceIndex monotonic sequence index
     * @return optional allocation
     */
    Optional<WorldGridAllocation> findBySequenceIndex(long sequenceIndex);

    /**
     * Looks up an allocation associated with a specific island.
     *
     * @param islandId island identifier
     * @return optional allocation
     */
    Optional<WorldGridAllocation> findByIslandId(IslandId islandId);

    /**
     * Looks up an allocation by its center world coordinates.
     *
     * @param worldName target world identifier
     * @param centerX center X coordinate
     * @param centerZ center Z coordinate
     * @return optional allocation
     */
    Optional<WorldGridAllocation> findByCoordinates(String worldName, int centerX, int centerZ);

    /**
     * Finds the maximum sequence index currently allocated in the specified world.
     *
     * @param worldName target world identifier
     * @return optional maximum sequence index, or empty if no allocations exist
     */
    Optional<Long> findMaxSequenceIndex(String worldName);

    /**
     * Binds an island ID to an existing allocation slot.
     *
     * @param sequenceIndex sequence index of the allocation
     * @param islandId island identifier to bind
     */
    void bindIsland(long sequenceIndex, IslandId islandId);
}
