package com.uxplima.uxmskyblock.core.application.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A move between a player and a vessel's hold writes its intent before the player changes, writes both
 * owners in one commit, and when it cannot finish is aborted before the player is put back.
 *
 * <p>The game mode spec's {@code CrossOwnerEconomicInventoryTransferContractTest} for a TradeWinds hold:
 * every event, the journal's and the player's, goes into one list in the order it happened.
 */
class AMoveIntoTheHoldChangesNothingBeforeItsIntentTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final CargoJournalPort.Hold HOLD = new CargoJournalPort.Hold(IslandId.of(UUID.randomUUID()), 4);

    private final List<String> events = new ArrayList<>();
    private final FakeJournal journal = new FakeJournal();
    private final Sailor ada = new Sailor();
    private final CargoTransfer.Cargo intoTheHold = player -> {
        events.add("work out the hold");
        return Result.ok(bytes("emerald x3"));
    };

    @Test
    @DisplayName("The intent comes first, and the player and the hold are written in one commit")
    void theIntentComesFirst() {
        assertThat(move().isOk()).isTrue();
        assertThat(events)
                .containsExactly("read", "work out the hold", "intent", "apply", "applied 0", "commit", "durable at 9");
        CargoJournalPort.Move recorded = java.util.Objects.requireNonNull(journal.recorded);
        assertThat(new String(recorded.cargoAfter(), StandardCharsets.UTF_8)).isEqualTo("emerald x3");
        assertThat(recorded.playerVersion()).isEqualTo(7);
        assertThat(recorded.cargoVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("A hold that cannot take it, or an intent refused, changes nothing")
    void nothingChangesWithoutAnIntent() {
        CargoTransfer.Cargo full = player -> Result.err("tradewinds.hold.full");
        assertThat(new CargoTransfer(journal, NODE)
                        .move(ada, HOLD, 2, bytes(""), full)
                        .errorOrThrow())
                .isEqualTo("tradewinds.hold.full");
        assertThat(events).containsExactly("read");

        events.clear();
        journal.intent = InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");
        assertThat(move().errorOrThrow()).isEqualTo(TradeExchange.BUSY);
        assertThat(events).doesNotContain("apply");
    }

    @Test
    @DisplayName("A commit refused is aborted before the player is put back")
    void aRefusedCommitIsUndone() {
        journal.commit = InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");

        assertThat(move().errorOrThrow()).isEqualTo(TradeExchange.NOT_SAVED);
        assertThat(events).endsWith("commit", "abort", "put back", "durable at 8");
    }

    @Test
    @DisplayName("A commit unanswered that landed is kept, and nothing is put back")
    void anUnansweredCommitThatLanded() {
        journal.commitThrows = true;
        journal.abort = InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
        journal.state = InventoryMutationJournalState.COMMITTED;

        assertThat(move().isOk()).isTrue();
        assertThat(events).endsWith("commit", "abort", "durable at 9").doesNotContain("put back");
    }

    @Test
    @DisplayName("A move that can be neither aborted nor read takes the player off, whatever changed")
    void neitherAbortedNorRead() {
        ada.applies = false;
        journal.abortThrows = true;
        journal.stateThrows = true;

        assertThat(move().errorOrThrow()).isEqualTo(TradeExchange.IN_DOUBT);
        assertThat(events).endsWith("apply", "abort", "in doubt").doesNotContain("put back");
    }

    private Result<InventoryMutationOperationId, String> move() {
        return new CargoTransfer(journal, NODE).move(ada, HOLD, 2, bytes(""), intoTheHold);
    }

    private static byte[] bytes(String contents) {
        return contents.getBytes(StandardCharsets.UTF_8);
    }

    private final class Sailor implements TradeExchange.LiveSide {
        private final TradeJournalPort.Holder holder =
                new TradeJournalPort.Holder(PlayerUuid.of(UUID.randomUUID()), ProfileId.of(UUID.randomUUID()), 1);
        private boolean applies = true;

        @Override
        public TradeJournalPort.Holder holder() {
            return holder;
        }

        @Override
        public long expectedVersion() {
            return 7;
        }

        @Override
        public Result<TradeExchange.Snapshot, String> read() {
            events.add("read");
            return Result.ok(new TradeExchange.Snapshot(bytes("emerald x3, bread x1"), bytes("bread x1")));
        }

        @Override
        public boolean apply(TradeExchange.Snapshot snapshot) {
            events.add("apply");
            return applies;
        }

        @Override
        public void putBack(TradeExchange.Snapshot snapshot) {
            events.add("put back");
        }

        @Override
        public void durableAt(long version) {
            events.add("durable at " + version);
        }

        @Override
        public void inDoubt() {
            events.add("in doubt");
        }
    }

    private final class FakeJournal implements CargoJournalPort {
        private InventoryMutationJournalOutcome intent = InventoryMutationJournalOutcome.success();
        private InventoryMutationJournalOutcome commit = InventoryMutationJournalOutcome.success();
        private InventoryMutationJournalOutcome abort = InventoryMutationJournalOutcome.success();
        private boolean commitThrows;
        private boolean abortThrows;
        private boolean stateThrows;
        private @Nullable InventoryMutationJournalState state;
        private @Nullable Move recorded;

        @Override
        public InventoryMutationJournalOutcome recordIntent(
                InventoryMutationOperationId operationId, ServerNodeId node, Move move, Duration expiry) {
            events.add("intent");
            recorded = move;
            return intent;
        }

        @Override
        public InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index) {
            events.add("applied " + index);
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome commit(
                InventoryMutationOperationId operationId, ServerNodeId node, Move move) {
            events.add("commit");
            if (commitThrows) {
                throw new IllegalStateException("no answer");
            }
            return commit;
        }

        @Override
        public InventoryMutationJournalOutcome abort(
                InventoryMutationOperationId operationId, ServerNodeId node, TradeJournalPort.Holder player) {
            events.add("abort");
            if (abortThrows) {
                throw new IllegalStateException("no answer");
            }
            return abort;
        }

        @Override
        public List<InventoryMutationOperationId> findOpenMoves(ProfileId profile) {
            return List.of();
        }

        @Override
        public Optional<PlayerSide> playerSide(InventoryMutationOperationId operationId) {
            return Optional.empty();
        }

        @Override
        public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
            if (stateThrows) {
                throw new IllegalStateException("no answer");
            }
            return Optional.ofNullable(state);
        }

        @Override
        public InventoryMutationJournalOutcome settle(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                TradeJournalPort.Holder player,
                byte @Nullable [] restore,
                long restoreOver,
                InventoryMutationJournalState settledAs) {
            return InventoryMutationJournalOutcome.success();
        }
    }
}
