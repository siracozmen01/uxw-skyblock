package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Outbound application port defining authoritative final durable player profile inventory
 * persistence during cross-server handoff finalization.
 *
 * <p>Durability Scope and Invariants:
 * <ul>
 *   <li>This port represents the dedicated finalization snapshot persistence path executed on the
 *       source node during cross-server handoff or controlled shutdown while session state is {@code DRAINING}.</li>
 *   <li>It is strictly NOT an {@code InventoryMutationJournal} replacement.</li>
 *   <li>It is strictly NOT valid for {@code IMMEDIATE}, economic, or value-sensitive item mutation execution
 *       (e.g., container extraction, player trades, island vault item transfers, currency/economic transactions).</li>
 *   <li>All {@code IMMEDIATE}, economic, and value-sensitive mutations remain strictly owned by the write-ahead
 *       {@code InventoryMutationJournal} protocol and MUST NOT be downgraded to this finalization path.</li>
 * </ul>
 *
 * <p>Strictly owns optimistic concurrency control (OCC), {@code DRAINING} session authority validation,
 * and atomic coupling of {@code profile_inventories.profile_inventory_version} with
 * {@code player_sessions.last_durable_inventory_version}.
 * Does not depend on SQL, JDBC, or persistence infrastructure libraries.
 */
public interface ProfileHandoffFinalizationPort {

    /**
     * Executes the authoritative final durable flush of player profile inventory during handoff finalization.
     *
     * <p>Serializes on {@code player_sessions} first (exclusive row lock on MariaDB/PostgreSQL,
     * {@code BEGIN IMMEDIATE} on SQLite), validates canonical authority under {@code DRAINING} state,
     * performs an OCC update on {@code profile_inventories} (advancing version to {@code expectedVersion + 1}),
     * and atomically updates {@code player_sessions.last_durable_inventory_version} to match {@code expectedVersion + 1}.
     *
     * @param playerUuid the target player UUID
     * @param profileId the active profile ID
     * @param currentNode the claiming authoritative server node
     * @param expectedEpoch the expected session epoch
     * @param expectedVersion the expected durable inventory version before flush
     * @param state everything the player carries: inventory, ender chest, experience, health, hunger,
     *     effects, where they logged out, game mode and flight. All of it is written, because a join
     *     puts all of it back; its profile must be {@code profileId}
     * @return {@link ProfileInventoryMutationOutcome.Success} carrying new version (expectedVersion + 1) if committed,
     *         or {@link ProfileInventoryMutationOutcome.Rejected}
     */
    ProfileInventoryMutationOutcome finalizeHandoffFlush(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            ProfileInventoryRecord state);

    /**
     * Reads the current durable inventory version recorded on the player's session, if present.
     *
     * @param playerUuid the target player UUID
     * @return an {@link OptionalLong} containing the version, or empty if no session is present
     */
    OptionalLong loadLastDurableInventoryVersion(PlayerUuid playerUuid);
}
