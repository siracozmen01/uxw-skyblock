package com.uxplima.uxmskyblock.core.application.session;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;

/**
 * Outbound application port defining the canonical SQL-backed player session authority lifecycle.
 *
 * <p>Strictly owns session authority and fencing transitions across cluster nodes.
 * Does not depend on SQL, JDBC, or persistence infrastructure libraries.
 */
public interface PlayerSessionAuthorityPort {

    /**
     * Looks up the current session record for the player, if one exists.
     *
     * @param playerUuid target player UUID
     * @return optional containing the session record
     */
    Optional<PlayerSessionRecord> findSession(PlayerUuid playerUuid);

    /**
     * Ensures an active session for the player on the local node, bootstrapping account,
     * initial profile, default inventory, and session authority if missing, or renewing/taking over.
     *
     * @param playerUuid target player UUID
     * @param defaultProfileId default profile ID to use if bootstrapping for the first time
     * @param currentNode claiming server node ID
     * @return outcome carrying the active session epoch on success, or rejected
     */
    SessionAuthorityOutcome ensureSession(PlayerUuid playerUuid, ProfileId defaultProfileId, ServerNodeId currentNode);

    /**
     * Renews the active session lease heartbeat for the current owner node.
     *
     * <p>Requires state == 'ACTIVE', matching node, matching epoch, and unexpired lease.
     * Crucially, normal renewal NEVER increments session_epoch.
     *
     * @param playerUuid the target player UUID
     * @param currentNode the claiming authoritative node
     * @param currentEpoch the expected current session epoch
     * @return {@link SessionAuthorityOutcome.Success} if updated, or {@link SessionAuthorityOutcome.Rejected}
     */
    SessionAuthorityOutcome renew(PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch);

    /**
     * Initiates a controlled handoff drain, transitioning session state from ACTIVE to DRAINING.
     *
     * <p>Freezes normal player mutations on the source node while preserving lease validity.
     *
     * @param playerUuid the target player UUID
     * @param currentNode the claiming authoritative node
     * @param currentEpoch the expected current session epoch
     * @return {@link SessionAuthorityOutcome.Success} if updated, or {@link SessionAuthorityOutcome.Rejected}
     */
    SessionAuthorityOutcome drain(PlayerUuid playerUuid, ServerNodeId currentNode, long currentEpoch);

    /**
     * Marks the drained session as ready for handoff, binding it to an intended target node and handoff ID.
     *
     * <p>Requires state == 'DRAINING', matching node, matching epoch, and unexpired lease.
     *
     * @param playerUuid the target player UUID
     * @param currentNode the current source node
     * @param currentEpoch the expected current session epoch
     * @param handoffId the unique handoff transfer identifier
     * @param targetNode the destination server node
     * @return {@link SessionAuthorityOutcome.Success} if updated, or {@link SessionAuthorityOutcome.Rejected}
     */
    SessionAuthorityOutcome prepareHandoff(
            PlayerUuid playerUuid,
            ServerNodeId currentNode,
            long currentEpoch,
            String handoffId,
            ServerNodeId targetNode);

    /**
     * Claims session authority on the destination node under a bounded, destination-scoped planned handoff.
     *
     * <p>Requires state == 'HANDOFF_READY', matching handoff ID, matching target node, matching source node,
     * matching source epoch, and unexpired handoff window.
     * Atomically transfers authoritative_node, increments session_epoch, clears handoff metadata,
     * and sets state back to ACTIVE.
     *
     * @param playerUuid the target player UUID
     * @param expectedSourceNode the expected source node relinquishing authority
     * @param expectedSourceEpoch the expected source session epoch
     * @param expectedHandoffId the unique handoff transfer identifier
     * @param destinationNode the new authoritative destination node
     * @return {@link SessionAuthorityOutcome.Success} carrying new epoch if acquired, or {@link SessionAuthorityOutcome.Rejected}
     */
    SessionAuthorityOutcome plannedAcquire(
            PlayerUuid playerUuid,
            ServerNodeId expectedSourceNode,
            long expectedSourceEpoch,
            String expectedHandoffId,
            ServerNodeId destinationNode);

    /**
     * Claims session authority on the destination node following an unrecovered crash or timeout of the prior owner.
     *
     * <p>Requires matching expected epoch and strictly EXPIRED lease (lease_expires_at &lt; CURRENT_TIMESTAMP).
     * Atomically transfers authoritative_node, increments session_epoch, clears handoff metadata,
     * and transitions state to RECOVERING.
     *
     * @param playerUuid the target player UUID
     * @param expectedEpoch the expected session epoch before takeover
     * @param destinationNode the new authoritative destination node
     * @return {@link SessionAuthorityOutcome.Success} carrying new epoch if taken over, or {@link SessionAuthorityOutcome.Rejected}
     */
    SessionAuthorityOutcome failureTakeover(PlayerUuid playerUuid, long expectedEpoch, ServerNodeId destinationNode);
}
