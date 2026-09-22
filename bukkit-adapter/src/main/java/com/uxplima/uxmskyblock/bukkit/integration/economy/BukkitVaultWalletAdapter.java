package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Vault/VaultUnlocked adapter bridging the domain {@link ExternalWalletPort}
 * to the platform-level {@link EconomyBridge}.
 *
 * <p>With no economy plugin there is no wallet, and every answer here is no. It used to be yes: a
 * wallet that did not exist had funds, paid out and took in, so money moved into an island bank from
 * nowhere and out of it into nothing.
 */
public final class BukkitVaultWalletAdapter implements ExternalWalletPort {

    private final EconomyBridge economyBridge;

    public BukkitVaultWalletAdapter(EconomyBridge economyBridge) {
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge");
    }

    @Override
    public boolean hasFunds(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.has(player, dollars);
    }

    @Override
    public boolean withdraw(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.withdraw(player, dollars);
    }

    @Override
    public boolean deposit(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.deposit(player, dollars);
    }
}
