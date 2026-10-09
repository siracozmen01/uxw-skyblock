package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.menu.providers.ContentClick;
import com.uxplima.uxmlib.menu.providers.ContentProvider;
import com.uxplima.uxmlib.menu.providers.OwnRowsClick;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import org.jspecify.annotations.Nullable;

/**
 * One offer of a trade drawn from the file. Nothing in a trade window moves an item: an offer
 * names a stack in its owner's inventory, and the stack stays there until the trade happens. So
 * both regions are read only, painted from the trade, and the viewer's own half answers a click by
 * taking that offer back, and a click on a stack of their own inventory by offering it.
 */
final class TradeOffers implements ContentProvider {

    private final boolean mine;
    private final TradeWindow.Moves moves;

    TradeOffers(boolean mine, TradeWindow.Moves moves) {
        this.mine = mine;
        this.moves = moves;
    }

    @Override
    public List<@Nullable ItemStack> render(MenuContext ctx, ContentRegionSpec region) {
        List<@Nullable ItemStack> painted = new ArrayList<>();
        TradeWindow.View view = TradeWindow.viewIn(ctx);
        if (view == null) {
            return painted;
        }
        UUID whose = mine ? view.viewer() : view.trade().other(view.viewer());
        for (Trade.Offer offer : view.trade().offers(whose)) {
            painted.add(offer.item());
        }
        return painted;
    }

    @Override
    public void clicked(MenuContext ctx, ContentRegionSpec region, ContentClick click) {
        TradeWindow.View view = TradeWindow.viewIn(ctx);
        if (mine && view != null) {
            moves.withdraw(ctx.viewer(), view.trade(), click.index());
        }
    }

    @Override
    public boolean ownRowsClicked(MenuContext ctx, ContentRegionSpec region, OwnRowsClick click) {
        TradeWindow.View view = TradeWindow.viewIn(ctx);
        if (!mine || view == null) {
            return false;
        }
        moves.offer(ctx.viewer(), view.trade(), click.inventorySlot(), click.stack());
        return true;
    }
}
