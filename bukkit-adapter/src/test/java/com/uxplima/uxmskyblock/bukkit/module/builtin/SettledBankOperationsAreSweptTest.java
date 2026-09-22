package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bank sweeps the idempotency records it writes.
 *
 * <p>Every deposit, withdrawal, upgrade purchase, shop trade and upkeep charge writes one so a retry
 * of it is answered rather than applied twice. Nothing ever deleted one, so the table held every
 * money movement a server had ever made, and the shop made it busier again.
 */
class SettledBankOperationsAreSweptTest {

    /** Writes down the cutoffs it was asked about, and can be made to throw. */
    private static class RecordingBankPort implements IslandBankPort {
        final List<Instant> cutoffs = new ArrayList<>();
        boolean throwing;

        @Override
        public java.util.Optional<IslandBank> findBankByIslandId(IslandId islandId) {
            return java.util.Optional.empty();
        }

        @Override
        public IslandBank createBank(IslandId islandId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BankTransactionOutcome executeTransaction(
                IslandId islandId,
                java.util.UUID actorUuid,
                String currencyId,
                int currencyScale,
                long deltaMinorUnits,
                String reason,
                String nodeId,
                long expectedEpoch,
                long expectedVersion,
                java.util.UUID operationId,
                String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
            return List.of();
        }

        @Override
        public int purgeSettledOperationsBefore(Instant before) {
            if (throwing) {
                throw new IllegalStateException("the database is gone");
            }
            cutoffs.add(before);
            return 3;
        }
    }

    @Test
    @DisplayName("The sweep asks for everything settled before the retention behind now")
    void theSweepUsesTheRetention() {
        RecordingBankPort port = new RecordingBankPort();
        BankModule module = new BankModule(
                mock(IslandBankService.class),
                port,
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort(),
                Duration.ofDays(30));

        Instant before = Instant.now();
        assertThat(module.sweepSettledOperations()).describedAs("records swept").isEqualTo(3);

        assertThat(port.cutoffs).hasSize(1);
        assertThat(port.cutoffs.get(0))
                .describedAs("thirty days behind the moment of the sweep")
                .isBetween(
                        before.minus(Duration.ofDays(30)).minusSeconds(5),
                        Instant.now().minus(Duration.ofDays(30)).plusSeconds(5));
    }

    @Test
    @DisplayName("A sweep that throws does not stop the bank")
    void afailingSweepIsSurvived() {
        RecordingBankPort port = new RecordingBankPort();
        port.throwing = true;
        BankModule module = new BankModule(
                mock(IslandBankService.class),
                port,
                new com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort(),
                Duration.ofDays(30));

        assertThat(module.sweepSettledOperations())
                .describedAs("nothing swept, and no exception out")
                .isZero();
    }

    @Test
    @DisplayName("A node with no port to sweep carries on without one")
    void noPortIsNotAFailure() {
        BankModule module = new BankModule(mock(IslandBankService.class));

        assertThat(module.sweepSettledOperations()).isZero();
    }

    @Test
    @DisplayName("A retention of nothing is refused rather than accepted")
    void aNegativeRetentionIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new BankModule(mock(IslandBankService.class), null, null, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
