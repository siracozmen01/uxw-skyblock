package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Carries out a move between a player's inventory and a vessel's hold through the {@link CargoJournalPort}.
 *
 * <p>The player is read on their thread and the intent is committed before their inventory changes. The
 * inventory then changes and is recorded as applied, and the inventory and the hold are written in one
 * commit. The hold is the vessel's and lives in durable storage: it is changed by that commit alone. A move
 * that cannot finish is aborted first and the inventory put back after, and one that can be neither
 * aborted nor read leaves the player in doubt, for recovery to settle.
 *
 * <p>The caller holds the player's write lock for the whole move.
 */
public final class CargoTransfer {

    private static final Duration EXPIRY = Duration.ofMinutes(2);

    /** The hold as the move found it and as it leaves it, worked out from what the player gives or takes. */
    @FunctionalInterface
    public interface Cargo {

        /** The hold after the move, given the player's snapshot, or the key of why the hold cannot take it. */
        Result<byte[], String> after(TradeExchange.Snapshot player);
    }

    private final CargoJournalPort journal;
    private final ServerNodeId node;

    public CargoTransfer(CargoJournalPort journal, ServerNodeId node) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.node = Objects.requireNonNull(node, "node");
    }

    /**
     * Moves between {@code player} and the hold that holds {@code cargoBefore} at {@code cargoVersion}, and
     * says whether it happened.
     */
    public Result<InventoryMutationOperationId, String> move(
            TradeExchange.LiveSide player,
            CargoJournalPort.Hold hold,
            long cargoVersion,
            byte[] cargoBefore,
            Cargo cargo) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(cargoBefore, "cargoBefore");
        Objects.requireNonNull(cargo, "cargo");
        Result<TradeExchange.Snapshot, String> read = player.read();
        if (!read.isOk()) {
            return Result.err(read.errorOrThrow());
        }
        TradeExchange.Snapshot snapshot = read.orElseThrow();
        Result<byte[], String> cargoAfter = cargo.after(snapshot);
        if (!cargoAfter.isOk()) {
            return Result.err(cargoAfter.errorOrThrow());
        }
        CargoJournalPort.Move move = new CargoJournalPort.Move(
                player.holder(),
                player.expectedVersion(),
                snapshot.before(),
                snapshot.after(),
                hold,
                cargoVersion,
                cargoBefore,
                cargoAfter.orElseThrow());
        InventoryMutationOperationId operation = InventoryMutationOperationId.random();
        try {
            if (!journal.recordIntent(operation, node, move, EXPIRY).isSuccess()) {
                return Result.err(TradeExchange.BUSY);
            }
        } catch (RuntimeException unanswered) {
            // Nothing has changed. An intent that landed anyway finds the player as it was, and recovery
            // aborts it.
            return Result.err(TradeExchange.BUSY);
        }
        boolean changed = false;
        try {
            if (!player.apply(snapshot)) {
                return undo(operation, player, snapshot, false, TradeExchange.CHANGED);
            }
            changed = true;
            journal.markApplied(operation, 0);
        } catch (RuntimeException failed) {
            return undo(operation, player, snapshot, changed, TradeExchange.CHANGED);
        }
        boolean committed;
        try {
            committed = journal.commit(operation, node, move).isSuccess();
        } catch (RuntimeException unanswered) {
            committed = false;
        }
        if (committed) {
            player.durableAt(player.expectedVersion() + 2);
            return Result.ok(operation);
        }
        return undo(operation, player, snapshot, true, TradeExchange.NOT_SAVED);
    }

    private Result<InventoryMutationOperationId, String> undo(
            InventoryMutationOperationId operation,
            TradeExchange.LiveSide player,
            TradeExchange.Snapshot snapshot,
            boolean changed,
            String why) {
        boolean aborted;
        try {
            aborted = journal.abort(operation, node, player.holder()).isSuccess();
        } catch (RuntimeException unanswered) {
            aborted = false;
        }
        if (aborted) {
            if (changed) {
                player.putBack(snapshot);
            }
            player.durableAt(player.expectedVersion() + 1);
            return Result.err(why);
        }
        if (landed(operation)) {
            player.durableAt(player.expectedVersion() + 2);
            return Result.ok(operation);
        }
        // Neither aborted nor read. The intent moved the player's durable version past the one their
        // session knows, so whatever they do next would never be written: they leave, and recovery settles
        // the move from durable storage when they come back.
        player.inDoubt();
        return Result.err(TradeExchange.IN_DOUBT);
    }

    private boolean landed(InventoryMutationOperationId operation) {
        try {
            return journal.state(operation)
                    .map(InventoryMutationJournalState.COMMITTED::equals)
                    .orElse(false);
        } catch (RuntimeException stillUnanswered) {
            return false;
        }
    }
}
