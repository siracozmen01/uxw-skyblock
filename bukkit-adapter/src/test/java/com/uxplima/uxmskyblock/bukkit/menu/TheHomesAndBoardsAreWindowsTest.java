package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeId;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The homes and every leaderboard are windows drawn from their files, one tile each, and a click on a tile runs
 * the command it stands for under the names this server gave it.
 */
class TheHomesAndBoardsAreWindowsTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final List<String> typed = new ArrayList<>();
    private PlayerMock ada;
    private SkyblockMenuEngine engine;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-home-list.conf", "island-leaderboard.conf");
    }

    private static Home home(String name) {
        return new Home(
                HomeId.random(),
                new ProfileId(UUID.randomUUID()),
                IslandId.of(UUID.randomUUID()),
                name,
                HomeScope.PERSONAL,
                "skyblock_world",
                10.4,
                64.0,
                -5.6,
                0.0f,
                0.0f,
                Instant.now(),
                Instant.now());
    }

    @Test
    @DisplayName("A home is a tile of its name and where it is, and a click goes there or deletes it")
    void aHomeIsATile() {
        HomeList homes = new HomeList(line -> {
            typed.add(line);
            return "unregistered " + line;
        });
        homes.useMenuEngine(engine);
        MenuRow row = HomeList.rows(List.of(home("base"))).get(0);
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(row);

        assertThat(ShippedTemplates.lore(
                        ShippedTemplates.renderer(engine, Messages.bundled()),
                        ShippedTemplates.template("island-home-list.conf", "homes"),
                        drawn))
                .contains("◆ base")
                .contains("Where skyblock_world 10, 64, -6")
                .contains("Shift right click to delete it")
                .doesNotContain("<entry_");
        ShippedTemplates.click(engine, HomeList.TRAVEL, drawn, ada, ClickKind.LEFT, "");
        ShippedTemplates.click(engine, HomeList.DELETE, drawn, ada, ClickKind.SHIFT_RIGHT, "");
        assertThat(typed).containsExactly("gohome base", "delhome base");
    }

    @Test
    @DisplayName("Showing the homes opens their file with how many there are, and answers false with no file")
    void showingOpensTheFile() {
        HomeList homes = new HomeList(line -> line);

        assertThat(homes.show(ada, List.of(home("base")), 3))
                .describedAs("no engine yet")
                .isFalse();
        homes.useMenuEngine(engine);
        engine.install();
        assertThat(homes.show(ada, List.of(home("base"), home("farm")), 3)).isTrue();
        assertThat(engine.bindings().list(HomeList.HOMES).orElseThrow().apply(MenuContext.of(ada, null, 0)))
                .hasSize(2);
    }

    @Test
    @DisplayName("An island on a board is a tile of its place, its name, its score and its owner's head")
    void anIslandOnABoardIsATile() {
        TopList board = new TopList(line -> {
            typed.add(line);
            return "unregistered " + line;
        });
        board.useMenuEngine(engine);
        UUID owner = UUID.randomUUID();
        MenuRow row = TopList.rows(List.of(new TopList.Entry(1, "Sunny", "Level 7", owner, "Bo")))
                .get(0);
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(row);
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());
        var template = ShippedTemplates.template("island-leaderboard.conf", "islands");

        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("◆ #1 Sunny")
                .contains("Score Level 7")
                .contains("Owner Bo")
                .doesNotContain("<entry_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("head:" + owner);
        ShippedTemplates.click(engine, TopList.VISIT, drawn, ada, ClickKind.LEFT, "");
        assertThat(typed).containsExactly("visit Bo");
    }

    @Test
    @DisplayName("An island whose owner the server does not know is drawn under a plain head and visits nobody")
    void anUnknownOwner() {
        TopList board = new TopList(line -> {
            typed.add(line);
            return line;
        });
        board.useMenuEngine(engine);
        MenuRow row = TopList.rows(List.of(new TopList.Entry(2, "Old", "Level 1", null, null)))
                .get(0);
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(row);

        assertThat(row.words()).containsEntry("head", "PLAYER_HEAD").containsEntry("owner", "?");
        ShippedTemplates.click(engine, TopList.VISIT, drawn, ada, ClickKind.LEFT, "");
        assertThat(typed).isEmpty();
        assertThat(board.show(ada, "level", "#2", List.of())).isTrue();
        assertThat(Map.of()).isEmpty();
    }
}
