package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;

/**
 * Production reward delivery handler for External Vault economy.
 *
 * <p>Deposits funds to the player's external Vault economy account.
 * Fails closed if the external Vault economy provider is unavailable.
 */
public final class ExternalVaultRewardDeliveryHandler implements RewardDeliveryHandler {

    private final @Nullable SkyblockEconomyBridge economyBridge;

    public ExternalVaultRewardDeliveryHandler(@Nullable SkyblockEconomyBridge economyBridge) {
        this.economyBridge = economyBridge;
    }

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.EXTERNAL_VAULT;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        if (economyBridge == null) {
            return DeliveryResult.failure("External Vault economy bridge is not initialized.");
        }

        double amount = parseAmount(component.payloadData());
        if (amount <= 0.0) {
            return DeliveryResult.failure("Invalid external vault amount: " + component.payloadData());
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(grant.recipientProfileId().value());
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            return DeliveryResult.failure("Player has never played or account is unavailable: " + recipient);
        }

        UUID opId = component.componentOperationId().value();
        try {
            boolean success = economyBridge.depositWallet(player, amount);
            if (success) {
                return DeliveryResult.success(opId);
            } else {
                return DeliveryResult.failure("External Vault provider rejected deposit of " + amount);
            }
        } catch (Exception e) {
            return DeliveryResult.failure("External Vault deposit failed: " + e.getMessage());
        }
    }

    private double parseAmount(String payload) {
        if (payload == null) return 0.0;
        String clean = payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("amount:");
        if (idx < 0) return 0.0;
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        try {
            return Double.parseDouble(clean.substring(idx + 7, end).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
