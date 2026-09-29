package com.uxplima.uxmskyblock.core.domain.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A StrangerRealms island reaches further with every member past the first, up to the limit. */
class AClaimGrowsWithItsMembersTest {

    @Test
    @DisplayName("One member reaches what the size gives, each more adds its share, and the limit holds")
    void itGrowsToTheLimit() {
        ClaimGrowth growth = new ClaimGrowth(10, 60);

        assertThat(growth.radius(25, 1)).isEqualTo(25);
        assertThat(growth.radius(25, 0)).isEqualTo(25);
        assertThat(growth.radius(25, 2)).isEqualTo(35);
        assertThat(growth.radius(25, 4)).isEqualTo(55);
        assertThat(growth.radius(25, 5)).isEqualTo(60);
        assertThat(growth.radius(25, 1_000_000)).isEqualTo(60);
    }

    @Test
    @DisplayName("A size bought past the limit is kept, and numbers that are no rule are refused")
    void theSizeIsNeverTakenAway() {
        assertThat(new ClaimGrowth(10, 60).radius(80, 3))
                .describedAs("growth never shrinks what the island paid for")
                .isEqualTo(80);
        assertThatThrownBy(() -> new ClaimGrowth(-1, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaimGrowth(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
