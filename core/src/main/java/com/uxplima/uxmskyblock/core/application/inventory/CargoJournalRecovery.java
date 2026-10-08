package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Settles the moves between a player and a vessel's hold that a crash cut short, as the player's session
 * starts.
 *
 * <p>The hold changes only in the move's commit, so an open move never changed it. What the player's
 * durable inventory holds decides the rest:
 * <ul>
 *   <li>what the move found: the move never reached the player, and it is aborted;</li>
 *   <li>what the move left them: they left mid-move with their half, and it is put back from the inventory
 *       the intent kept, so the hold and the player agree again;</li>
 *   <li>anything else: nothing is guessed, the move is quarantined and nothing is given or taken.</li>
 * </ul>
 */
public final class CargoJournalRecovery {

    /** What recovery did with one open move. */
    public enum Settlement {
        ABORTED,
        PUT_BACK,
        QUARANTINED,
        /** The journal refused, most likely because the session moved on; the move stays open. */
        REFUSED
    }

    /** One open move and what became of it. */
    public record Settled(InventoryMutationOperationId operationId, Settlement settlement) {
        public Settled {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(settlement, "settlement");
        }
    }

    private final CargoJournalPort journal;
    private final ProfileInventoryCheckpointPort inventories;

    public CargoJournalRecovery(CargoJournalPort journal, ProfileInventoryCheckpointPort inventories) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    /** Settles every move left open on {@code holder}'s inventory, under the session {@code node} holds. */
    public List<Settled> recover(TradeJournalPort.Holder holder, ServerNodeId node) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(node, "node");
        List<InventoryMutationOperationId> open = journal.findOpenMoves(holder.profile());
        List<Settled> settled = new ArrayList<>(open.size());
        for (InventoryMutationOperationId move : open) {
            settled.add(new Settled(move, settle(move, holder, node)));
        }
        return List.copyOf(settled);
    }

    private Settlement settle(InventoryMutationOperationId move, TradeJournalPort.Holder holder, ServerNodeId node) {
        Optional<CargoJournalPort.PlayerSide> found = journal.playerSide(move);
        if (found.isEmpty() || !found.get().profile().equals(holder.profile())) {
            return Settlement.REFUSED;
        }
        CargoJournalPort.PlayerSide side = found.get();
        Optional<ProfileInventoryRecord> durable = inventories.loadInventory(side.profile());
        String holds = InventoryFingerprint.of(
                durable.map(ProfileInventoryRecord::inventoryNbt).orElse(null));
        long version = durable.map(ProfileInventoryRecord::version).orElse(0L);
        if (holds.equals(side.beforeFingerprint())) {
            return outcome(
                    journal.settle(move, node, holder, null, version, InventoryMutationJournalState.ABORTED),
                    Settlement.ABORTED);
        }
        if (holds.equals(side.afterFingerprint())) {
            return outcome(
                    journal.settle(
                            move, node, holder, side.beforeInventory(), version, InventoryMutationJournalState.ABORTED),
                    Settlement.PUT_BACK);
        }
        return outcome(
                journal.settle(move, node, holder, null, version, InventoryMutationJournalState.RECOVERY_REQUIRED),
                Settlement.QUARANTINED);
    }

    private static Settlement outcome(InventoryMutationJournalOutcome done, Settlement settlement) {
        return done.isSuccess() ? settlement : Settlement.REFUSED;
    }
}
