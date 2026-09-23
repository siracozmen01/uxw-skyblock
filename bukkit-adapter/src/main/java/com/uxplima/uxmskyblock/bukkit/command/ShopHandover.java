package com.uxplima.uxmskyblock.bukkit.command;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * What a shop trade does when the player left before its answer reached them.
 *
 * <p>A trade asks the bank off the player's thread and hands items over on it. A player who left in
 * between had their answer dropped: a buyer paid for items nobody gave them, and a seller whose sale
 * was refused never got their items back. A purchase is paid back, and a refused sale's items go to
 * the seller's reward inbox.
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

    /**
     * Files a refused sale's items in the seller's reward inbox when they could not be given back.
     *
     * <p>The inbox is what reaches a player who is not online: they claim the items the next time they
     * play. Without an inbox, or if filing fails, the items are logged for an operator to give back.
     */
    public static void keepUnreturned(
            SchedulerPort scheduler,
            @Nullable RewardInboxService inbox,
            ProfileId seller,
            Material material,
            int amount,
            IslandShopService.TradeResult result) {
        if (result instanceof IslandShopService.TradeResult.Traded) {
            return;
        }
        if (inbox == null) {
            logLost(seller, material, amount, null);
            return;
        }
        scheduler.async(() -> {
            try {
                inbox.issueReward(seller, "SHOP_RETURN", material.name(), null, stacksOf(material, amount));
            } catch (RuntimeException e) {
                logLost(seller, material, amount, e);
            }
        });
    }

    /** One item component per full stack, so no single delivery asks for more than a stack holds. */
    static List<RewardDraftComponent> stacksOf(Material material, int amount) {
        int perStack = Math.max(1, material.getMaxStackSize());
        List<RewardDraftComponent> stacks = new ArrayList<>();
        for (int left = amount; left > 0; left -= perStack) {
            int here = Math.min(perStack, left);
            stacks.add(new RewardDraftComponent(
                    RewardComponentType.ITEM,
                    "uxm:item_bundle",
                    1,
                    "{\"item\":\"" + material.name() + "\",\"amount\":" + here + "}"));
        }
        return stacks;
    }

    private static void logLost(ProfileId seller, Material material, int amount, @Nullable Throwable why) {
        LOGGER.log(
                Level.WARNING,
                "A sale of " + amount + " " + material + " by profile " + seller + " was not paid for, and the"
                        + " seller left before the items could be given back. Give them back by hand.",
                why);
    }
}
