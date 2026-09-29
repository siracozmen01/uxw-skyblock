package com.uxplima.uxmskyblock.core.domain.hazard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Water is a Poseidon player's air: dry air hurts, the sun hurts more, rain keeps them wet, and a
 * swimmer who stays in one spot too long is hurt by the water too.
 */
class PoseidonAirHurtsTest {

    private final PoseidonRules rules =
            new PoseidonRules(1.0, 3.0, 2.5, Duration.ofSeconds(5), 1.0, true, Duration.ofSeconds(1));

    @Test
    @DisplayName("A moving swimmer is safe, and one who stays still past the limit is hurt")
    void inTheWater() {
        assertThat(rules.damage(swimming(Duration.ofSeconds(4)))).isZero();
        assertThat(rules.harm(swimming(Duration.ofSeconds(5)))).isEqualTo(PoseidonRules.Harm.STILL);
        assertThat(rules.damage(swimming(Duration.ofSeconds(5)))).isEqualTo(2.5);
    }

    @Test
    @DisplayName("Dry air hurts, the sun adds its own, and rain keeps the player wet while the operator counts it")
    void outOfTheWater() {
        assertThat(rules.damage(dry(false, false))).isEqualTo(1.0);
        assertThat(rules.harm(dry(false, false))).isEqualTo(PoseidonRules.Harm.DRY);
        assertThat(rules.damage(dry(false, true))).isEqualTo(4.0);
        assertThat(rules.harm(dry(false, true))).isEqualTo(PoseidonRules.Harm.SUN);
        assertThat(rules.damage(dry(true, true))).isZero();

        PoseidonRules dryRain =
                new PoseidonRules(1.0, 3.0, 2.5, Duration.ofSeconds(5), 1.0, false, Duration.ofSeconds(1));
        assertThat(dryRain.damage(dry(true, false))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A damage the operator sets to zero is no harm at all")
    void zeroIsNoHarm() {
        PoseidonRules gentle = new PoseidonRules(0, 0, 0, Duration.ZERO, 1.0, true, Duration.ofSeconds(1));

        assertThat(gentle.harm(dry(false, true))).isEqualTo(PoseidonRules.Harm.NONE);
        assertThat(gentle.harm(swimming(Duration.ofMinutes(1)))).isEqualTo(PoseidonRules.Harm.NONE);
        PoseidonRules noSun = new PoseidonRules(1.0, 0, 0, Duration.ZERO, 1.0, true, Duration.ofSeconds(1));
        assertThat(noSun.harm(dry(false, true))).isEqualTo(PoseidonRules.Harm.DRY);
    }

    @Test
    @DisplayName("A spot is held while the player stays within reach, and a move past it starts a new rest")
    void stillness() {
        Instant start = Instant.parse("2026-09-29T12:00:00Z");
        Stillness rest = Stillness.at(0, 64, 0, start);

        Stillness drifted = rest.seenAt(0.6, 64, 0.6, start.plusSeconds(3), 1.0);
        assertThat(drifted).isSameAs(rest);
        assertThat(drifted.heldFor(start.plusSeconds(3))).isEqualTo(Duration.ofSeconds(3));

        Stillness moved = rest.seenAt(0, 65.2, 0, start.plusSeconds(4), 1.0);
        assertThat(moved.since()).isEqualTo(start.plusSeconds(4));
        assertThat(moved.heldFor(start.plusSeconds(4))).isZero();
        assertThat(rest.heldFor(start.minusSeconds(1))).isZero();
    }

    @Test
    @DisplayName("Numbers that are no rule are refused")
    void refused() {
        assertThatThrownBy(() -> new PoseidonRules(-1, 0, 0, Duration.ZERO, 1, true, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoseidonRules(0, 0, 0, Duration.ZERO, 0, true, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PoseidonRules(0, 0, 0, Duration.ZERO, 1, true, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PoseidonRules.shipped().damage(dry(false, true))).isEqualTo(3.0);
    }

    private static PoseidonExposure swimming(Duration still) {
        return new PoseidonExposure(true, false, false, still);
    }

    private static PoseidonExposure dry(boolean rain, boolean sun) {
        return new PoseidonExposure(false, rain, sun, Duration.ZERO);
    }
}
