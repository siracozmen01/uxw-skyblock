package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * The write-ahead journal of a move between a player's inventory and a vessel's cargo hold.
 *
 * <p>The two belong to different owners: the inventory to the player, written under their session, and
 * the hold to the vessel, written under the vessel's lease. A move is one operation with a participant
 * for each, never two writes one after the other:
 * <ol>
 *   <li>{@link #recordIntent} locks the player's session and then the vessel's lease, checks both
 *       versions, writes the player's inventory as the move found it one version on, and commits the
 *       intent. Nothing moves in the world before it.</li>
 *   <li>The player's inventory changes on their thread, and {@link #markApplied} records it.</li>
 *   <li>{@link #commit} writes the inventory and the hold over their versions in one transaction.</li>
 * </ol>
 *
 * <p>The hold changes only in that commit, so a move cut short never left it changed. Recovery settles
 * the player's side as their session starts: see
 * {@link com.uxplima.uxmskyblock.core.application.inventory.CargoJournalRecovery}.
 */
public interface CargoJournalPort {

    /** The operation type a move is recorded under. */
    String OPERATION_TYPE = "VESSEL_TRANSFER";

    /** The provider a vessel's lease is held under. */
    String LEASE_PROVIDER = "uxm:tradewinds";

    /** A vessel's hold, and the lease epoch this node writes it under. */
    record Hold(IslandId vessel, long leaseEpoch) {
        public Hold {
            Objects.requireNonNull(vessel, "vessel");
        }
    }

    /**
     * A move as its intent records it.
     *
     * @param player whose inventory moves, and the session that writes it
     * @param playerVersion the inventory version the move was computed against
     * @param playerBefore the player's inventory as the move found it
     * @param playerAfter the player's inventory as the move leaves it, serialised
     * @param hold the vessel's hold and the lease it is written under
     * @param cargoVersion the hold version the move was computed against
     * @param cargoBefore the hold as the move found it, serialised
     * @param cargoAfter the hold as the move leaves it, serialised
     */
    @SuppressWarnings("ArrayRecordComponent")
    record Move(
            TradeJournalPort.Holder player,
            long playerVersion,
            byte[] playerBefore,
            byte[] playerAfter,
            Hold hold,
            long cargoVersion,
            byte[] cargoBefore,
            byte[] cargoAfter) {
        public Move {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(hold, "hold");
            playerBefore = Arrays.copyOf(Objects.requireNonNull(playerBefore, "playerBefore"), playerBefore.length);
            playerAfter = Arrays.copyOf(Objects.requireNonNull(playerAfter, "playerAfter"), playerAfter.length);
            cargoBefore = Arrays.copyOf(Objects.requireNonNull(cargoBefore, "cargoBefore"), cargoBefore.length);
            cargoAfter = Arrays.copyOf(Objects.requireNonNull(cargoAfter, "cargoAfter"), cargoAfter.length);
        }

        @Override
        public byte[] playerBefore() {
            return Arrays.copyOf(playerBefore, playerBefore.length);
        }

        @Override
        public byte[] playerAfter() {
            return Arrays.copyOf(playerAfter, playerAfter.length);
        }

        @Override
        public byte[] cargoBefore() {
            return Arrays.copyOf(cargoBefore, cargoBefore.length);
        }

        @Override
        public byte[] cargoAfter() {
            return Arrays.copyOf(cargoAfter, cargoAfter.length);
        }
    }

    /** The player's side of an open move, as recovery reads it. */
    @SuppressWarnings("ArrayRecordComponent")
    record PlayerSide(
            ProfileId profile,
            String beforeFingerprint,
            String afterFingerprint,
            ParticipantApplyState applyState,
            byte[] beforeInventory) {
        public PlayerSide {
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

    /** Records the intent of a move. */
    InventoryMutationJournalOutcome recordIntent(
            InventoryMutationOperationId operationId, ServerNodeId node, Move move, Duration expiry);

    /** Records that side {@code index} of an open move changed: 0 the player, 1 the hold. */
    InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index);

    /**
     * Writes the player's inventory over the version the intent moved it to and the hold over its version,
     * and marks the move {@code COMMITTED}, in one transaction.
     */
    InventoryMutationJournalOutcome commit(InventoryMutationOperationId operationId, ServerNodeId node, Move move);

    /**
     * Marks an open move {@code ABORTED}, once the player's inventory was put back in memory. Refused while
     * the player's durable inventory holds anything but what the move found.
     */
    InventoryMutationJournalOutcome abort(
            InventoryMutationOperationId operationId, ServerNodeId node, TradeJournalPort.Holder player);

    /** The moves still open on {@code profile}'s inventory, oldest first. */
    List<InventoryMutationOperationId> findOpenMoves(ProfileId profile);

    /** The player's side of a move, or empty when there is no such move. */
    Optional<PlayerSide> playerSide(InventoryMutationOperationId operationId);

    /** The state of a move, if there is one. */
    Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId);

    /**
     * Settles an open move under the player's session, as {@code ABORTED} or {@code RECOVERY_REQUIRED}.
     *
     * <p>{@code restore}, when given, is written as the player's inventory over {@code restoreOver}, its
     * durable version now: only while the inventory holds what the move gave it. Aborting without it
     * requires the inventory to hold what the move found. The hold is never written: a move that did not
     * commit never changed it.
     */
    InventoryMutationJournalOutcome settle(
            InventoryMutationOperationId operationId,
            ServerNodeId node,
            TradeJournalPort.Holder player,
            byte @Nullable [] restore,
            long restoreOver,
            InventoryMutationJournalState settledAs);
}
