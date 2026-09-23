package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.shop.IslandShopService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import com.uxplima.uxmskyblock.core.domain.shop.ShopItemPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What a player reads when the bank would not settle a shop trade.
 *
 * <p>The refusal used to carry the bank's outcome printed as a Java record, and the player read it
 * after a colon: a node's name, an island's id and a sentence in English, whatever their language.
 */
class WhatAShopRefusalTellsAPlayerTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String HELD_ELSEWHERE =
            "Local node node-1 does not hold authority for island " + ISLAND + " (held by node-7)";

    private PlayerMock player;
    private IslandShopService shop;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        player = createPlayer("Trader");
        shop = mock(IslandShopService.class);
        when(shop.catalogue())
                .thenReturn(List.of(new ShopItemPrice(
                        "DIAMOND",
                        20_000L,
                        0L,
                        0L,
                        new PricingCurve(20_000L, 10_000L, 40_000L, 0.5, 100L),
                        Instant.now())));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandShopCommands commands = new IslandShopCommands(
                () -> shop,
                () -> null,
                locations,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                Messages.bundled(),
                sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    @Test
    @DisplayName("A sale the bank refused says why in words, not as the bank's record")
    void aRefusedSaleSaysWhyInWords() throws Exception {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        refuseWith(new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY, HELD_ELSEWHERE));

        assertThat(run("shop sell diamond 4"))
                .anyMatch(line -> line.contains("Another server"))
                .noneMatch(line -> line.contains("AuthorityRejected")
                        || line.contains("node-7")
                        || line.contains(ISLAND.toString()));
    }

    @Test
    @DisplayName("A trade too large for any bank says so, and never reaches the bank")
    void aTradeTooLargeSaysSo() throws Exception {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Refused(
                        "DIAMOND", "The amount asked for is more than any bank can hold."));

        assertThat(run("shop sell diamond 4")).anyMatch(line -> line.contains("more than any island bank"));
    }

    @Test
    @DisplayName("A Turkish player reads a refused trade in Turkish")
    void aTurkishPlayerReadsTurkish() throws Exception {
        player.setLocale(Locale.forLanguageTag("tr"));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 4));
        refuseWith(new BankTransactionOutcome.StaleVersion(3, 4));

        assertThat(run("shop sell diamond 4"))
                .anyMatch(line -> line.contains("aynı anda"))
                .noneMatch(line -> line.contains("StaleVersion"));
    }

    private void refuseWith(BankTransactionOutcome bank) {
        when(shop.sell(any(), any(), anyString(), anyLong(), any()))
                .thenReturn(new IslandShopService.TradeResult.Refused("DIAMOND", String.valueOf(bank), bank));
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class), any(Runnable.class));
        return scheduler;
    }
}
