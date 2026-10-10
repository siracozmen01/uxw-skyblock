package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A window built in code, the answer to a menu file that is missing, hides its filler's tooltip as the files do.
 *
 * <p>A pane with a blank name still drew a small empty box under the cursor, on every free slot of every window.
 */
class AWindowBuiltInCodeHidesItsFillerTest extends MockBukkitHarness {

    @Test
    @DisplayName("The filler of a window built in code shows no tooltip at all")
    void theFillerShowsNothing() {
        assertThat(java.util.Objects.requireNonNull(SkyblockTiles.filler().getItemMeta())
                        .isHideTooltip())
                .isTrue();
    }
}
