package com.uxplima.uxmskyblock.core.application.island;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Outbound application port for distributed island single-writer authority leasing.
 */
public interface IslandAuthorityPort {

    /**
     * Acquires initial authority for an unowned island.
     *
     * @param islandId the island id
     * @param nodeId the claiming server node id
     * @param leaseSeconds duration of the initial lease window in seconds
     * @return outcome carrying the new epoch if acquired, or rejected if already owned
     */
    IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds);

    /**
     * Renews the authority lease for the current owner node without advancing epoch.
     *
     * @param islandId the island id
     * @param nodeId the current owner node id
     * @param expectedEpoch the expected active authority epoch
     * @param leaseSeconds duration of the renewal lease window in seconds
     * @return outcome carrying the current epoch if renewed, or rejected if fencing violated
     */
    IslandAuthorityOutcome renewAuthority(IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds);

    /**
     * Takes over authority of an island whose previous lease has expired, advancing epoch.
     *
     * @param islandId the island id
     * @param newNodeId the new claiming server node id
     * @param expectedEpoch the expected prior authority epoch
     * @param leaseSeconds duration of the new lease window in seconds
     * @return outcome carrying the incremented epoch if taken over, or rejected if still valid
     */
    IslandAuthorityOutcome takeoverAuthority(
            IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds);

    /**
     * Retrieves the current persistent authority record for an island.
     *
     * @param islandId the island id
     * @return optional containing the authority record if found
     */
    Optional<IslandAuthorityRecord> findAuthority(IslandId islandId);

    /**
     * Keeps this node's authority over one world's islands alive, and picks up what lapsed.
     *
     * <p>A lease was taken once, when the island was created, and nothing ever renewed it. Every
     * write that needs authority, a bank movement and an upgrade purchase among them, is refused
     * once the lease has run out, so an island stopped being able to use its own bank a lease after
     * it was made and never started again.
     *
     * <p>Three statements, not three per island: renew what this node holds, take over what has run
     * out inside this world, and insert a row for an island here that has none.
     *
     * @param nodeId this node
     * @param worldName the world whose islands this node serves
     * @param leaseSeconds how long the lease runs from now
     * @return what the pass did
     */
    IslandAuthoritySweep sweepAuthority(ServerNodeId nodeId, String worldName, int leaseSeconds);
}
