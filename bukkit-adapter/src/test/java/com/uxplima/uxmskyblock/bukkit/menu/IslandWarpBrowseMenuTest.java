package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.item.RenderContext;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * The public warp directory as a window.
 *
 * <p>Every warp has carried an icon material since the warp work and the directory drew chat lines,
 * so the field was written, stored and never seen. These say it is seen now, and that a Bedrock
 * player reads the same directory.
 */
class IslandWarpBrowseMenuTest {

    private static final IslandId FIRST = IslandId.of(UUID.randomUUID());
    private static final IslandId SECOND = IslandId.of(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private IslandWarpBrowseMenu menu;

    private static IslandWarp warp(IslandId islandId, String name, String icon, WarpCategory category) {
        Instant now = Instant.now();
        return new IslandWarp(
                IslandWarpId.of(UUID.randomUUID()),
                islandId,
                WarpName.of(name),
                new WarpLocation("world", 10.0, 70.0, 10.0, 0.0f, 0.0f),
                icon,
                category,
                false,
                now,
                now);
    }

    private static List<IslandWarpBrowseMenu.Entry> twoWarps() {
        return List.of(
                new IslandWarpBrowseMenu.Entry(warp(FIRST, "shop", "DIAMOND_BLOCK", WarpCategory.SHOPS), "Ayse"),
                new IslandWarpBrowseMenu.Entry(warp(SECOND, "farm", "WHEAT", WarpCategory.FARMS), "Mehmet"));
    }

    /** The icon a slot draws for this viewer, which is where the warp's own material shows up. */
    private ItemStack tileAt(SimpleGui gui, int slot) {
        GuiItem item = java.util.Objects.requireNonNull(gui.getItem(slot), "slot " + slot + " is empty");
        return item.icon(new RenderContext(player, gui, slot));
    }

    /** Clicks a slot the way the framework does: the resolved action, on a cancelled event. */
    private void clickAt(SimpleGui gui, int slot) {
        GuiItem item = java.util.Objects.requireNonNull(gui.getItem(slot), "slot " + slot + " is empty");
        item.action(new RenderContext(player, gui, slot)).accept(mock(InventoryClickEvent.class));
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Guis.install(MockBukkit.createMockPlugin());
        player = server.addPlayer();
        menu = new IslandWarpBrowseMenu(Messages.bundled());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A warp is drawn under the icon its owner gave it")
    void theIconIsTheOneTheWarpCarries() {
        SimpleGui gui = menu.buildGui(player, twoWarps(), entry -> {});

        assertThat(tileAt(gui, 0).getType()).isEqualTo(Material.DIAMOND_BLOCK);
        assertThat(tileAt(gui, 1).getType()).isEqualTo(Material.WHEAT);
    }

    @Test
    @DisplayName("A warp naming a material this server does not have is still in the directory")
    void anUnknownIconFallsBackToACompass() {
        List<IslandWarpBrowseMenu.Entry> entries =
                List.of(new IslandWarpBrowseMenu.Entry(warp(FIRST, "old", "GRASS", WarpCategory.GENERAL), "Ayse"));

        assertThat(tileAt(menu.buildGui(player, entries, entry -> {}), 0).getType())
                .isEqualTo(Material.COMPASS);
    }

    @Test
    @DisplayName("The name, the owner and the kind are all on the tile, so a player knows where they are going")
    void theTileSaysWhoseWarpItIs() {
        ItemStack tile = tileAt(menu.buildGui(player, twoWarps(), entry -> {}), 0);

        String drawn = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(java.util.Objects.requireNonNull(tile.getItemMeta().displayName()));
        List<net.kyori.adventure.text.Component> lore = tile.getItemMeta().lore();
        assertThat(lore).isNotNull();
        String story = String.join(
                " ",
                lore.stream()
                        .map(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(line))
                        .toList());

        assertThat(drawn).contains("shop");
        assertThat(story).contains("Ayse").contains("SHOPS");
    }

    @Test
    @DisplayName("Clicking a tile hands that warp to the visit flow")
    void aClickReachesTheVisit() {
        List<IslandWarpBrowseMenu.Entry> chosen = new ArrayList<>();
        SimpleGui gui = menu.buildGui(player, twoWarps(), chosen::add);

        clickAt(gui, 1);

        assertThat(chosen).hasSize(1);
        assertThat(chosen.get(0).warp().name().value()).isEqualTo("farm");
    }

    @Test
    @DisplayName("A Bedrock player gets the same warps in the same order, as a native form")
    void bedrockReadsTheSameDirectory() {
        BedrockDetector detector = mock(BedrockDetector.class);
        BedrockScreen screen = mock(BedrockScreen.class);
        when(detector.isBedrock(player.getUniqueId())).thenReturn(true);
        menu.setBedrockFormService(new BedrockFormService(detector, screen, Messages.bundled()));

        List<IslandWarpBrowseMenu.Entry> chosen = new ArrayList<>();
        menu.show(player, twoWarps(), chosen::add);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BedrockButton>> buttons = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<IntConsumer> answer = ArgumentCaptor.forClass(IntConsumer.class);
        verify(screen).sendSimpleForm(any(), anyString(), anyString(), buttons.capture(), answer.capture());

        assertThat(buttons.getValue()).hasSize(2);
        assertThat(buttons.getValue().get(0).text()).contains("shop");
        assertThat(buttons.getValue().get(1).text()).contains("farm");

        answer.getValue().accept(1);
        assertThat(chosen).hasSize(1);
        assertThat(chosen.get(0).warp().name().value()).isEqualTo("farm");
        assertThat(chosen.get(0).ownerName()).isEqualTo("Mehmet");
    }

    @Test
    @DisplayName("The owner of an island is resolved once however many warps it publishes")
    void anIslandsOwnerIsReadOnce() {
        List<IslandWarp> warps = List.of(
                warp(FIRST, "one", "STONE", WarpCategory.SHOPS),
                warp(FIRST, "two", "STONE", WarpCategory.FARMS),
                warp(SECOND, "three", "STONE", WarpCategory.GENERAL));
        AtomicInteger reads = new AtomicInteger();

        List<IslandWarpBrowseMenu.Entry> entries = IslandWarpBrowseMenu.entriesOf(warps, islandId -> {
            reads.incrementAndGet();
            return islandId.equals(FIRST) ? "Ayse" : "Mehmet";
        });

        assertThat(reads).hasValue(2);
        assertThat(entries).extracting(IslandWarpBrowseMenu.Entry::ownerName).containsExactly("Ayse", "Ayse", "Mehmet");
    }
}
