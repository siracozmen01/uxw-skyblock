package com.uxplima.uxmskyblock.core.application.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The island's own money and the operator's currencies keep their names apart on the way to the bank. */
class BankCurrenciesTest {

    @Test
    @DisplayName("The island's money is the primary column in cents, under the name every older saga carries")
    void theIslandsMoney() {
        assertThat(BankCurrencies.columnOf("VAULT")).isEqualTo("PRIMARY");
        assertThat(BankCurrencies.scaleOf("vault")).isEqualTo(2);
        assertThat(BankCurrencies.isPrimary("PRIMARY")).isTrue();
    }

    @Test
    @DisplayName("An operator currency is its own column, in whole units, under its id in lower case")
    void anOperatorCurrency() {
        assertThat(BankCurrencies.columnOf("Experience")).isEqualTo("experience");
        assertThat(BankCurrencies.scaleOf("experience")).isZero();
        assertThat(BankCurrencies.isPrimary("experience")).isFalse();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "primary",
                "crystals",
                "exp",
                "vault",
                "Gems",
                "two words",
                "",
                "a23456789012345678901234567890123"
            })
    @DisplayName("A name the bank keeps for itself, or one it cannot store, is no operator currency")
    void namesTheBankCannotKeep(String id) {
        assertThat(BankCurrencies.isHeld(id)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"experience", "points", "gems_2", "token-a"})
    @DisplayName("A short lower case id is an operator currency")
    void namesTheBankKeeps(String id) {
        assertThat(BankCurrencies.isHeld(id)).isTrue();
    }

    @Test
    @DisplayName("A bank reads what it holds of a currency, and zero of one it never held")
    void aBankReadsWhatItHolds() {
        IslandBank bank =
                new IslandBank(IslandId.of(UUID.randomUUID()), 0L, 0L, 0L, 1L, Instant.now(), Map.of("points", 12L));

        assertThat(bank.heldOf("points")).isEqualTo(12L);
        assertThat(bank.heldOf("gems")).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new IslandBank(
                        IslandId.of(UUID.randomUUID()), 0L, 0L, 0L, 1L, Instant.now(), Map.of("points", -1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
