package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.command.BankRefusalLines;
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
    private @Nullable BedrockFormService bedrockFormService;

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
                buildGui(player, islandId, catalogue).open(player);
            });
        };
        schedulerPort.async(asyncTask);
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
        return LegacyComponentSerializer.legacySection()
                .serialize(messages.renderPlain(
                        player,
                        key,
                        Placeholder.unparsed("item", price.itemKey()),
                        Placeholder.unparsed("price", money(price.currentPrice()))));
    }

    /** Builds the window from what was already read, so nothing here reaches the database. */
    public SimpleGui buildGui(Player player, IslandId islandId, List<ShopItemPrice> catalogue) {
        int rows = Math.min(6, Math.max(2, (catalogue.size() / 9) + 2));
        SimpleGui gui = Guis.gui()
                .title(messages.renderPlain(player, "menu.shop.title"))
                .rows(rows)
                .build();

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

            List<Component> lore = new ArrayList<>();
            lore.add(messages.renderPlain(
                    player, "menu.shop.tile_price", Placeholder.unparsed("price", money(price.currentPrice()))));
            lore.add(Component.empty());
            lore.add(messages.renderPlain(player, "menu.shop.tile_buy_one"));
            lore.add(messages.renderPlain(
                    player, "menu.shop.tile_buy_stack", Placeholder.unparsed("amount", Integer.toString(STACK))));
            lore.add(messages.renderPlain(player, "menu.shop.tile_sell_one"));
            lore.add(messages.renderPlain(
                    player, "menu.shop.tile_sell_stack", Placeholder.unparsed("amount", Integer.toString(STACK))));

            ItemStack icon = ItemBuilder.of(material)
                    .name(messages.renderPlain(
                                    player, "menu.shop.tile_name", Placeholder.unparsed("item", price.itemKey()))
                            .decoration(TextDecoration.ITALIC, false))
                    .lore(lore)
                    .build();

            gui.set(slot++, GuiItem.button(icon, event -> {
                event.setCancelled(true);
                boolean buying = event.isLeftClick();
                int amount = event.isShiftClick() ? STACK : 1;
                trade(player, islandId, material, amount, buying);
            }));
        }

        gui.set(
                lastRowStart + 4,
                GuiItem.button(
                        ItemBuilder.of(Material.BARRIER)
                                .name(messages.renderPlain(player, "menu.shop.close")
                                        .decoration(TextDecoration.ITALIC, false))
                                .build(),
                        event -> {
                            event.setCancelled(true);
                            player.closeInventory();
                        }));
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
            int held = TradableStacks.countOf(player, material);
            if (held < amount) {
                messages.send(
                        player,
                        "shop.not_enough",
                        Placeholder.unparsed("item", material.name()),
                        Placeholder.unparsed("held", Integer.toString(held)),
                        Placeholder.unparsed("amount", Integer.toString(amount)));
                return;
            }
            TradableStacks.take(player, material, amount);
        }

        schedulerPort.async(() -> {
            IslandShopService.TradeResult result = buying
                    ? shopService.buy(islandId, playerUuid, material.name(), amount, serverNodeId)
                    : shopService.sell(islandId, playerUuid, material.name(), amount, serverNodeId);
            schedulerPort.onEntity(playerUuid, () -> report(player, material, amount, buying, result));
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
                    Placeholder.unparsed("item", traded.itemKey()),
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
                        Placeholder.unparsed("item", poor.itemKey()),
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
