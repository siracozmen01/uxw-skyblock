package com.uxplima.uxmskyblock.core.application.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * No inventory changes before the trade's intent is written, and a trade that cannot finish leaves every
 * side as it found it.
 *
 * <p>This is the game mode spec's {@code CompoundInventoryIntentPrecedesLiveMutationTest}: every event,
 * the journal's and each side's, goes into one list in the order it happened.
 */
class ATradeChangesNothingBeforeItsIntentIsWrittenTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    private final List<String> events = new ArrayList<>();
    private final FakeJournal journal = new FakeJournal();
    private final Trader ada = new Trader("ada");
    private final Trader bo = new Trader("bo");

    @Test
    @DisplayName("The intent is written before either side changes, and both are written in one commit")
    void theIntentComesFirst() {
        Result<InventoryMutationOperationId, String> done = new TradeExchange(journal, NODE).exchange(List.of(ada, bo));

        assertThat(done.isOk()).isTrue();
        assertThat(events)
                .containsExactly(
                        "read ada",
                        "read bo",
                        "intent",
                        "apply ada",
                        "applied 0",
                        "apply bo",
                        "applied 1",
                        "commit",
                        "committed ada at 8",
                        "committed bo at 4");
    }

    @Test
    @DisplayName("An intent the journal refuses changes no inventory")
    void aRefusedIntentChangesNothing() {
        journal.intent = InventoryMutationJournalOutcome.rejected("STALE_EPOCH");

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.BUSY);
        assertThat(events).containsExactly("read ada", "read bo", "intent");
    }

    @Test
    @DisplayName("A side that cannot trade stops it before anything is written")
    void aSideThatCannotTradeStopsIt() {
        bo.read = Result.err("trade.no_room");

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo("trade.no_room");
        assertThat(events).containsExactly("read ada", "read bo");
    }

    @Test
    @DisplayName("A side that changed since it was read puts back the side already traded, and the trade is aborted")
    void aChangedSidePutsTheOtherBack() {
        bo.applies = false;

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.CHANGED);
        assertThat(events)
                .containsExactly(
                        "read ada", "read bo", "intent", "apply ada", "applied 0", "apply bo", "abort", "put back ada");
    }

    @Test
    @DisplayName("A refused commit puts both sides back, and the trade is aborted")
    void aRefusedCommitPutsBothBack() {
        journal.commit = InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.NOT_SAVED);
        assertThat(events).endsWith("commit", "abort", "put back ada", "put back bo");
    }

    @Test
    @DisplayName("A commit the database never answered but that landed is a trade that happened")
    void anUnansweredCommitThatLanded() {
        journal.commitThrows = true;
        journal.landed = InventoryMutationJournalState.COMMITTED;
        journal.abort = InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).isOk())
                .isTrue();
        assertThat(events).endsWith("commit", "abort", "committed ada at 8", "committed bo at 4");
        assertThat(events).noneMatch(event -> event.startsWith("put back"));
    }

    @Test
    @DisplayName("A trade that can be neither aborted nor read puts nothing back and leaves its sides in doubt")
    void aTradeNobodyCanReadIsInDoubt() {
        journal.commitThrows = true;
        journal.abortThrows = true;
        journal.stateThrows = true;

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.IN_DOUBT);
        assertThat(events).endsWith("commit", "abort", "in doubt ada", "in doubt bo");
        assertThat(events).noneMatch(event -> event.startsWith("put back"));
    }

    @Test
    @DisplayName("A journal that fails after a side changed calls the trade off and puts the side back")
    void aFailureAfterASideChangedPutsItBack() {
        journal.markAppliedThrows = true;

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.CHANGED);
        assertThat(events).endsWith("apply ada", "applied 0", "abort", "put back ada");
        assertThat(events).doesNotContain("apply bo");
    }

    @Test
    @DisplayName("An intent the database never answered changes no inventory")
    void anUnansweredIntentChangesNothing() {
        journal.intentThrows = true;

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.BUSY);
        assertThat(events).containsExactly("read ada", "read bo", "intent");
    }

    @Test
    @DisplayName("A commit the database never answered and that did not land puts both sides back")
    void anUnansweredCommitThatDidNotLand() {
        journal.commitThrows = true;

        assertThat(new TradeExchange(journal, NODE).exchange(List.of(ada, bo)).errorOrThrow())
                .isEqualTo(TradeExchange.NOT_SAVED);
        assertThat(events).endsWith("commit", "abort", "put back ada", "put back bo");
    }

    private final class Trader implements TradeExchange.LiveSide {
        private final String name;
        private final TradeJournalPort.Holder holder =
                new TradeJournalPort.Holder(PlayerUuid.of(UUID.randomUUID()), ProfileId.of(UUID.randomUUID()), 1);
        private Result<TradeExchange.Snapshot, String> read;
        private boolean applies = true;

        Trader(String name) {
            this.name = name;
            this.read = Result.ok(new TradeExchange.Snapshot(bytes(name + " before"), bytes(name + " after")));
        }

        @Override
        public TradeJournalPort.Holder holder() {
            return holder;
        }

        @Override
        public long expectedVersion() {
            return name.equals("ada") ? 7 : 3;
        }

        @Override
        public Result<TradeExchange.Snapshot, String> read() {
            events.add("read " + name);
            return read;
        }

        @Override
        public boolean apply(TradeExchange.Snapshot snapshot) {
            events.add("apply " + name);
            return applies;
        }

        @Override
        public void putBack(TradeExchange.Snapshot snapshot) {
            events.add("put back " + name);
        }

        @Override
        public void committed(long version) {
            events.add("committed " + name + " at " + version);
        }

        @Override
        public void inDoubt() {
            events.add("in doubt " + name);
        }
    }

    private final class FakeJournal implements TradeJournalPort {
        private InventoryMutationJournalOutcome intent = InventoryMutationJournalOutcome.success();
        private InventoryMutationJournalOutcome commit = InventoryMutationJournalOutcome.success();
        private InventoryMutationJournalOutcome abort = InventoryMutationJournalOutcome.success();
        private boolean intentThrows;
        private boolean markAppliedThrows;
        private boolean commitThrows;
        private boolean abortThrows;
        private boolean stateThrows;
        private @Nullable InventoryMutationJournalState landed;

        @Override
        public InventoryMutationJournalOutcome recordIntent(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                List<Side> sides,
                String payload,
                Duration expiry) {
            events.add("intent");
            if (intentThrows) {
                throw new IllegalStateException("the database did not answer");
            }
            return intent;
        }

        @Override
        public InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index) {
            events.add("applied " + index);
            if (markAppliedThrows) {
                throw new IllegalStateException("the database did not answer");
            }
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome commit(
                InventoryMutationOperationId operationId, ServerNodeId node, List<Outcome> sides) {
            events.add("commit");
            if (commitThrows) {
                throw new IllegalStateException("the database did not answer");
            }
            return commit;
        }

        @Override
        public InventoryMutationJournalOutcome abort(
                InventoryMutationOperationId operationId, ServerNodeId node, List<Holder> holders) {
            events.add("abort");
            if (abortThrows) {
                throw new IllegalStateException("the database did not answer");
            }
            return abort;
        }

        @Override
        public List<InventoryMutationOperationId> findOpenTrades(ProfileId profile) {
            return List.of();
        }

        @Override
        public List<Participant> participants(InventoryMutationOperationId operationId) {
            return List.of();
        }

        @Override
        public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
            if (stateThrows) {
                throw new IllegalStateException("the database did not answer");
            }
            return Optional.ofNullable(landed);
        }

        @Override
        public InventoryMutationJournalOutcome settleSide(
                InventoryMutationOperationId operationId,
                int index,
                ServerNodeId node,
                Holder holder,
                byte @Nullable [] restore,
                long restoreOver,
                boolean closeTrade) {
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome settleTrade(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                Holder holder,
                InventoryMutationJournalState settledAs) {
            return InventoryMutationJournalOutcome.success();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
