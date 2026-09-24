package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Settles the journaled inventory mutations a crash left open, before the player is given the inventory.
 *
 * <p>The journal was written ahead of every item reward so that a crash in the middle could be
 * settled, and nothing ever settled one. An intent left open stayed open: the reward could not be
 * delivered again under its id, and nobody could tell whether the item had reached the player.
 *
 * <p>The inventory the player is about to be given is the durable one, so that is what is compared.
 * <ul>
 *   <li>It holds what the mutation found: the mutation never reached durable storage, whether or not
 *       it ran before the crash. The intent is aborted, nothing is refunded, and the operation may be
 *       tried again.</li>
 *   <li>It holds what the mutation expected: a checkpoint wrote the mutated inventory before the
 *       commit could. The operation is done, so it is committed as it stands and never runs again.
 *       The testing standard reverts this case instead. The journal keeps fingerprints, not the
 *       inventory they came from, so there is nothing to revert to, and an inventory that already
 *       holds the outcome is the outcome.</li>
 *   <li>It holds anything else: something changed the inventory the journal cannot account for.
 *       Nothing is guessed and nothing is refunded; the operation is quarantined for staff.</li>
 * </ul>
 */
public final class InventoryJournalRecovery {

    /** What recovery did with one open intent. */
    public enum Settlement {
        ABORTED,
        ROLLED_FORWARD,
        QUARANTINED,
        /** The journal refused, most likely because the session moved on; the intent stays open. */
        REFUSED
    }

    /** One open intent and what became of it. */
    public record Settled(InventoryMutationOperationId operationId, Settlement settlement) {
        public Settled {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(settlement, "settlement");
        }
    }

    private final InventoryMutationJournalPort journal;
    private final ProfileInventoryCheckpointPort inventories;

    public InventoryJournalRecovery(InventoryMutationJournalPort journal, ProfileInventoryCheckpointPort inventories) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    /**
     * Settles every open intent on {@code profileId}'s inventory, under the session {@code nodeId} holds
     * at {@code sessionEpoch}.
     */
    public List<Settled> recover(PlayerUuid playerUuid, ProfileId profileId, ServerNodeId nodeId, long sessionEpoch) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        List<InventoryMutationOperationId> open = journal.findOpenIntents(profileId);
        if (open.isEmpty()) {
            return List.of();
        }
        String durable = InventoryFingerprint.of(inventories
                .loadInventory(profileId)
                .map(ProfileInventoryRecord::inventoryNbt)
                .orElse(null));
        List<Settled> settled = new ArrayList<>(open.size());
        for (InventoryMutationOperationId operation : open) {
            Optional<InventoryMutationParticipantRecord> participant = journal.loadParticipant(operation, 0);
            Settlement settlement;
            InventoryMutationJournalOutcome outcome;
            if (participant.isPresent() && durable.equals(participant.get().beforeFingerprint())) {
                settlement = Settlement.ABORTED;
                outcome = journal.abortIntent(playerUuid, profileId, nodeId, sessionEpoch, operation);
            } else if (participant.isPresent()
                    && durable.equals(participant.get().afterFingerprint())) {
                settlement = Settlement.ROLLED_FORWARD;
                outcome = journal.settleOpenIntent(
                        playerUuid,
                        profileId,
                        nodeId,
                        sessionEpoch,
                        operation,
                        InventoryMutationJournalState.COMMITTED);
            } else {
                settlement = Settlement.QUARANTINED;
                outcome = journal.settleOpenIntent(
                        playerUuid,
                        profileId,
                        nodeId,
                        sessionEpoch,
                        operation,
                        InventoryMutationJournalState.RECOVERY_REQUIRED);
            }
            settled.add(new Settled(operation, outcome.isSuccess() ? settlement : Settlement.REFUSED));
        }
        return List.copyOf(settled);
    }
}
