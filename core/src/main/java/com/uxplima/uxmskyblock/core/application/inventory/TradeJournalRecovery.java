package com.uxplima.uxmskyblock.core.application.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Settles the trades a crash cut short, one side at a time, as each player's session starts.
 *
 * <p>A side is read from durable storage, the inventory the player is about to be given:
 * <ul>
 *   <li>{@code BEFORE}: it holds what the trade found, or the side was already put back.</li>
 *   <li>{@code AFTER}: it holds what the trade gave it. The player left in the middle and the last write
 *       of their inventory kept the change.</li>
 *   <li>{@code UNKNOWN}: anything else.</li>
 * </ul>
 * Every side {@code AFTER} is a trade that happened: it is committed as it stands. Every side
 * {@code BEFORE} is a trade that never reached anyone: it is aborted. A mix is half a trade, and the side
 * that holds its half is put back from the inventory the intent kept, under that side's own session.
 * The other sides are put back the same way when their players come. One side {@code UNKNOWN} is
 * something nobody can account for: the trade is quarantined and nothing is given or taken.
 */
public final class TradeJournalRecovery {

    /** What recovery did with one open trade, from the side of the player it ran for. */
    public enum Settlement {
        /** No side holds the trade any longer, and it is aborted. */
        ABORTED,
        /** Every side holds the trade's outcome, and it is committed. */
        ROLLED_FORWARD,
        /** This side held its half and was put back; another side still has to be. */
        PUT_BACK,
        /** This side held nothing of the trade; another side still holds its half. */
        RELEASED,
        /** A side holds something the trade cannot account for. */
        QUARANTINED,
        /** The journal refused, most likely because the session moved on; the trade stays open. */
        REFUSED
    }

    /** One open trade and what became of it. */
    public record Settled(InventoryMutationOperationId operationId, Settlement settlement) {
        public Settled {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(settlement, "settlement");
        }
    }

    private enum Found {
        BEFORE,
        AFTER,
        UNKNOWN
    }

    private final TradeJournalPort journal;
    private final ProfileInventoryCheckpointPort inventories;

    public TradeJournalRecovery(TradeJournalPort journal, ProfileInventoryCheckpointPort inventories) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    /** Settles every trade left open on {@code holder}'s inventory, under the session {@code node} holds. */
    public List<Settled> recover(TradeJournalPort.Holder holder, ServerNodeId node) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(node, "node");
        List<InventoryMutationOperationId> open = journal.findOpenTrades(holder.profile());
        List<Settled> settled = new ArrayList<>(open.size());
        for (InventoryMutationOperationId trade : open) {
            settled.add(new Settled(trade, settle(trade, holder, node)));
        }
        return List.copyOf(settled);
    }

    private Settlement settle(InventoryMutationOperationId trade, TradeJournalPort.Holder holder, ServerNodeId node) {
        List<TradeJournalPort.Participant> participants = journal.participants(trade);
        Optional<TradeJournalPort.Participant> mine = participants.stream()
                .filter(side -> side.profile().equals(holder.profile()))
                .findFirst();
        if (mine.isEmpty()) {
            return Settlement.REFUSED;
        }
        List<Found> found = new ArrayList<>(participants.size());
        long myVersion = 0;
        for (TradeJournalPort.Participant side : participants) {
            Optional<ProfileInventoryRecord> durable = inventories.loadInventory(side.profile());
            if (side == mine.get()) {
                myVersion = durable.map(ProfileInventoryRecord::version).orElse(0L);
            }
            found.add(classify(side, durable));
        }
        if (found.contains(Found.UNKNOWN)) {
            return outcome(
                    journal.settleTrade(trade, node, holder, InventoryMutationJournalState.RECOVERY_REQUIRED),
                    Settlement.QUARANTINED);
        }
        if (!found.contains(Found.BEFORE)) {
            return outcome(
                    journal.settleTrade(trade, node, holder, InventoryMutationJournalState.COMMITTED),
                    Settlement.ROLLED_FORWARD);
        }
        int index = participants.indexOf(mine.get());
        boolean iHoldMyHalf = found.get(index) == Found.AFTER;
        // Once this side holds nothing of the trade, does any other side still hold its half?
        boolean anotherHoldsItsHalf = false;
        for (int i = 0; i < found.size(); i++) {
            if (i != index && found.get(i) == Found.AFTER) {
                anotherHoldsItsHalf = true;
            }
        }
        InventoryMutationJournalOutcome done = journal.settleSide(
                trade,
                mine.get().index(),
                node,
                holder,
                iHoldMyHalf ? mine.get().beforeInventory() : null,
                myVersion,
                !anotherHoldsItsHalf);
        if (!anotherHoldsItsHalf) {
            return outcome(done, Settlement.ABORTED);
        }
        return outcome(done, iHoldMyHalf ? Settlement.PUT_BACK : Settlement.RELEASED);
    }

    private static Found classify(TradeJournalPort.Participant side, Optional<ProfileInventoryRecord> durable) {
        if (side.applyState() == ParticipantApplyState.REVERTED) {
            // Put back already, by its own player's recovery; what it holds since is theirs.
            return Found.BEFORE;
        }
        String now = InventoryFingerprint.of(
                durable.map(ProfileInventoryRecord::inventoryNbt).orElse(null));
        if (now.equals(side.beforeFingerprint())) {
            return Found.BEFORE;
        }
        if (now.equals(side.afterFingerprint())) {
            return Found.AFTER;
        }
        return Found.UNKNOWN;
    }

    private static Settlement outcome(InventoryMutationJournalOutcome done, Settlement settlement) {
        return done.isSuccess() ? settlement : Settlement.REFUSED;
    }
}
