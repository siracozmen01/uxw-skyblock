package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** An amount is read the way a player writes one, and anything that is not a positive whole amount is refused. */
class BankAmountTest {

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "2500 | 2500",
                "  42  | 42",
                "$750 | 750",
                "2,500 | 2500",
                "1 000 000 | 1000000",
                "10_000 | 10000",
                "2.5k | 2500",
                "2.5K | 2500",
                "1m | 1000000",
                "1.25m | 1250000",
                "3b | 3000000000",
                "0.001k | 1"
            })
    @DisplayName("An amount written as a player writes it is the whole amount it means")
    void readsWhatAPlayerWrites(String written, long amount) {
        assertThat(BankAmount.parse(written)).hasValue(amount);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "",
                "lots",
                "0",
                "-5",
                "2.5",
                "1.0005k",
                "1kk",
                "k",
                "1,,000",
                "1e3",
                "9999999999999999999",
                "9999999999b",
                "12 apples"
            })
    @DisplayName("Anything that is not a positive whole amount is not an amount")
    void refusesTheRest(String written) {
        assertThat(BankAmount.parse(written)).isEmpty();
    }
}
