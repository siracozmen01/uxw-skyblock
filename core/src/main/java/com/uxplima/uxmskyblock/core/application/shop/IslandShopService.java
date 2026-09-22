package com.uxplima.uxmskyblock.core.application.shop;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.jspecify.annotations.Nullable;

/**
 * Buying from and selling to the shop, settled against the island bank.
 *
 * <p>The pricing engine could raise and lower a price and nothing ever asked it to. It had no way to
 * be paid and no way to pay, so the two methods that move a price, {@code recordPurchase} and
 * {@code recordSale}, had no caller anywhere. This is what calls them, and it is the only thing that
 * does, so a price only ever moves because somebody traded.
 *
 * <p>The money moves before the volume does. A trade the bank refuses must leave the price exactly
 * where it was, because a price that moved for a trade that did not happen is a price every other
 * player pays for nothing.
 */
public final class IslandShopService {

    /** What a trade did, or why it did not. */
    public sealed interface TradeResult {

        /** The trade happened: this many units at this unit price, and the bank now holds this. */
        record Traded(String itemKey, long quantity, long unitPrice, long total, long balanceAfter)
                implements TradeResult {}

        /** The shop does not trade that. */
        record UnknownItem(String itemKey) implements TradeResult {}

        /** The island bank could not cover it. */
        record CannotAfford(String itemKey, long total, long balance) implements TradeResult {}

        /**
         * The bank refused for some other reason.
         *
         * <p>The reason is for the log. The bank's own outcome is what a player is told about, out of
         * the catalogue: the reason used to be that outcome printed as a Java record, and the player
         * read it as it stood. No outcome means the trade never reached the bank.
         */
        record Refused(
                String itemKey, String reason, @Nullable BankTransactionOutcome bank) implements TradeResult {

            /** A refusal that never reached the bank. */
            public Refused(String itemKey, String reason) {
                this(itemKey, reason, null);
            }
        }
    }

    private final DynamicPricingEngine pricingEngine;
    private final IslandBankService bankService;

    public IslandShopService(DynamicPricingEngine pricingEngine, IslandBankService bankService) {
        this.pricingEngine = Objects.requireNonNull(pricingEngine, "pricingEngine must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
    }

    /** Every commodity the shop trades, with what it currently costs. */
    public List<ShopItemPrice> catalogue() {
        return pricingEngine.getAllPrices().values().stream()
                .sorted(java.util.Comparator.comparing(ShopItemPrice::itemKey))
                .toList();
    }

    /** What one unit costs right now, or nothing when the shop does not trade it. */
    public OptionalLong unitPrice(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey must not be null");
        return pricingEngine.getUnitPrice(normalise(itemKey));
    }

    /**
     * Buys {@code quantity} units, taking the cost out of the island bank.
     *
     * <p>The bank is charged first. Only a charge that went through moves the price, so a purchase
     * the bank refused leaves every other player's price alone.
     */
    public TradeResult buy(
            IslandId islandId, PlayerUuid actor, String itemKey, long quantity, ServerNodeId serverNodeId) {
        return trade(islandId, actor, itemKey, quantity, serverNodeId, true);
    }

    /**
     * Sells {@code quantity} units, paying the island bank.
     *
     * <p>The caller takes the items from the player before calling this and gives them back when the
     * answer is not {@link TradeResult.Traded}, because items handed to a shop that did not pay are
     * items nobody has.
     */
    public TradeResult sell(
            IslandId islandId, PlayerUuid actor, String itemKey, long quantity, ServerNodeId serverNodeId) {
        return trade(islandId, actor, itemKey, quantity, serverNodeId, false);
    }

    private TradeResult trade(
            IslandId islandId,
            PlayerUuid actor,
            String itemKey,
            long quantity,
            ServerNodeId serverNodeId,
            boolean buying) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(itemKey, "itemKey must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }

        String key = normalise(itemKey);
        OptionalLong price = pricingEngine.getUnitPrice(key);
        if (price.isEmpty()) {
            return new TradeResult.UnknownItem(key);
        }

        long unitPrice = price.getAsLong();
        long total;
        try {
            total = Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException tooMuch) {
            return new TradeResult.Refused(key, "The amount asked for is more than any bank can hold.");
        }

        String reason = (buying ? "Shop purchase: " : "Shop sale: ") + quantity + " " + key;
        BankTransactionOutcome outcome = buying
                ? bankService.withdrawFromIsland(islandId, actor, total, reason, serverNodeId)
                : bankService.depositToIsland(islandId, actor, total, reason, serverNodeId);

        if (outcome instanceof BankTransactionOutcome.InsufficientFunds shortfall) {
            return new TradeResult.CannotAfford(key, total, shortfall.currentBalance());
        }
        if (!(outcome instanceof BankTransactionOutcome.Success success)) {
            return new TradeResult.Refused(key, String.valueOf(outcome), outcome);
        }

        // Only now, with the money moved, does the price move.
        if (buying) {
            pricingEngine.recordPurchase(key, quantity);
        } else {
            pricingEngine.recordSale(key, quantity);
        }

        return new TradeResult.Traded(
                key, quantity, unitPrice, total, success.updatedBank().primaryBalanceMinorUnits());
    }

    private static String normalise(String itemKey) {
        return itemKey.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
