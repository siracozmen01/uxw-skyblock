package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Writes a vault page back when its window closes, and keeps a click inside what the role allows.
 *
 * <p>A vault page is held under a lease while it is open, so closing is the commit. Without this the
 * lease would run out on its own and everything the player moved would be gone.
 *
 * <p>The role editor has published a vault deposit permission and a vault withdraw permission since
 * the permission work and the window read neither: it asked only whether the page could be opened.
 * A role allowed to look and nothing else could empty the page. Both answers are carried on the
 * window's own holder, read once where the page was opened, so a click costs no database call on
 * the thread it arrives on.
 */
public final class IslandVaultListener implements Listener {

    private final IslandVaultWindow window;

    public IslandVaultListener(IslandVaultWindow window) {
        this.window = Objects.requireNonNull(window, "window must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof IslandVaultWindow.VaultHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        window.commit(player, holder, inventory.getContents());
    }

    /** Refuses a click that would move items the viewer's role does not let them move. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandVaultWindow.VaultHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean out = takesOut(event, topSize);
        boolean in = putsIn(event, topSize);
        if ((out && !holder.mayWithdraw()) || (in && !holder.mayDeposit())) {
            event.setCancelled(true);
            window.sayTheRoleRefused(player, out && !holder.mayWithdraw());
        }
    }

    /** Refuses a drag that would spread items into a page the viewer may not deposit into. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandVaultWindow.VaultHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (holder.mayDeposit()) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean reachesThePage = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (reachesThePage) {
            event.setCancelled(true);
            window.sayTheRoleRefused(player, false);
        }
    }

    /**
     * Whether this click could take something out of the page.
     *
     * <p>Gathering with a double click reaches the page from either side, so it counts wherever it
     * was aimed. Everything else counts only when it was aimed at the page itself.
     */
    private static boolean takesOut(InventoryClickEvent event, int topSize) {
        InventoryAction action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return true;
        }
        if (!isOnThePage(event, topSize)) {
            return false;
        }
        return switch (action) {
            case PICKUP_ALL,
                    PICKUP_HALF,
                    PICKUP_ONE,
                    PICKUP_SOME,
                    MOVE_TO_OTHER_INVENTORY,
                    HOTBAR_SWAP,
                    HOTBAR_MOVE_AND_READD,
                    DROP_ONE_SLOT,
                    DROP_ALL_SLOT,
                    SWAP_WITH_CURSOR -> true;
            default -> false;
        };
    }

    /** Whether this click could put something into the page. */
    private static boolean putsIn(InventoryClickEvent event, int topSize) {
        InventoryAction action = event.getAction();
        if (!isOnThePage(event, topSize)) {
            // Shift clicking in one's own inventory sends the stack to the page.
            return action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        }
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> true;
            default -> false;
        };
    }

    private static boolean isOnThePage(InventoryClickEvent event, int topSize) {
        int raw = event.getRawSlot();
        return raw >= 0 && raw < topSize;
    }
}
