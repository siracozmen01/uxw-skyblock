package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What a role may move in and out of the island vault.
 *
 * <p>The role editor has published a vault deposit permission and a vault withdraw permission since
 * the permission work and the window read neither: it asked only whether the page could be opened.
 * A role allowed to look and nothing else could empty the page, and a role allowed to put things in
 * could take them all out again.
 *
 * <p>Both answers ride on the window's own holder, read once where the page was opened, so nothing
 * here reaches the database on the thread a click arrives on.
 */
class TheVaultRoleDecidesWhatMovesTest {

    private static final int PAGE_SIZE = 27;

    private ServerMock server;
    private PlayerMock player;
    private IslandVaultListener listener;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        IslandVaultWindow window = new IslandVaultWindow(
                mock(IslandVaultService.class),
                mock(IslandStoragePort.class),
                inlineScheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
        listener = new IslandVaultListener(window);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Opens a vault page for the player under a role that may do exactly this. */
    private InventoryView pageOpenedBy(boolean mayDeposit, boolean mayWithdraw) {
        IslandVaultWindow.VaultHolder holder = new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                new ProfileId(player.getUniqueId()),
                UUID.randomUUID().toString(),
                List.of(),
                mayDeposit,
                mayWithdraw);
        Inventory page = Bukkit.createInventory(holder, PAGE_SIZE);
        page.setItem(0, new ItemStack(Material.DIAMOND, 8));
        return player.openInventory(page);
    }

    private InventoryClickEvent click(InventoryView view, int rawSlot, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                rawSlot < PAGE_SIZE ? InventoryType.SlotType.CONTAINER : InventoryType.SlotType.QUICKBAR,
                rawSlot,
                ClickType.LEFT,
                action);
        listener.onInventoryClick(event);
        return event;
    }

    @Test
    @DisplayName("A role that may only look cannot take anything out of the page")
    void lookingIsNotTaking() {
        InventoryView view = pageOpenedBy(false, false);

        InventoryClickEvent event = click(view, 0, InventoryAction.PICKUP_ALL);

        assertThat(event.isCancelled()).isTrue();
        assertThat(player.nextMessage()).describedAs("and is told why").isNotNull();
    }

    @Test
    @DisplayName("A role that may put things in but not take them out is stopped on the way out only")
    void depositWithoutWithdraw() {
        InventoryView view = pageOpenedBy(true, false);

        assertThat(click(view, 0, InventoryAction.PICKUP_ALL).isCancelled())
                .describedAs("taking out")
                .isTrue();
        assertThat(click(view, 1, InventoryAction.PLACE_ALL).isCancelled())
                .describedAs("putting in")
                .isFalse();
    }

    @Test
    @DisplayName("Shift clicking out of the page is taking out, and shift clicking into it is putting in")
    void shiftClickCountsOnBothSides() {
        InventoryView noWithdraw = pageOpenedBy(true, false);
        assertThat(click(noWithdraw, 0, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled())
                .describedAs("a stack shifted out of the page")
                .isTrue();

        InventoryView noDeposit = pageOpenedBy(false, true);
        assertThat(click(noDeposit, PAGE_SIZE + 3, InventoryAction.MOVE_TO_OTHER_INVENTORY)
                        .isCancelled())
                .describedAs("a stack shifted into the page from the player's own inventory")
                .isTrue();
    }

    @Test
    @DisplayName("Gathering with a double click reaches the page from either side")
    void gatheringCountsAsTakingOut() {
        InventoryView view = pageOpenedBy(true, false);

        assertThat(click(view, PAGE_SIZE + 5, InventoryAction.COLLECT_TO_CURSOR).isCancelled())
                .isTrue();
    }

    @Test
    @DisplayName("A swap is both directions at once, so either missing permission stops it")
    void aswapNeedsBoth() {
        assertThat(click(pageOpenedBy(true, false), 0, InventoryAction.SWAP_WITH_CURSOR)
                        .isCancelled())
                .isTrue();
        assertThat(click(pageOpenedBy(false, true), 0, InventoryAction.SWAP_WITH_CURSOR)
                        .isCancelled())
                .isTrue();
    }

    @Test
    @DisplayName("A role holding both moves items freely")
    void arolewithBothIsLeftAlone() {
        InventoryView view = pageOpenedBy(true, true);

        assertThat(click(view, 0, InventoryAction.PICKUP_ALL).isCancelled()).isFalse();
        assertThat(click(view, 1, InventoryAction.PLACE_ALL).isCancelled()).isFalse();
        assertThat(click(view, PAGE_SIZE + 2, InventoryAction.MOVE_TO_OTHER_INVENTORY)
                        .isCancelled())
                .isFalse();
    }

    @Test
    @DisplayName("Dragging into a page the role may not deposit into is refused")
    void draggingIntoThePageNeedsDeposit() {
        InventoryView view = pageOpenedBy(false, true);
        Map<Integer, ItemStack> spread = Map.of(2, new ItemStack(Material.STONE, 1));

        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack(Material.STONE, 2), false, spread);
        listener.onInventoryDrag(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Dragging inside the player's own inventory is nobody's business but theirs")
    void draggingOutsideThePageIsLeftAlone() {
        InventoryView view = pageOpenedBy(false, true);
        Map<Integer, ItemStack> spread = Map.of(PAGE_SIZE + 4, new ItemStack(Material.STONE, 1));

        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack(Material.STONE, 2), false, spread);
        listener.onInventoryDrag(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A window this listener does not own is left alone entirely")
    void anotherWindowIsNotTouched() {
        Inventory plain = Bukkit.createInventory(null, PAGE_SIZE);
        InventoryView view = player.openInventory(plain);

        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
