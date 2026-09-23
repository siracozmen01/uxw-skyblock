package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.TradableStacks;
import com.uxplima.uxmskyblock.bukkit.menu.IslandShopMenu;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is shop}: what the shop pays and charges, and the two verbs that trade.
 *
 * <p>The pricing engine, its damping, its elasticity and its curve have been here since the economy
 * work, and nothing ever put an item in it or asked it for a price. The architecture names dynamic
 * shop pricing as a version one requirement.
 *
 * <p>Selling takes the items from the player before the bank is asked, because the player's
 * inventory can only be touched on the thread that owns them, and the bank cannot be asked there.
 * A sale the bank refuses hands the items straight back.
 */
public final class IslandShopCommands {

    private final Supplier<@Nullable IslandShopService> shopServiceProvider;
    private final Supplier<@Nullable IslandShopMenu> shopMenuProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    /** Where a refused sale's items go when the seller left before they could be given back. */
    private volatile Supplier<@Nullable RewardInboxService> inbox = () -> null;

    /** Names the reward inbox a refused sale's items go to when their seller is gone. */
    public void keepUnreturnedIn(Supplier<@Nullable RewardInboxService> inbox) {
        this.inbox = java.util.Objects.requireNonNull(inbox, "inbox must not be null");
    }

    public IslandShopCommands(
            Supplier<@Nullable IslandShopService> shopServiceProvider,
            Supplier<@Nullable IslandShopMenu> shopMenuProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.shopServiceProvider = Objects.requireNonNull(shopServiceProvider, "shopServiceProvider must not be null");
        this.shopMenuProvider = Objects.requireNonNull(shopMenuProvider, "shopMenuProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("shop")
                .executes(this::executeOpen)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("buy")
                        .then(Cmd.argument("item", StringArgumentType.word())
                                .suggests(this::suggestItems)
                                .executes(ctx -> executeBuy(ctx, 1))
                                .then(Cmd.argument("amount", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx ->
                                                executeBuy(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(Cmd.literal("sell")
                        .then(Cmd.argument("item", StringArgumentType.word())
                                .suggests(this::suggestItems)
                                .executes(ctx -> executeSell(ctx, 1))
                                .then(Cmd.argument("amount", IntegerArgumentType.integer(1, 4096))
                                        .executes(ctx ->
                                                executeSell(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))));
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestItems(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        IslandShopService service = shopServiceProvider.get();
        if (service != null) {
            for (ShopItemPrice price : service.catalogue()) {
                builder.suggest(price.itemKey());
            }
        }
        return builder.buildFuture();
    }

    /** Opens the window, or prints the list when this node has no window to open. */
    private int executeOpen(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandShopMenu menu = shopMenuProvider.get();
        if (menu == null) {
            return executeList(ctx);
        }
        menu.open(player);
        return Cmd.OK;
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandShopService service = shopServiceProvider.get();
        if (service == null) {
            send(player, "shop.disabled");
            return Cmd.OK;
        }

        List<ShopItemPrice> catalogue = service.catalogue();
        send(player, "shop.header");
        if (catalogue.isEmpty()) {
            send(player, "shop.nothing_traded");
            return Cmd.OK;
        }
        for (ShopItemPrice price : catalogue) {
            send(
                    player,
                    "shop.entry",
                    Placeholder.unparsed("item", price.itemKey()),
                    Placeholder.unparsed("price", money(price.currentPrice())));
        }
        return Cmd.OK;
    }

    private int executeBuy(CommandContext<CommandSourceStack> ctx, int amount) {
        return withShop(ctx, (player, service, islandId) -> {
            String item = StringArgumentType.getString(ctx, "item");
            Material material = Material.matchMaterial(item);
            if (material == null) {
                send(player, "shop.unknown_item", Placeholder.unparsed("item", item));
                return;
            }
            IslandShopService.TradeResult result =
                    service.buy(islandId, new PlayerUuid(player.getUniqueId()), material.name(), amount, serverNodeId);
            PlayerUuid buyer = new PlayerUuid(player.getUniqueId());
            schedulerPort.onEntity(
                    buyer,
                    () -> {
                        if (result instanceof IslandShopService.TradeResult.Traded traded) {
                            // What does not fit is dropped where they stand. An item on the ground can be
                            // picked up; an item that was paid for and never handed over cannot.
                            TradableStacks.give(player, material, amount);
                            reportTraded(player, "shop.bought", traded);
                            return;
                        }
                        report(player, result);
                    },
                    // The buyer left before the items could be handed over: the purchase is paid back.
                    () -> ShopHandover.refundIfBought(schedulerPort, service, islandId, buyer, result, serverNodeId));
        });
    }

    private int executeSell(CommandContext<CommandSourceStack> ctx, int amount) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        String item = StringArgumentType.getString(ctx, "item");
        Material material = Material.matchMaterial(item);
        if (material == null) {
            send(player, "shop.unknown_item", Placeholder.unparsed("item", item));
            return Cmd.OK;
        }
        IslandShopService service = shopServiceProvider.get();
        if (service == null) {
            send(player, "shop.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        // The inventory is read and taken here, on the thread that owns the player, because that is
        // the only thread that may touch it. Everything after this is the bank, so it goes away.
        int held = TradableStacks.countOf(player, material);
        if (held < amount) {
            send(
                    player,
                    "shop.not_enough",
                    Placeholder.unparsed("item", material.name()),
                    Placeholder.unparsed("held", Integer.toString(held)),
                    Placeholder.unparsed("amount", Integer.toString(amount)));
            return Cmd.OK;
        }
        TradableStacks.take(player, material, amount);

        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> {
                            TradableStacks.give(player, material, amount);
                            send(player, "error.no_island");
                        },
                        () -> ShopHandover.keepUnreturned(
                                schedulerPort,
                                inbox.get(),
                                profileId,
                                material,
                                amount,
                                new IslandShopService.TradeResult.UnknownItem(material.name())));
                return;
            }
            if (!mayTrade(optIsland.get(), profileId)) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> {
                            TradableStacks.give(player, material, amount);
                            send(player, "shop.permission_denied");
                        },
                        () -> ShopHandover.keepUnreturned(
                                schedulerPort,
                                inbox.get(),
                                profileId,
                                material,
                                amount,
                                new IslandShopService.TradeResult.UnknownItem(material.name())));
                return;
            }
            IslandShopService.TradeResult result =
                    service.sell(optIsland.get(), playerUuid, material.name(), amount, serverNodeId);
            schedulerPort.onEntity(
                    playerUuid,
                    () -> {
                        if (result instanceof IslandShopService.TradeResult.Traded traded) {
                            reportTraded(player, "shop.sold", traded);
                            return;
                        }
                        // Items handed to a shop that did not pay are items nobody has.
                        TradableStacks.give(player, material, amount);
                        report(player, result);
                    },
                    () -> ShopHandover.keepUnreturned(schedulerPort, inbox.get(), profileId, material, amount, result));
        });
        return Cmd.OK;
    }

    /** Resolves the caller's island off the command thread and hands the work what it needs. */
    private int withShop(CommandContext<CommandSourceStack> ctx, ShopWork work) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandShopService service = shopServiceProvider.get();
        if (service == null) {
            send(player, "shop.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            if (!mayTrade(optIsland.get(), profileId)) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "shop.permission_denied"));
                return;
            }
            work.run(player, service, optIsland.get());
        });
        return Cmd.OK;
    }

    /**
     * Whether this profile's role lets them trade with the island bank.
     *
     * <p>The role editor has published a shop permission since the permission work and the shop
     * read it nowhere, so a member whose role said no could buy and sell against the island's money
     * all the same. The island itself is a row, so this is asked off the command thread, where its
     * two callers already are.
     *
     * <p>An island that cannot be read is not a refusal. The trade answers that itself, and the
     * answer it gives is the right one.
     */
    private boolean mayTrade(IslandId islandId, ProfileId profileId) {
        return islandLocationService
                .findIsland(islandId)
                .map(island ->
                        island.isOwner(profileId) || island.hasPermission(profileId, IslandPermission.SHOP_ACCESS))
                .orElse(true);
    }

    @FunctionalInterface
    private interface ShopWork {
        void run(Player player, IslandShopService service, IslandId islandId);
    }

    private void reportTraded(Player player, String key, IslandShopService.TradeResult.Traded traded) {
        send(
                player,
                key,
                Placeholder.unparsed("item", traded.itemKey()),
                Placeholder.unparsed("amount", Long.toString(traded.quantity())),
                Placeholder.unparsed("price", money(traded.unitPrice())),
                Placeholder.unparsed("total", money(traded.total())),
                Placeholder.unparsed("balance", money(traded.balanceAfter())));
    }

    private void report(Player player, IslandShopService.TradeResult result) {
        switch (result) {
            case IslandShopService.TradeResult.Traded ignored -> {
                // Reported by the caller, which knows whether it was a purchase or a sale.
            }
            case IslandShopService.TradeResult.UnknownItem unknown ->
                send(player, "shop.unknown_item", Placeholder.unparsed("item", unknown.itemKey()));
            case IslandShopService.TradeResult.CannotAfford poor ->
                send(
                        player,
                        "shop.cannot_afford",
                        Placeholder.unparsed("item", poor.itemKey()),
                        Placeholder.unparsed("total", money(poor.total())),
                        Placeholder.unparsed("balance", money(poor.balance())));
            case IslandShopService.TradeResult.Refused refused -> {
                BankTransactionOutcome bank = refused.bank();
                send(player, bank == null ? "shop.refused" : BankRefusalLines.keyFor(bank, "bank.refused"));
            }
        }
    }

    private static String money(long minorUnits) {
        return String.format(java.util.Locale.US, "%.2f", minorUnits / 100.0);
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
