package com.uxplima.uxmskyblock.core.domain.durability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerStateDurabilityConfigTest {

    @Test
    @DisplayName("defaultPolicy() provides HYBRID durability mode and 60-second ambient checkpoint interval")
    void defaultPolicyHasFrozenDefaults() {
        PlayerStateDurabilityConfig config = PlayerStateDurabilityConfig.defaultPolicy();

        assertThat(config.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
        assertThat(config.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("accepts and preserves valid positive ambient checkpoint intervals")
    void acceptsAndPreservesConfiguredDuration() {
        PlayerStateDurabilityConfig config30s = PlayerStateDurabilityConfig.of(Duration.ofSeconds(30));
        assertThat(config30s.ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config30s.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);

        PlayerStateDurabilityConfig config2m =
                PlayerStateDurabilityConfig.of(DurabilityMode.HYBRID, Duration.ofMinutes(2));
        assertThat(config2m.ambientCheckpointInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config2m.durabilityMode()).isEqualTo(DurabilityMode.HYBRID);
    }

    @Test
    @DisplayName("rejects non-positive ambient checkpoint intervals (zero or negative)")
    void rejectsNonPositiveDuration() {
        assertThatThrownBy(() -> PlayerStateDurabilityConfig.of(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> PlayerStateDurabilityConfig.of(Duration.ofSeconds(-10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("rejects null arguments")
    @SuppressWarnings("NullAway")
    void rejectsNullArguments() {
        assertThatThrownBy(() -> new PlayerStateDurabilityConfig(null, Duration.ofSeconds(60)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("durabilityMode");

        assertThatThrownBy(() -> new PlayerStateDurabilityConfig(DurabilityMode.HYBRID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ambientCheckpointInterval");
    }

    @Test
    @DisplayName("satisfies value equality and hashCode contracts as an immutable record")
    void satisfiesValueEqualityAndHashCode() {
        PlayerStateDurabilityConfig a1 = PlayerStateDurabilityConfig.of(Duration.ofSeconds(45));
        PlayerStateDurabilityConfig a2 = PlayerStateDurabilityConfig.of(Duration.ofSeconds(45));
        PlayerStateDurabilityConfig b = PlayerStateDurabilityConfig.of(Duration.ofSeconds(90));

        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
        assertThat(a1).isNotEqualTo(b);
        assertThat(a1.hashCode()).isNotEqualTo(b.hashCode());
    }
}
