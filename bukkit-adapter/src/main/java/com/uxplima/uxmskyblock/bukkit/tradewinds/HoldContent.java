package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.menu.providers.ContentClick;
import com.uxplima.uxmlib.menu.providers.ContentProvider;
import com.uxplima.uxmlib.menu.providers.OwnRowsClick;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import org.jspecify.annotations.Nullable;

/**
 * The hold of a vessel drawn from {@code menus/vessel-cargo.conf}. The hold lives in durable storage,
 * so nothing moves in the window: the region is painted from the hold as last read, a click on a stack
 * in it asks for that stack, and a click on a stack of the player's own inventory asks to stow it.
 * Each request is the same move the window built in code makes, under the player's write lock and the
 * vessel's lease.
 *
 * <p>The slots of the region past the hold the vessel's rank gives stay empty and answer nothing.
 * A closed window is forgotten by the next change to the hold, which finds it gone.
 */
final class HoldContent implements ContentProvider {

    private final CargoHolds holds;

    HoldContent(CargoHolds holds) {
        this.holds = Objects.requireNonNull(holds, "holds");
    }

    @Override
    public List<@Nullable ItemStack> render(MenuContext ctx, ContentRegionSpec region) {
        List<@Nullable ItemStack> painted = new ArrayList<>();
        CargoHolds.View view = viewOf(ctx);
        if (view == null) {
            return painted;
        }
        for (ItemStack stack : view.shown()) {
            painted.add(stack == null ? null : stack.clone());
        }
        return painted;
    }

    @Override
    public void clicked(MenuContext ctx, ContentRegionSpec region, ContentClick click) {
        CargoHolds.View view = viewOf(ctx);
        if (view == null || click.index() >= view.slots()) {
            return;
        }
        holds.request(ctx.viewer(), view, true, click.index(), view.shown()[click.index()]);
    }

    @Override
    public boolean ownRowsClicked(MenuContext ctx, ContentRegionSpec region, OwnRowsClick click) {
        CargoHolds.View view = viewOf(ctx);
        if (view == null) {
            return false;
        }
        holds.request(
                ctx.viewer(),
                view,
                false,
                click.inventorySlot(),
                ctx.viewer().getInventory().getItem(click.inventorySlot()));
        return true;
    }

    /** The hold behind a window drawn from the file, or null for a window reopened without one. */
    private static CargoHolds.@Nullable View viewOf(MenuContext ctx) {
        return ctx.subjectRaw().orElse(null) instanceof CargoHolds.View view ? view : null;
    }
}
