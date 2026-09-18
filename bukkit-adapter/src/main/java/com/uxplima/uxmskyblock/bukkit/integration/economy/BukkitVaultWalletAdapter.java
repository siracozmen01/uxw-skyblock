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
 */
public final class BukkitVaultWalletAdapter implements ExternalWalletPort {

    private final EconomyBridge economyBridge;

    public BukkitVaultWalletAdapter(EconomyBridge economyBridge) {
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge");
    }

    @Override
    public boolean hasFunds(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return true;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.has(player, dollars);
    }

    @Override
    public boolean withdraw(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return true;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.withdraw(player, dollars);
    }

    @Override
    public boolean deposit(PlayerUuid playerUuid, long amountMinorUnits) {
        if (!economyBridge.isPresent()) {
            return true;
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid.value());
        double dollars = amountMinorUnits / 100.0;
        return economyBridge.deposit(player, dollars);
    }
}
