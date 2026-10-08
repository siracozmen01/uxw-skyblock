package com.uxplima.uxmskyblock.core.application.trade;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Carries out a trade both players agreed to, through the {@link TradeJournalPort}.
 *
 * <p>Every side is read first, on its player's thread, and the intent is committed before any inventory
 * changes: nothing a player could see moves until a crash can be recovered from. Each side then changes
 * and is recorded as applied, and both inventories are written in one commit. When a side cannot change,
 * or the commit is refused, the trade is aborted and every side that changed is put back in memory.
 *
 * <p>The caller holds every side's write lock for the whole exchange, so no checkpoint writes a side
 * between the abort and its being put back.
 *
 * <p>An error is the key of the message a player is told, so the window that asked can say why.
 */
public final class TradeExchange {

    /** How long an intent stands before it is anybody's to recover. */
    private static final Duration EXPIRY = Duration.ofMinutes(2);

    /** The trade could not start: a side was taken or its inventory moved since it was read. */
    public static final String BUSY = "trade.busy";

    /** A side changed between the reading and the trade. */
    public static final String CHANGED = "trade.changed";

    /** The database refused the trade; nothing was given or taken. */
    public static final String NOT_SAVED = "trade.not_saved";

    /** Nobody can tell whether the trade was written; recovery decides when the players come back. */
    public static final String IN_DOUBT = "trade.in_doubt";

    /** One player's inventory, touched only on that player's thread. */
    public interface LiveSide {

        /** Whose inventory this is, and the session that writes it. */
        TradeJournalPort.Holder holder();

        /** The inventory version the side's session last wrote. */
        long expectedVersion();

        /** What the side holds now and what the trade would leave it, or the key of why it cannot trade. */
        Result<Snapshot, String> read();

        /** Leaves the side as the trade does, if it still holds what {@link #read} found. */
        boolean apply(Snapshot snapshot);

        /** Puts the side back as {@link #read} found it. */
        void putBack(Snapshot snapshot);

        /** The trade is written: the side's inventory is at {@code version} now. */
        void committed(long version);

        /**
         * Nobody can tell whether the trade was written. What the side holds now must not be played
         * with or written: the player is taken off the server, and recovery settles the side from
         * durable storage when they come back.
         */
        void inDoubt();
    }

    /** A side's inventory before the trade and after it, serialised. */
    @SuppressWarnings("ArrayRecordComponent")
    public record Snapshot(byte[] before, byte[] after) {
        public Snapshot {
            before = Arrays.copyOf(Objects.requireNonNull(before, "before"), before.length);
            after = Arrays.copyOf(Objects.requireNonNull(after, "after"), after.length);
        }

        @Override
        public byte[] before() {
            return Arrays.copyOf(before, before.length);
        }

        @Override
        public byte[] after() {
            return Arrays.copyOf(after, after.length);
        }
    }

    private final TradeJournalPort journal;
    private final ServerNodeId node;

    public TradeExchange(TradeJournalPort journal, ServerNodeId node) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.node = Objects.requireNonNull(node, "node");
    }

    /** Carries out the trade between {@code sides}, and says whether it happened. */
    public Result<InventoryMutationOperationId, String> exchange(List<? extends LiveSide> sides) {
        Objects.requireNonNull(sides, "sides");
        if (sides.size() < 2) {
            throw new IllegalArgumentException("A trade has at least two sides");
        }
        List<Snapshot> snapshots = new ArrayList<>(sides.size());
        List<TradeJournalPort.Side> recorded = new ArrayList<>(sides.size());
        for (LiveSide side : sides) {
            Result<Snapshot, String> read = side.read();
            if (!read.isOk()) {
                return Result.err(read.errorOrThrow());
            }
            Snapshot snapshot = read.orElseThrow();
            snapshots.add(snapshot);
            recorded.add(new TradeJournalPort.Side(
                    side.holder(),
                    side.expectedVersion(),
                    InventoryFingerprint.of(snapshot.before()),
                    InventoryFingerprint.of(snapshot.after()),
                    snapshot.before()));
        }
        InventoryMutationOperationId trade = InventoryMutationOperationId.random();
        try {
            if (!journal.recordIntent(trade, node, recorded, "{}", EXPIRY).isSuccess()) {
                return Result.err(BUSY);
            }
        } catch (RuntimeException unanswered) {
            // Nothing has changed. An intent that landed anyway finds every side as it was, and
            // recovery aborts it.
            return Result.err(BUSY);
        }
        List<Integer> changed = new ArrayList<>(sides.size());
        try {
            for (int index = 0; index < sides.size(); index++) {
                if (!sides.get(index).apply(snapshots.get(index))) {
                    return undo(trade, sides, snapshots, changed, CHANGED);
                }
                changed.add(index);
                journal.markApplied(trade, index);
            }
        } catch (RuntimeException failed) {
            return undo(trade, sides, snapshots, changed, CHANGED);
        }
        List<TradeJournalPort.Outcome> outcomes = new ArrayList<>(sides.size());
        for (int index = 0; index < sides.size(); index++) {
            LiveSide side = sides.get(index);
            outcomes.add(new TradeJournalPort.Outcome(
                    side.holder(), side.expectedVersion(), snapshots.get(index).after()));
        }
        boolean committed;
        try {
            committed = journal.commit(trade, node, outcomes).isSuccess();
        } catch (RuntimeException unanswered) {
            // A commit the database never answered may still land. The abort below takes the
            // trade's row after it, and tells which of the two happened.
            committed = false;
        }
        if (committed) {
            return done(trade, sides);
        }
        return undo(trade, sides, snapshots, changed, NOT_SAVED);
    }

    private Result<InventoryMutationOperationId, String> done(
            InventoryMutationOperationId trade, List<? extends LiveSide> sides) {
        for (LiveSide side : sides) {
            side.committed(side.expectedVersion() + 1);
        }
        return Result.ok(trade);
    }

    /**
     * Calls the trade off. The journal is aborted first and the sides put back only once it is: a side
     * put back while the trade could still commit would hold what the database says it gave away.
     * A trade the abort finds committed happened. One that can be neither aborted nor read leaves the
     * sides that changed in doubt, and their players are taken off the server so that recovery, not
     * what they hold now, decides.
     */
    private Result<InventoryMutationOperationId, String> undo(
            InventoryMutationOperationId trade,
            List<? extends LiveSide> sides,
            List<Snapshot> snapshots,
            List<Integer> changed,
            String why) {
        List<TradeJournalPort.Holder> holders = new ArrayList<>(sides.size());
        for (LiveSide side : sides) {
            holders.add(side.holder());
        }
        boolean aborted;
        try {
            aborted = journal.abort(trade, node, holders).isSuccess();
        } catch (RuntimeException unanswered) {
            aborted = false;
        }
        if (aborted) {
            for (int index : changed) {
                sides.get(index).putBack(snapshots.get(index));
            }
            return Result.err(why);
        }
        if (landed(trade)) {
            return done(trade, sides);
        }
        for (int index : changed) {
            sides.get(index).inDoubt();
        }
        return Result.err(changed.isEmpty() ? why : IN_DOUBT);
    }

    private boolean landed(InventoryMutationOperationId trade) {
        try {
            return journal.state(trade)
                    .map(InventoryMutationJournalState.COMMITTED::equals)
                    .orElse(false);
        } catch (RuntimeException stillUnanswered) {
            return false;
        }
    }
}
