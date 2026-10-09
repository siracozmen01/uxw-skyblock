package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.menu.providers.ContentClick;
import com.uxplima.uxmlib.menu.providers.ContentProvider;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow.VaultHolder;
import org.jspecify.annotations.Nullable;

/**
 * The page of a vault window drawn from {@code menus/island-vault-page.conf}: the region the file
 * names, filled with the page as it stood when the lease was taken and written back when it closes.
 *
 * <p>The player moves the items themselves, so the region is never repainted while the window is
 * up: a redraw painted from the stored page would take back what was just put down. The window is
 * the truth until it closes, which is how the window built in code always worked.
 *
 * <p>A window with no page behind it moves nothing: the engine reopens a menu from its history
 * without the record it was opened with, and a page already written, by the stop or by a close,
 * is not the window's to change. A take from either would be an item the page still holds.
 *
 * <p>A movement the viewer's role does not allow is refused here, where the window built in code
 * refused it in its listener. A shift click and a drag are asked once for every slot they could
 * land on, so the refusal is said once for the gesture rather than once for each slot.
 */
final class VaultPageContent implements ContentProvider {

    /** How long one refusal speaks for every slot the same gesture is asked about. */
    private static final long ONE_GESTURE_NANOS = 250_000_000L;

    private final IslandVaultWindow window;
    private final Map<UUID, Long> refusedAt = new ConcurrentHashMap<>();

    VaultPageContent(IslandVaultWindow window) {
        this.window = Objects.requireNonNull(window, "window must not be null");
    }

    @Override
    public List<@Nullable ItemStack> render(MenuContext ctx, ContentRegionSpec region) {
        List<@Nullable ItemStack> page = new ArrayList<>();
        VaultHolder holder = live(ctx);
        if (holder == null) {
            return page;
        }
        for (ItemStack stack : holder.openedWith()) {
            page.add(stack.getType().isAir() ? null : stack.clone());
        }
        return page;
    }

    @Override
    public boolean repaintsOnRedraw() {
        return false;
    }

    @Override
    public boolean allows(MenuContext ctx, ContentRegionSpec region, ContentClick click) {
        VaultHolder holder = live(ctx);
        if (holder == null) {
            return false;
        }
        boolean takesOut = click.kind() != ContentClick.Kind.INSERT;
        boolean putsIn = click.kind() != ContentClick.Kind.TAKE;
        if (takesOut && !holder.mayWithdraw()) {
            refuse(ctx, true);
            return false;
        }
        if (putsIn && !holder.mayDeposit()) {
            refuse(ctx, false);
            return false;
        }
        return true;
    }

    @Override
    public void readBack(MenuContext ctx, ContentRegionSpec region, List<@Nullable ItemStack> contents) {
        refusedAt.remove(ctx.viewer().getUniqueId());
        VaultHolder holder = ctx.subjectRaw().orElse(null) instanceof VaultHolder page ? page : null;
        // The stop may have written the page already, and a page is written once.
        if (holder != null && holder.takeTheWrite()) {
            window.commit(ctx.viewer(), holder, contents.toArray(new ItemStack[0]));
        }
    }

    private void refuse(MenuContext ctx, boolean takingOut) {
        long now = System.nanoTime();
        Long last = refusedAt.put(ctx.viewer().getUniqueId(), now);
        if (last == null || now - last > ONE_GESTURE_NANOS) {
            window.sayTheRoleRefused(ctx.viewer(), takingOut);
        }
    }

    /** The page behind the window while it is still the window's to change, or null. */
    private static @Nullable VaultHolder live(MenuContext ctx) {
        return ctx.subjectRaw().orElse(null) instanceof VaultHolder holder
                        && !holder.written().get()
                ? holder
                : null;
    }
}
