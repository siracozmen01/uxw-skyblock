package com.uxplima.uxmskyblock.bukkit.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * A Vault economy that keeps its wallets in a map.
 *
 * <p>The island bank refuses every move on a server with no economy plugin, because there is no
 * wallet to take from or pay into. A test that boots the plugin and moves money needs one, and this
 * is it: a plugin called Vault, and an economy registered with the services manager the way an
 * economy plugin registers its own.
 */
public final class InMemoryVaultEconomy {

    private final Map<UUID, Double> wallets = new ConcurrentHashMap<>();
    private final double opening;

    private InMemoryVaultEconomy(double opening) {
        this.opening = opening;
    }

    /** Registers the economy on this server. Call it before the plugin under test enables. */
    public static InMemoryVaultEconomy install(ServerMock server, double openingBalance) {
        InMemoryVaultEconomy economy = new InMemoryVaultEconomy(openingBalance);
        Plugin vault = MockBukkit.createMockPlugin("Vault");
        server.getServicesManager().register(Economy.class, economy.asVault(), vault, ServicePriority.Normal);
        return economy;
    }

    /** What this player's wallet holds now. */
    public double balance(OfflinePlayer player) {
        return wallets.getOrDefault(player.getUniqueId(), opening);
    }

    private Economy asVault() {
        Economy economy = mock(Economy.class);
        when(economy.isEnabled()).thenReturn(true);
        when(economy.getName()).thenReturn("InMemory");
        when(economy.format(anyDouble()))
                .thenAnswer(call -> String.format(java.util.Locale.ROOT, "%.2f", call.<Double>getArgument(0)));
        when(economy.getBalance(any(OfflinePlayer.class))).thenAnswer(call -> balance(call.getArgument(0)));
        when(economy.has(any(OfflinePlayer.class), anyDouble()))
                .thenAnswer(call -> balance(call.getArgument(0)) >= call.<Double>getArgument(1));
        when(economy.withdrawPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(call -> {
            OfflinePlayer player = call.getArgument(0);
            double amount = call.getArgument(1);
            double held = balance(player);
            if (amount < 0 || held < amount) {
                return new EconomyResponse(0, held, EconomyResponse.ResponseType.FAILURE, "insufficient");
            }
            wallets.put(player.getUniqueId(), held - amount);
            return new EconomyResponse(amount, held - amount, EconomyResponse.ResponseType.SUCCESS, null);
        });
        when(economy.depositPlayer(any(OfflinePlayer.class), anyDouble())).thenAnswer(call -> {
            OfflinePlayer player = call.getArgument(0);
            double amount = call.getArgument(1);
            double held = balance(player);
            if (amount < 0) {
                return new EconomyResponse(0, held, EconomyResponse.ResponseType.FAILURE, "negative");
            }
            wallets.put(player.getUniqueId(), held + amount);
            return new EconomyResponse(amount, held + amount, EconomyResponse.ResponseType.SUCCESS, null);
        });
        return economy;
    }
}
