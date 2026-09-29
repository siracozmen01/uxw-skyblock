package com.uxplima.uxmskyblock.core.domain.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The border of the StrangerRealms land holds the furthest island with room, within the operator's limits. */
class TheRealmBorderHoldsEveryIslandTest {

    @Test
    @DisplayName("The border reaches past the furthest island by the margin on both sides, and grows with it")
    void itHoldsTheFurthest() {
        RealmBorder border = new RealmBorder(100, 500, 100_000);

        assertThat(border.size(0)).isEqualTo(500);
        assertThat(border.size(-40)).isEqualTo(500);
        assertThat(border.size(5_120)).isEqualTo(10_440);
        assertThat(border.size(10_240)).isEqualTo(20_680);
        assertThat(border.size(Integer.MAX_VALUE)).isEqualTo(100_000);
    }

    @Test
    @DisplayName("Limits that hold nothing are refused")
    void badLimits() {
        assertThatThrownBy(() -> new RealmBorder(-1, 10, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealmBorder(1, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealmBorder(1, 30, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
