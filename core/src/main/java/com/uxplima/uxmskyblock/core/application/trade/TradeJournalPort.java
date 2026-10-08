package com.uxplima.uxmskyblock.core.application.trade;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * The write-ahead journal of a trade between players: one operation, one participant per player.
 *
 * <p>Each player's inventory is written under that player's own session, and a trade changes two of
 * them. Two journaled mutations committed one after the other would leave one player paid and the
 * other not whenever the second failed, so a trade is one operation with a participant for each side:
 * <ol>
 *   <li>{@link #recordIntent} locks every side's session in one canonical order, checks each one and its
 *       inventory version, and commits the intent. Nothing in any inventory moves before it.</li>
 *   <li>Each side's inventory changes on that player's own thread, and {@link #markApplied} records it.</li>
 *   <li>{@link #commit} locks the sessions again and writes every side's inventory over its expected
 *       version in one transaction.</li>
 * </ol>
 *
 * <p>A trade cut short is settled side by side, each under its own player's session: see
 * {@link TradeJournalRecovery}. A side keeps the inventory it had before the trade, so it can be put
 * back without guessing.
 */
public interface TradeJournalPort {

    /** The operation type a trade is recorded under. */
    String OPERATION_TYPE = "PLAYER_TRADE";

    /** A player's session, as a phase needs it to show it may still write. */
    record Holder(PlayerUuid player, ProfileId profile, long sessionEpoch) {
        public Holder {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * One side of a trade as the intent records it.
     *
     * @param holder whose inventory this is
     * @param expectedVersion the inventory version the trade was computed against
     * @param beforeFingerprint the inventory as the trade found it
     * @param afterFingerprint the inventory as the trade leaves it
     * @param beforeInventory the inventory as the trade found it, kept so recovery can put it back
     */
    @SuppressWarnings("ArrayRecordComponent")
    record Side(
            Holder holder,
            long expectedVersion,
            String beforeFingerprint,
            String afterFingerprint,
            byte[] beforeInventory) {
        public Side {
            Objects.requireNonNull(holder, "holder");
            Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
            Objects.requireNonNull(afterFingerprint, "afterFingerprint");
            beforeInventory =
                    Arrays.copyOf(Objects.requireNonNull(beforeInventory, "beforeInventory"), beforeInventory.length);
        }

        @Override
        public byte[] beforeInventory() {
            return Arrays.copyOf(beforeInventory, beforeInventory.length);
        }
    }

    /** One side's inventory as the trade leaves it, written by {@link #commit}. */
    @SuppressWarnings("ArrayRecordComponent")
    record Outcome(Holder holder, long expectedVersion, byte[] inventory) {
        public Outcome {
            Objects.requireNonNull(holder, "holder");
            inventory = Arrays.copyOf(Objects.requireNonNull(inventory, "inventory"), inventory.length);
        }

        @Override
        public byte[] inventory() {
            return Arrays.copyOf(inventory, inventory.length);
        }
    }

    /** A participant of an open trade, as recovery reads it. */
    @SuppressWarnings("ArrayRecordComponent")
    record Participant(
            int index,
            ProfileId profile,
            long expectedVersion,
            String beforeFingerprint,
            String afterFingerprint,
            ParticipantApplyState applyState,
            byte[] beforeInventory) {
        public Participant {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
            Objects.requireNonNull(afterFingerprint, "afterFingerprint");
            Objects.requireNonNull(applyState, "applyState");
            beforeInventory =
                    Arrays.copyOf(Objects.requireNonNull(beforeInventory, "beforeInventory"), beforeInventory.length);
        }

        @Override
        public byte[] beforeInventory() {
            return Arrays.copyOf(beforeInventory, beforeInventory.length);
        }
    }

    /**
     * Records the intent of a trade, every side at once, in index order.
     *
     * <p>The sessions are locked in the order of their player ids read as unsigned 128-bit numbers, so
     * two trades over the same players never wait on each other.
     */
    InventoryMutationJournalOutcome recordIntent(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            List<Side> sides,
            String payload,
            Duration expiry);

    /** Records that side {@code index} of an open trade changed in memory. */
    InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index);

    /**
     * Writes every side's inventory over its expected version and marks the trade {@code COMMITTED}, in
     * one transaction. A side whose session or version moved refuses the whole trade.
     */
    InventoryMutationJournalOutcome commit(
            InventoryMutationOperationId operationId, ServerNodeId node, List<Outcome> sides);

    /**
     * Marks an open trade {@code ABORTED}, once every side was put back in memory. Every holder's session
     * must still be this node's; a session that ended does not stop the abort.
     */
    InventoryMutationJournalOutcome abort(
            InventoryMutationOperationId operationId, ServerNodeId node, List<Holder> holders);

    /** The trades still open on {@code profile}'s inventory, oldest first. */
    List<InventoryMutationOperationId> findOpenTrades(ProfileId profile);

    /** Every participant of a trade, in index order, or empty when there is no such trade. */
    List<Participant> participants(InventoryMutationOperationId operationId);

    /** The state of a trade, if there is one. */
    Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId);

    /**
     * Settles one side of an open trade under that side's own session.
     *
     * <p>{@code restore}, when given, is written as the side's inventory over {@code restoreOver}, its
     * durable version now. The side is then {@code REVERTED}. With {@code closeTrade} the whole trade is
     * {@code ABORTED} with it: recovery passes it when no side holds the trade's outcome any longer.
     */
    InventoryMutationJournalOutcome settleSide(
            InventoryMutationOperationId operationId,
            int index,
            ServerNodeId node,
            Holder holder,
            byte @Nullable [] restore,
            long restoreOver,
            boolean closeTrade);

    /**
     * Settles a whole open trade as {@code COMMITTED}, every side holding its outcome, or
     * {@code RECOVERY_REQUIRED}, a side holding something the trade cannot account for. Neither writes
     * an inventory.
     */
    InventoryMutationJournalOutcome settleTrade(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            Holder holder,
            InventoryMutationJournalState settledAs);
}
