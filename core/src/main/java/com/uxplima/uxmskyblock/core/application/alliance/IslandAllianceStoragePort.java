package com.uxplima.uxmskyblock.core.application.alliance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound persistence port for storing and querying bilateral island alliances and pending invites.
 */
public interface IslandAllianceStoragePort {

    /**
     * Persists an established bilateral alliance.
     *
     * @param alliance the alliance record to persist
     */
    void saveAlliance(IslandAlliance alliance);

    /**
     * Removes an established bilateral alliance between two islands.
     *
     * @param islandA the first island
     * @param islandB the second island
     */
    void removeAlliance(IslandId islandA, IslandId islandB);

    /**
     * Checks if two islands are currently allied.
     *
     * @param islandA the first island
     * @param islandB the second island
     * @return true if an active alliance exists between them
     */
    boolean areAllied(IslandId islandA, IslandId islandB);

    /**
     * Retrieves all active alliance records involving the specified island.
     *
     * @param islandId the island identifier
     * @return list of active alliances
     */
    List<IslandAlliance> findAlliances(IslandId islandId);

    /**
     * Counts the total number of active alliances for the specified island.
     *
     * @param islandId the island identifier
     * @return number of active alliances
     */
    int countAlliances(IslandId islandId);

    /**
     * Saves or replaces a pending alliance invite.
     *
     * @param invite the invite to save
     */
    void saveInvite(IslandAllianceInvite invite);

    /**
     * Finds a pending invite from sender to target island.
     *
     * @param senderIslandId the sender island
     * @param targetIslandId the target island
     * @return optional containing the invite if present
     */
    Optional<IslandAllianceInvite> findInvite(IslandId senderIslandId, IslandId targetIslandId);

    /**
     * Finds all pending non-expired invites received by the specified target island.
     *
     * @param targetIslandId the target island
     * @param now the current reference timestamp
     * @return list of active pending invites
     */
    List<IslandAllianceInvite> findPendingInvites(IslandId targetIslandId, Instant now);

    /**
     * Deletes a specific invite between sender and target islands.
     *
     * @param senderIslandId the sender island
     * @param targetIslandId the target island
     */
    void deleteInvite(IslandId senderIslandId, IslandId targetIslandId);

    /**
     * Purges all invites that have expired relative to the given timestamp.
     *
     * @param now the reference timestamp
     */
    void purgeExpiredInvites(Instant now);
}
