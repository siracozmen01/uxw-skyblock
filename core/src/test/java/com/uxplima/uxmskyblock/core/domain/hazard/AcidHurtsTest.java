package com.uxplima.uxmskyblock.core.domain.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The acid sea and acid rain hurt by the operator's numbers, and what protects from each protects. */
class AcidHurtsTest {

    private final AcidRules rules = new AcidRules(3.0, 1.5, true, Duration.ofSeconds(1));

    @Test
    @DisplayName("Dry and under a roof, nothing is taken")
    void nothingReaches() {
        assertThat(rules.damage(AcidExposure.NONE)).isZero();
    }

    @Test
    @DisplayName("The sea takes its damage, and an effect the operator names keeps the player safe in it")
    void theSea() {
        assertThat(rules.damage(new AcidExposure(true, false, false, false))).isEqualTo(3.0);
        assertThat(rules.damage(new AcidExposure(true, false, true, true))).isZero();
        assertThat(rules.seaHurts(new AcidExposure(true, false, false, false))).isTrue();
        assertThat(rules.seaHurts(new AcidExposure(true, false, false, true))).isFalse();
    }

    @Test
    @DisplayName("The rain takes its damage, a helmet keeps it off, and a helmet does nothing in the sea")
    void theRain() {
        assertThat(rules.damage(new AcidExposure(false, true, false, false))).isEqualTo(1.5);
        assertThat(rules.damage(new AcidExposure(false, true, true, false))).isZero();
        assertThat(rules.damage(new AcidExposure(true, true, true, false))).isEqualTo(3.0);
        assertThat(rules.damage(new AcidExposure(true, true, false, false))).isEqualTo(4.5);
    }

    @Test
    @DisplayName("Where the operator says a helmet does not help, the rain still falls on one")
    void aHelmetThatDoesNotHelp() {
        AcidRules bareRain = new AcidRules(3.0, 1.5, false, Duration.ofSeconds(1));

        assertThat(bareRain.damage(new AcidExposure(false, true, true, false))).isEqualTo(1.5);
    }

    @Test
    @DisplayName("Negative damage and a check faster than a tick are refused")
    void nonsenseIsRefused() {
        assertThatThrownBy(() -> new AcidRules(-1, 0, true, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AcidRules(1, 1, true, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
