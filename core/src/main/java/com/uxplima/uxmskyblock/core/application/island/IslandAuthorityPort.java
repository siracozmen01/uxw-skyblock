package com.uxplima.uxmskyblock.core.application.island;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
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
}
