package com.uxplima.uxmskyblock.core.domain.warp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WarpNameTest {

    @Test
    @DisplayName("Valid warp name is normalized to lowercase")
    void validWarpNameNormalized() {
        WarpName name = WarpName.of("Main_Spawn-1");
        assertThat(name.value()).isEqualTo("main_spawn-1");
        assertThat(name.toString()).isEqualTo("main_spawn-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"market", "farm_1", "parkour-hard", "A", "12345678901234567890123456789012"})
    @DisplayName("Accepted valid warp names")
    void acceptedNames(String input) {
        WarpName name = WarpName.of(input);
        assertThat(name.value()).isEqualTo(input.toLowerCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "   ",
                "name with spaces",
                "warp!",
                "warp#1",
                "too_long_name_exceeding_32_characters_limit"
            })
    @DisplayName("Invalid warp names are rejected")
    void invalidNamesRejected(String invalid) {
        assertThatThrownBy(() -> WarpName.of(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
