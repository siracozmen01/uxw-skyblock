package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.command.BankRefusalLines;
import com.uxplima.uxmskyblock.bukkit.i18n.ItemNames;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.TradableStacks;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.jspecify.annotations.Nullable;

/**
 * The shop as a window: what it trades, what each one costs, and two clicks that trade.
 *
 * <p>Everything the window draws is read before it reaches the thread that owns the player, because
 * that is where a window is built and opened. A click hands the trade back off that thread, and only
 * the items and the answer come back to it.
 *
 * <p>A Bedrock player gets a native form with the same commodities in the same order. A chest a
 * Bedrock player cannot use properly is an unfinished window.
 */
public final class IslandShopMenu {

    /** How many a shift click trades, against the one an ordinary click trades. */
    private static final int STACK = 64;

    private final IslandShopService shopService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final com.uxplima.uxmskyblock.core.domain.session.ServerNodeId serverNodeId;
    private final Messages messages;

    /** Where a refused sale's items go when the seller left before they could be given back. */
    private volatile com.uxplima.uxmskyblock.core.application.reward.@Nullable RewardInboxService inbox;

    /** Names the reward inbox a refused sale's items go to when their seller is gone. */
    public void keepUnreturnedIn(com.uxplima.uxmskyblock.core.application.reward.@Nullable RewardInboxService inbox) {
        this.inbox = inbox;
    }

    private @Nullable BedrockFormService bedrockFormService;

    private volatile @Nullable Consumer<Player> wayBack;

    private volatile @Nullable SkyblockMenuEngine menuEngine;

    public IslandShopMenu(
            IslandShopService shopService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId serverNodeId,
            Messages messages) {
        this.shopService = Objects.requireNonNull(shopService, "shopService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();
        if (activeOpt.isEmpty()) {
            player.sendMessage(
                    messages.render(player, "error.session_not_active").decoration(TextDecoration.ITALIC, false));
            return;
        }

        ProfileId profileId = activeOpt.get();
        Runnable asyncTask = () -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(
                                messages.render(player, "error.no_island").decoration(TextDecoration.ITALIC, false));
                    }
                });
                return;
            }
            IslandId islandId = optIslandId.get();
            List<ShopItemPrice> catalogue = shopService.catalogue();

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                BedrockFormService forms = this.bedrockFormService;
                if (forms != null && forms.isBedrock(player)) {
                    openForm(forms, player, islandId, catalogue);
                    return;
                }
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null
                        && engine.open(
                                player,
                                FILE,
                                Map.of("island", islandId.value().toString(), "amount", Integer.toString(STACK)),
                                Map.of(GOODS, rows(catalogue)))) {
                    return;
                }
                buildGui(player, islandId, catalogue).open(player);
            });
        };
        schedulerPort.async(asyncTask);
    }

    /** The menu file that draws the shop, and the list it draws the goods from. */
    static final String FILE = "island-shop";

    static final String GOODS = "skyblock:shop-goods";

    /**
     * Hands this window the engine that reads {@code menus/island-shop.conf}, and teaches the engine what
     * a click on a good does. The window built here stays as the answer to a file that is missing or will
     * not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(GOODS);
        engine.action("skyblock:shop-buy", ctx -> tradeFromFile(ctx, true));
        engine.action("skyblock:shop-sell", ctx -> tradeFromFile(ctx, false));
    }

    /** One row per good the shop knows the material of: its icon, its name, and its price now. */
    static List<MenuRow> rows(List<ShopItemPrice> catalogue) {
        List<MenuRow> rows = new ArrayList<>();
        for (ShopItemPrice price : catalogue) {
            Material material = Material.matchMaterial(price.itemKey());
            if (material != null) {
                rows.add(new MenuRow(
                        Map.of(
                                "material", material.name(),
                                "item", ItemNames.word(price.itemKey()),
                                "price", money(price.currentPrice())),
                        price));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * {@code skyblock:shop-buy:<amount>} and {@code skyblock:shop-sell:<amount>}: the file says how many a
     * gesture trades, so a shift click can mean a stack on one server and ten on another.
     */
    private void tradeFromFile(com.uxplima.uxmlib.menu.runtime.MenuActionContext ctx, boolean buying) {
        Optional<ShopItemPrice> price = MenuRow.handle(ctx.context(), ShopItemPrice.class);
        String island = ctx.context().arguments().getOrDefault("island", "");
        Material material = price.map(p -> Material.matchMaterial(p.itemKey())).orElse(null);
        if (material == null || island.isEmpty()) {
            return;
        }
        int amount;
        try {
            amount = Math.max(1, Integer.parseInt(ctx.arg().strip()));
        } catch (NumberFormatException unwritten) {
            amount = 1;
        }
        trade(ctx.player(), IslandId.of(UUID.fromString(island)), material, amount, buying);
    }

    /**
     * The same window for a Bedrock player, as a native form.
     *
     * <p>A form button is one press rather than a left and a right click, so each commodity gets two
     * of them: one that buys and one that sells.
     */
    private void openForm(BedrockFormService forms, Player player, IslandId islandId, List<ShopItemPrice> catalogue) {
        List<BedrockFormService.Choice> choices = new ArrayList<>();
        for (ShopItemPrice price : catalogue) {
            Material material = Material.matchMaterial(price.itemKey());
            if (material == null) {
                continue;
            }
            choices.add(new BedrockFormService.Choice(
                    label(player, "menu.shop.form_buy", price), () -> trade(player, islandId, material, 1, true)));
            choices.add(new BedrockFormService.Choice(
                    label(player, "menu.shop.form_sell", price), () -> trade(player, islandId, material, 1, false)));
        }
        forms.openChoiceForm(player, "menu.shop.title", "menu.shop.form_body", choices);
    }

    private String label(Player player, String key, ShopItemPrice price) {
        return com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(messages.renderPlain(
                player,
                key,
                ItemNames.placeholder("item", price.itemKey()),
                Placeholder.unparsed("price", money(price.currentPrice()))));
    }

    /**
     * Hands this window the way back to the island menu, so the bottom row reads "Back" rather than
     * leaving Escape as the only way out. Without one the window has no back button at all.
     */
    public void useWayBack(@Nullable Consumer<Player> wayBack) {
        this.wayBack = wayBack;
    }

    /** Builds the window from what was already read, so nothing here reaches the database. */
    public SimpleGui buildGui(Player player, IslandId islandId, List<ShopItemPrice> catalogue) {
        int rows = Math.min(6, Math.max(2, (catalogue.size() / 9) + 2));
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.shop.title")))
                .rows(rows)
                .build();
        gui.filler().fillRow(rows, GuiItem.display(SkyblockTiles.filler()));

        SkyblockTiles tiles = new SkyblockTiles(messages);
        int slot = 0;
        int lastRowStart = (rows - 1) * 9;
        for (ShopItemPrice price : catalogue) {
            if (slot >= lastRowStart) {
                break;
            }
            Material material = Material.matchMaterial(price.itemKey());
            if (material == null) {
                continue;
            }
            ItemStack icon = tiles.item(
                    material,
                    player,
                    "tile:money @menu.shop.tile price",
                    ItemNames.placeholder("entry_item", price.itemKey()),
                    Placeholder.unparsed("entry_price", money(price.currentPrice())),
                    Placeholder.unparsed("argument_amount", Integer.toString(STACK)));
            gui.set(slot++, GuiItem.button(icon, event -> {
                event.setCancelled(true);
                boolean buying = event.isLeftClick();
                int amount = event.isShiftClick() ? STACK : 1;
                trade(player, islandId, material, amount, buying);
            }));
        }

        Consumer<Player> back = this.wayBack;
        if (back != null) {
            gui.set(
                    lastRowStart + 4,
                    GuiItem.button(tiles.button(Material.FEATHER, player, "menu.button.back"), event -> {
                        event.setCancelled(true);
                        back.accept(player);
                    }));
        }
        return gui;
    }

    /**
     * Trades from a click.
     *
     * <p>Selling takes the items here, on the thread that owns the player, because that is the only
     * thread that may touch an inventory. The bank is asked off it, and a sale the bank refuses
     * hands every item straight back.
     */
    void trade(Player player, IslandId islandId, Material material, int amount, boolean buying) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        if (!buying) {
            if (com.uxplima.uxmskyblock.bukkit.creative.SealedInventory.holds(player)) {
                messages.send(player, "sealed.refused");
                return;
            }
            int held = TradableStacks.countOf(player, material);
            if (held < amount) {
                messages.send(
                        player,
                        "shop.not_enough",
                        ItemNames.placeholder("item", material.name()),
                        Placeholder.unparsed("held", Integer.toString(held)),
                        Placeholder.unparsed("amount", Integer.toString(amount)));
                return;
            }
            TradableStacks.take(player, material, amount);
        }

        PlayerSessionCoordinator sessions = this.sessionCoordinator;
        com.uxplima.uxmskyblock.core.domain.identity.@Nullable ProfileId seller = sessions == null
                ? null
                : sessions.activeProfile(player.getUniqueId()).orElse(null);
        schedulerPort.async(() -> {
            IslandShopService.TradeResult result = buying
                    ? shopService.buy(islandId, playerUuid, material.name(), amount, serverNodeId)
                    : shopService.sell(islandId, playerUuid, material.name(), amount, serverNodeId);
            schedulerPort.onEntity(playerUuid, () -> report(player, material, amount, buying, result), () -> {
                // The player left before the answer reached them.
                if (buying) {
                    com.uxplima.uxmskyblock.bukkit.command.ShopHandover.refundIfBought(
                            schedulerPort, shopService, islandId, playerUuid, result, serverNodeId);
                } else if (seller != null) {
                    com.uxplima.uxmskyblock.bukkit.command.ShopHandover.keepUnreturned(
                            schedulerPort, inbox, seller, material, amount, result);
                }
            });
        });
    }

    private void report(
            Player player, Material material, int amount, boolean buying, IslandShopService.TradeResult result) {
        if (result instanceof IslandShopService.TradeResult.Traded traded) {
            if (buying) {
                TradableStacks.give(player, material, amount);
            }
            messages.send(
                    player,
                    buying ? "shop.bought" : "shop.sold",
                    ItemNames.placeholder("item", traded.itemKey()),
                    Placeholder.unparsed("amount", Long.toString(traded.quantity())),
                    Placeholder.unparsed("price", money(traded.unitPrice())),
                    Placeholder.unparsed("total", money(traded.total())),
                    Placeholder.unparsed("balance", money(traded.balanceAfter())));
            return;
        }

        if (!buying) {
            // Items handed to a shop that did not pay are items nobody has.
            TradableStacks.give(player, material, amount);
        }
        switch (result) {
            case IslandShopService.TradeResult.Traded ignored -> {
                // Answered above.
            }
            case IslandShopService.TradeResult.UnknownItem unknown ->
                messages.send(player, "shop.unknown_item", Placeholder.unparsed("item", unknown.itemKey()));
            case IslandShopService.TradeResult.CannotAfford poor ->
                messages.send(
                        player,
                        "shop.cannot_afford",
                        ItemNames.placeholder("item", poor.itemKey()),
                        Placeholder.unparsed("total", money(poor.total())),
                        Placeholder.unparsed("balance", money(poor.balance())));
            case IslandShopService.TradeResult.Refused refused -> {
                BankTransactionOutcome bank = refused.bank();
                messages.send(player, bank == null ? "shop.refused" : BankRefusalLines.keyFor(bank, "bank.refused"));
            }
        }
    }

    private static String money(long minorUnits) {
        return String.format(Locale.US, "%.2f", minorUnits / 100.0);
    }
}
