package com.uxplima.uxmskyblock.core.application.inventory;

import java.time.Duration;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Core application outbound port for the write-ahead {@code InventoryMutationJournal}.
 *
 * <p>Governs IMMEDIATE economic and value-sensitive Minecraft item inventory mutations
 * under the Hybrid durability model. Implements the two-phase write-ahead persistence protocol:
 * <ol>
 *   <li><b>Phase 1 (Intent):</b> {@link #recordIntent} locks player session authority under
 *       canonical row locking, validates active profile binding, session state (strictly {@code ACTIVE}),
 *       lease validity, and expected inventory OCC version, then durably commits journal header
 *       ({@code INTENT}) and participant record. No live slot mutation may execute before this commit.</li>
 *   <li><b>Phase 2 (Completion):</b> {@link #commitMutation} re-locks session authority, validates
 *       journal state, atomically updates {@code profile_inventories} with OCC version bump ($V \to V+1$),
 *       synchronizes {@code player_sessions.last_durable_inventory_version} to $V+1$, and marks
 *       the journal record {@code COMMITTED}.</li>
 *   <li><b>Abort:</b> {@link #abortIntent} allows clean rollback of uncommitted intents to {@code ABORTED}
 *       without touching inventory version or data.</li>
 * </ol>
 */
public interface InventoryMutationJournalPort {

    /**
     * Records a durable write-ahead INTENT for an economic inventory mutation.
     *
     * <p>Must be executed and committed BEFORE live in-memory slot modifications begin.
     *
     * @param playerUuid the owning player UUID
     * @param profileId the active profile ID (must match session's active_profile_id)
     * @param nodeId the authoritative server node ID
     * @param sessionEpoch the current session epoch
     * @param expectedVersion the expected current version of the inventory
     * @param operationId the unique idempotency operation ID
     * @param operationType the operation classification (e.g. VAULT_TRANSFER, PLAYER_TRADE)
     * @param beforeFingerprint fingerprint before mutation
     * @param afterFingerprint expected fingerprint after mutation
     * @param payload structured operation metadata JSON/string
     * @param expiryDuration duration before this intent expires
     * @return outcome indicating success (intent recorded), rejection, or conflict
     */
    InventoryMutationJournalOutcome recordIntent(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            long expectedVersion,
            InventoryMutationOperationId operationId,
            String operationType,
            String beforeFingerprint,
            String afterFingerprint,
            String payload,
            Duration expiryDuration);

    /**
     * Atomically commits a previously recorded intent, updating profile inventory NBT,
     * incrementing inventory version, synchronizing the session durable version marker,
     * and marking the journal COMMITTED.
     *
     * @param playerUuid the owning player UUID
     * @param profileId the active profile ID
     * @param nodeId the authoritative server node ID
     * @param sessionEpoch the current session epoch
     * @param expectedVersion the expected version matching the recorded intent
     * @param operationId the operation ID of the recorded intent
     * @param updatedInventoryNbt the new inventory NBT payload
     * @return outcome containing the newly committed inventory version on success
     */
    InventoryMutationJournalOutcome commitMutation(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            long expectedVersion,
            InventoryMutationOperationId operationId,
            byte[] updatedInventoryNbt);

    /**
     * Aborts an active intent record, transitioning journal state to ABORTED without
     * modifying inventory data or advancing versions.
     *
     * @param playerUuid the owning player UUID
     * @param profileId the active profile ID
     * @param nodeId the authoritative server node ID
     * @param sessionEpoch the current session epoch
     * @param operationId the operation ID to abort
     * @return outcome indicating success or rejection
     */
    InventoryMutationJournalOutcome abortIntent(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            InventoryMutationOperationId operationId);

    /**
     * Loads the journal header record for the specified operation ID, if present.
     */
    Optional<InventoryMutationJournalRecord> loadJournal(InventoryMutationOperationId operationId);

    /**
     * Loads the participant record for the specified operation ID and participant index, if present.
     */
    Optional<InventoryMutationParticipantRecord> loadParticipant(
            InventoryMutationOperationId operationId, int participantIndex);

    /**
     * Deletes the journals that have nothing left to recover.
     *
     * <p>A journal is a write-ahead record of an inventory mutation, kept so a crash in the middle
     * can be finished or undone. Once it has committed or been aborted it has done its job, and
     * nothing ever deleted one: the table held every economic item movement a server had ever made,
     * and its participant rows with it.
     *
     * <p>A journal in RECOVERY_REQUIRED is never deleted. That state means a crash left something
     * nobody has reconciled, and deleting it would throw away the only record of it.
     *
     * @return how many journals were deleted
     */
    int purgeSettledBefore(java.time.Instant before);
}
