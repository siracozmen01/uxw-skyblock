package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Outbound application port defining authoritative routine ambient player profile inventory checkpointing.
 *
 * <p>Durability Scope and Invariants:
 * <ul>
 *   <li>This port represents the routine ambient {@code CHECKPOINTED} snapshot persistence path
 *       (default 60s cadence under Hybrid durability mode).</li>
 *   <li>It is strictly NOT an {@code InventoryMutationJournal} replacement.</li>
 *   <li>It is strictly NOT valid for {@code IMMEDIATE}, economic, or value-sensitive item mutation execution
 *       (e.g., container extraction, player trades, island vault item transfers, currency/economic transactions).</li>
 *   <li>All {@code IMMEDIATE}, economic, and value-sensitive mutations remain strictly owned by the write-ahead
 *       {@code InventoryMutationJournal} protocol and MUST NOT be downgraded to this ambient checkpoint path.</li>
 * </ul>
 *
 * <p>Strictly owns optimistic concurrency control (OCC) and session-guarded persistence transitions for ambient snapshots.
 * Does not depend on SQL, JDBC, or persistence infrastructure libraries.
 */
public interface ProfileInventoryCheckpointPort {

    /**
     * Executes an authoritative routine ambient checkpoint of the player profile inventory under session authority.
     *
     * <p>Serializes on {@code player_sessions} first (exclusive row lock on MariaDB/PostgreSQL,
     * {@code BEGIN IMMEDIATE} on SQLite), validates canonical authority, state, and DB lease,
     * and performs an OCC update on {@code profile_inventories}.
     *
     * @param playerUuid the target player UUID
     * @param profileId the active profile ID
     * @param currentNode the claiming authoritative server node
     * @param expectedEpoch the expected session epoch
     * @param expectedVersion the expected durable inventory version
     * @param state everything the player carries: inventory, ender chest, experience, health, hunger,
     *     effects, where they logged out, game mode and flight. All of it is written, because a join
     *     puts all of it back; its profile must be {@code profileId}
     * @return {@link ProfileInventoryMutationOutcome.Success} with version + 1 if committed,
     *         or {@link ProfileInventoryMutationOutcome.Rejected}
     */
    ProfileInventoryMutationOutcome checkpointInventory(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId currentNode,
            long expectedEpoch,
            long expectedVersion,
            ProfileInventoryRecord state);

    /**
     * Reads the current durable inventory record for a profile, if present.
     *
     * @param profileId the target profile ID
     * @return an optional containing the record, or empty if not present
     */
    Optional<ProfileInventoryRecord> loadInventory(ProfileId profileId);

    /**
     * Persists the initial inventory record for a newly created profile with version 1.
     *
     * @param record the initial profile inventory record
     */
    void initializeInventory(ProfileInventoryRecord record);
}
