package com.uxplima.uxmskyblock.bukkit.command;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * What a shop trade does when the player left before its answer reached them.
 *
 * <p>A trade asks the bank off the player's thread and hands items over on it. A player who left in
 * between had their answer dropped: a buyer paid for items nobody gave them, and a seller whose sale
 * was refused never got their items back.
 */
public final class ShopHandover {

    private static final Logger LOGGER = Logger.getLogger(ShopHandover.class.getName());

    private ShopHandover() {}

    /** Pays a purchase back when it went through and its items could not be handed over. */
    public static void refundIfBought(
            SchedulerPort scheduler,
            IslandShopService shop,
            IslandId islandId,
            PlayerUuid buyer,
            IslandShopService.TradeResult result,
            ServerNodeId node) {
        if (!(result instanceof IslandShopService.TradeResult.Traded purchase)) {
            return;
        }
        scheduler.async(() -> {
            try {
                BankTransactionOutcome refund = shop.refundPurchase(islandId, buyer, purchase, node);
                if (!(refund instanceof BankTransactionOutcome.Success)) {
                    LOGGER.warning(() -> "A purchase of " + purchase.quantity() + " " + purchase.itemKey() + " by "
                            + buyer + " was paid for and never handed over, and the refund of " + purchase.total()
                            + " was refused: " + refund + ". Settle it by hand.");
                }
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        "A purchase by " + buyer + " was paid for and never handed over, and the refund of "
                                + purchase.total() + " failed. Settle it by hand.",
                        e);
            }
        });
    }

    /** Says so, loudly, when a refused sale's items could not be given back. */
    public static void itemsNotReturned(
            PlayerUuid seller, Material material, int amount, IslandShopService.TradeResult result) {
        if (result instanceof IslandShopService.TradeResult.Traded) {
            return;
        }
        LOGGER.warning(() -> "A sale of " + amount + " " + material + " by " + seller + " was not paid for, and the"
                + " seller left before the items could be given back. Give them back by hand.");
    }
}
