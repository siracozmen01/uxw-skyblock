package com.uxplima.uxmskyblock.persistence.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * One currency the operator listed, kept in an island bank on any engine: put in, taken out, refused past what is
 * there, answered once per key, read back beside the bank's own columns, and refused under a name it may not take.
 */
final class HeldCurrencies {

    private HeldCurrencies() {}

    /** Runs the whole story against a bank already created for {@code island}, at version 1. */
    static void areKept(PlayerIslandBankAdapter adapter, IslandId island, UUID actor, String node, long epoch) {
        BankTransactionOutcome first =
                adapter.executeTransaction(island, actor, "experience", 0, 50L, "Put in", node, epoch, 1L, op(), "h1");
        assertThat(first).isInstanceOf(BankTransactionOutcome.Success.class);
        IslandBank afterFirst = ((BankTransactionOutcome.Success) first).updatedBank();
        assertThat(afterFirst.heldOf("experience")).isEqualTo(50L);
        assertThat(afterFirst.primaryBalanceMinorUnits()).isZero();
        assertThat(afterFirst.version()).isEqualTo(2L);

        assertThat(adapter.executeTransaction(
                        island, actor, "experience", 0, 25L, "Put in", node, epoch, 2L, op(), "h2"))
                .isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(adapter.executeTransaction(
                        island, actor, "experience", 0, -100L, "Take out", node, epoch, 3L, op(), "h3"))
                .isEqualTo(new BankTransactionOutcome.InsufficientFunds(75L, -100L));
        assertThat(adapter.executeTransaction(
                        island, actor, "experience", 0, 25L, "Put in", node, epoch, 3L, op(), "h2"))
                .describedAs("the same key is the same deposit")
                .isInstanceOf(BankTransactionOutcome.DuplicateOperation.class);
        assertThat(adapter.executeTransaction(island, actor, "points", 0, 7L, "Put in", node, epoch, 3L, op(), "h4"))
                .isInstanceOf(BankTransactionOutcome.Success.class);

        IslandBank read = adapter.findBankByIslandId(island).orElseThrow();
        assertThat(read.heldOf("experience")).isEqualTo(75L);
        assertThat(read.heldOf("points")).isEqualTo(7L);
        assertThat(read.heldOf("diamonds")).isZero();
        assertThat(read.version()).isEqualTo(4L);

        BankTransactionOutcome emptied = adapter.executeTransaction(
                island, actor, "experience", 0, -75L, "Take out", node, epoch, 4L, op(), "h5");
        assertThat(((BankTransactionOutcome.Success) emptied).updatedBank().heldOf("experience"))
                .isZero();
        // Every move here lands within one second, which an engine that keeps whole seconds cannot order by time:
        // the history is read by the version each move reached, so the newest is the last one made.
        assertThat(adapter.getTransactionHistory(island, 5))
                .extracting(BankTransaction::currencyId, BankTransaction::deltaAmountMinorUnits)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("experience", -75L),
                        org.assertj.core.groups.Tuple.tuple("points", 7L),
                        org.assertj.core.groups.Tuple.tuple("experience", 25L),
                        org.assertj.core.groups.Tuple.tuple("experience", 50L));

        for (String refused : new String[] {"Not An Id", "vault"}) {
            assertThatThrownBy(() -> adapter.executeTransaction(
                            island, actor, refused, 0, 1L, "Put in", node, epoch, 5L, op(), "h-" + refused))
                    .describedAs(refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static UUID op() {
        return UUID.randomUUID();
    }
}
