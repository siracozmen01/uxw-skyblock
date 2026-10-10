package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The warp directory is {@code menus/island-warp-directory.conf}, drawn from the warps the command read.
 *
 * <p>It was built in code, so an operator could not move a warp's tile, change what a click on it did,
 * or lead the window back to anywhere but where a developer had decided.
 */
class TheWarpDirectoryIsDrawnFromItsFileTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private PlayerMock player;
    private IslandWarpBrowseMenu menu;
    private List<IslandWarpBrowseMenu.Entry> entries;

    @BeforeEach
    void setUp() {
        player = createPlayer("Ada");
        menu = new IslandWarpBrowseMenu(Messages.bundled());
        entries = List.of(
                new IslandWarpBrowseMenu.Entry(warp("shop", "DIAMOND_BLOCK", WarpCategory.SHOPS), "Ayse"),
                new IslandWarpBrowseMenu.Entry(warp("old", "NOT_A_BLOCK", WarpCategory.GENERAL), "Mehmet"));
    }

    private static IslandWarp warp(String name, String icon, WarpCategory category) {
        Instant now = Instant.now();
        return new IslandWarp(
                IslandWarpId.of(UUID.randomUUID()),
                IslandId.of(UUID.randomUUID()),
                WarpName.of(name),
                new WarpLocation("world", 10.0, 70.0, 10.0, 0.0f, 0.0f),
                icon,
                category,
                false,
                now,
                now);
    }

    @Test
    @DisplayName("A warp is a row of its icon, its name, its owner and its kind, under a compass when its icon is gone")
    void aWarpIsARow() {
        List<MenuRow> rows = menu.rows(player, entries);

        assertThat(rows.get(0).words())
                .containsEntry("material", "DIAMOND_BLOCK")
                .containsEntry("name", "shop")
                .containsEntry("owner", "Ayse")
                .containsEntry("category", "Shops and Markets");
        assertThat(rows.get(1).words()).containsEntry("material", "COMPASS");
    }

    @Test
    @DisplayName("The shipped template draws a warp as a tile that names its owner and its kind")
    void theTemplateDrawsAWarp() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        var template = ShippedTemplates.template("island-warp-directory.conf", "warps");
        MenuContext drawn = MenuContext.of(player, null, 0)
                .withEntry(menu.rows(player, entries).get(0));
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());

        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("◆ shop")
                .contains("Owner Ayse")
                .contains("Kind Shops and Markets")
                .doesNotContain("<entry_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("DIAMOND_BLOCK");
    }

    @Test
    @DisplayName("A warp keeps its name on a server whose members window lists its members too")
    void aWarpKeepsItsNameBesideTheMembers() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        new MemberList(
                        Messages.bundled(),
                        uuid -> java.util.Optional.empty(),
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandLocationService.class),
                        mock(com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService.class),
                        java.time.Clock.systemUTC())
                .register(engine.bindings(), (viewer, member) -> {});
        var template = ShippedTemplates.template("island-warp-directory.conf", "warps");
        MenuContext drawn = MenuContext.of(player, null, 0)
                .withEntry(menu.rows(player, entries).get(0));

        assertThat(ShippedTemplates.lore(ShippedTemplates.renderer(engine, Messages.bundled()), template, drawn))
                .contains("◆ shop");
    }

    @Test
    @DisplayName("Choosing a warp in the file runs the visit the command asked for, with that warp")
    void choosingAWarpVisitsIt() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        menu.useMenuEngine(engine);
        List<IslandWarpBrowseMenu.Entry> chosen = new ArrayList<>();
        menu.show(player, entries, chosen::add);
        MenuContext drawn = MenuContext.of(player, null, 0)
                .withEntry(menu.rows(player, entries).get(1));

        ShippedTemplates.click(engine, "skyblock:warp-visit", drawn, player, ClickKind.LEFT, "");

        assertThat(chosen).containsExactly(entries.get(1));
    }

    @Test
    @DisplayName("Opening the directory hands the warps to its file, and the window built in code is not drawn")
    void openingUsesTheFile() {
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-warp-directory"), anyMap(), anyMap()))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.show(player, entries, entry -> {});

        verify(engine)
                .open(
                        eq(player),
                        eq("island-warp-directory"),
                        eq(Map.of()),
                        eq(Map.of("skyblock:public-warps", menu.rows(player, entries))));
        org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory top =
                player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof com.uxplima.uxmlib.gui.Gui))
                .describedAs("no window built in code is open")
                .isTrue();
    }
}
