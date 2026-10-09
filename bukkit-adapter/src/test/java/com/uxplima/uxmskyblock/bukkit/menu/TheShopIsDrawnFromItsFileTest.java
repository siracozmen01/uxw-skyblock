package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmlib.menu.spec.MenuItemSpec;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The shop is {@code menus/island-shop.conf}: the operator moves a good, changes a gesture or the amount
 * it trades, and the window follows.
 *
 * <p>It was built in code, so its slots, its materials and what each click traded were nobody's to
 * change but a developer's.
 */
class TheShopIsDrawnFromItsFileTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final List<ShopItemPrice> CATALOGUE = List.of(price("DIAMOND", 20_000L), price("NOT_AN_ITEM", 5L));

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final IslandShopService shop = mock(IslandShopService.class);
    private PlayerMock player;
    private IslandShopMenu menu;

    @BeforeEach
    void setUp() {
        player = createPlayer("Ada");
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(shop.catalogue()).thenReturn(CATALOGUE);
        menu = new IslandShopMenu(
                shop, storage, sessions, new InlineSchedulerPort(), ServerNodeId.of("node-1"), Messages.bundled());
    }

    private static ShopItemPrice price(String key, long price) {
        return new ShopItemPrice(
                key, price, 0L, 0L, new PricingCurve(price, price / 2, price * 2, 0.5, 100L), Instant.now());
    }

    @Test
    @DisplayName(
            "A good is a row of its icon, its name in the reader's client and its price, and a good no server knows is left out")
    void aGoodIsARow() {
        List<MenuRow> rows = IslandShopMenu.rows(CATALOGUE);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).words())
                .containsEntry("material", "DIAMOND")
                .containsEntry("item", "<lang:item.minecraft.diamond>")
                .containsEntry("price", "200.00");
    }

    @Test
    @DisplayName("The shipped template draws a good as a tile with its price and the amount a shift click trades")
    void theTemplateDrawsAGood() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        MenuItemSpec template = ShippedTemplates.template("island-shop.conf", "goods");
        MenuContext drawn = MenuContext.of(player, null, 0, Map.of("amount", "64"))
                .withEntry(IslandShopMenu.rows(CATALOGUE).get(0));
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());

        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("◆ Diamond")
                .contains("Price now 200.00")
                .contains("to buy 64")
                .doesNotContain("<entry_")
                .doesNotContain("<argument_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("DIAMOND");
    }

    @Test
    @DisplayName("Each gesture the file names trades what it says: buying one and selling a stack")
    void theGesturesTrade() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        menu.useMenuEngine(engine);
        MenuContext drawn = MenuContext.of(
                        player, null, 0, Map.of("island", ISLAND.value().toString()))
                .withEntry(IslandShopMenu.rows(CATALOGUE).get(0));
        when(shop.buy(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.CannotAfford("DIAMOND", 20_000L, 5L));

        ShippedTemplates.click(engine, "skyblock:shop-buy", drawn, player, ClickKind.LEFT, "3");

        verify(shop).buy(eq(ISLAND), any(), eq("DIAMOND"), eq(3L), any());
        assertThat(ShippedTemplates.verbs(ShippedTemplates.template("island-shop.conf", "goods")))
                .containsEntry(ClickKind.LEFT, List.of("skyblock:shop-buy:1", "sound"))
                .containsEntry(ClickKind.SHIFT_RIGHT, List.of("skyblock:shop-sell:%argument_amount%", "sound"));
    }

    @Test
    @DisplayName("Opening the shop hands the goods to its file, and the window built in code is not drawn")
    void openingUsesTheFile() {
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-shop"), anyMap(), anyMap())).thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player);

        verify(engine)
                .open(
                        eq(player),
                        eq("island-shop"),
                        eq(Map.of("island", ISLAND.value().toString(), "amount", "64")),
                        eq(Map.of("skyblock:shop-goods", IslandShopMenu.rows(CATALOGUE))));
        org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory top =
                player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof com.uxplima.uxmlib.gui.Gui))
                .describedAs("no window built in code is open")
                .isTrue();
    }
}
