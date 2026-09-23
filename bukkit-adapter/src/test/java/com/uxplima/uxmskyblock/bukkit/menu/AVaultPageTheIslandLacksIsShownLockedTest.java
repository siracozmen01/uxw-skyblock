package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A vault page the island has not bought is shown locked, and leads to where it can be bought.
 *
 * <p>The vault menu drew pages two to four as chests saying "Click to open" on every island. An
 * island with one page clicked one and was told the page does not exist. The file now shows each
 * page only when the island has it, and a locked tile in its slot when it does not, through an
 * {@code at-least} condition on the pages the menu was opened with.
 */
class AVaultPageTheIslandLacksIsShownLockedTest extends MockBukkitHarness {

    private static final Path VAULT_MENU = Path.of("src/main/resources/menus/island-vault.conf");

    @Test
    @DisplayName("at-least holds for a value at or above the number and not below it")
    void atLeastCompares() {
        assertThat(atLeast("1", "vault_pages 2")).isFalse();
        assertThat(atLeast("2", "vault_pages 2")).isTrue();
        assertThat(atLeast("4", "vault_pages 2")).isTrue();
    }

    @Test
    @DisplayName("A value that is missing or not a number, or a line missing a half, is never at least anything")
    void atLeastRefusesWhatItCannotRead() {
        PlayerMock viewer = createPlayer("Reader");
        MenuContext without = MenuContext.of(viewer, null, 0, Map.of());
        assertThat(SkyblockMenuEngine.atLeast(without, Map.of("value", "vault_pages 1")))
                .isFalse();
        assertThat(atLeast("many", "vault_pages 1")).isFalse();
        assertThat(atLeast("3", "vault_pages")).isFalse();
        assertThat(atLeast("3", "vault_pages 1 2")).isFalse();
        assertThat(atLeast("3", "vault_pages two")).isFalse();
    }

    @Test
    @DisplayName("Every page past the first is gated on the pages the island has, with a locked twin in its slot")
    void theFileGatesEveryPage() throws Exception {
        String file = Files.readString(VAULT_MENU);
        for (int page = 2; page <= 4; page++) {
            assertThat(file)
                    .contains("\"at-least:vault_pages " + page + "\"")
                    .contains("\"!at-least:vault_pages " + page + "\"")
                    .contains("page" + page + "-locked {");
        }
    }

    private boolean atLeast(String pages, String line) {
        PlayerMock viewer = createPlayer("Reader");
        MenuContext ctx = MenuContext.of(viewer, null, 0, Map.of("vault_pages", pages));
        return SkyblockMenuEngine.atLeast(ctx, Map.of("value", line));
    }
}
