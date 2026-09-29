package com.uxplima.uxmskyblock.core.domain.stranger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Upside Down turns the blocks it mirrors by the operator's rules, the first match winning. */
class TheUpsideDownDistressesWhatItMirrorsTest {

    @Test
    @DisplayName("A named block turns, a pattern turns every block it matches, and the rest stay")
    void rulesTurnBlocks() {
        List<String> unread = new ArrayList<>();
        DistressPalette palette = DistressPalette.parse(
                List.of("grass_block:mycelium", "*_LEAVES:AIR", "OAK_*:DARK_OAK_PLANKS", "STONE:DEEPSLATE"), unread);

        assertThat(unread).isEmpty();
        assertThat(palette.distressed("GRASS_BLOCK")).isEqualTo("MYCELIUM");
        assertThat(palette.distressed("BIRCH_LEAVES")).isEqualTo("AIR");
        assertThat(palette.distressed("OAK_LEAVES"))
                .describedAs("the first rule that matches wins")
                .isEqualTo("AIR");
        assertThat(palette.distressed("OAK_PLANKS")).isEqualTo("DARK_OAK_PLANKS");
        assertThat(palette.distressed("STONE")).isEqualTo("DEEPSLATE");
        assertThat(palette.distressed("STONE_BRICKS"))
                .describedAs("a name is matched whole")
                .isEqualTo("STONE_BRICKS");
        assertThat(palette.distressed("WATER")).isEqualTo("WATER");
    }

    @Test
    @DisplayName("A pattern needs its start and its end, and cannot count one letter twice")
    void patternsAreWhole() {
        DistressPalette palette = DistressPalette.parse(List.of("AB*BA:X"), new ArrayList<>());

        assertThat(palette.distressed("ABBA")).isEqualTo("X");
        assertThat(palette.distressed("ABA")).isEqualTo("ABA");
        assertThat(palette.distressed("AB_SOMETHING_BA")).isEqualTo("X");
        assertThat(palette.distressed("XABBA")).isEqualTo("XABBA");
    }

    @Test
    @DisplayName("Lines that are no rule are handed back and the rest still count")
    void badLinesAreHandedBack() {
        List<String> unread = new ArrayList<>();
        DistressPalette palette =
                DistressPalette.parse(List.of("GRASS_BLOCK", "A:B:C", "*A*:B", ":B", "DIRT:COARSE_DIRT"), unread);

        assertThat(unread).containsExactly("GRASS_BLOCK", "A:B:C", "*A*:B", ":B");
        assertThat(palette.rules()).hasSize(1);
        assertThat(palette.distressed("DIRT")).isEqualTo("COARSE_DIRT");
    }
}
