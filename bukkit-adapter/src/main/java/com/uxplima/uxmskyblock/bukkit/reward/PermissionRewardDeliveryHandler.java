package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;

/**
 * Production reward delivery handler for permissions.
 *
 * <p>Grants the specified permission node to the player.
 * Fails closed if the recipient has no active session or permission node is invalid.
 */
public final class PermissionRewardDeliveryHandler implements RewardDeliveryHandler {

    private final Plugin plugin;
    private final PlayerSessionCoordinator sessionCoordinator;

    public PermissionRewardDeliveryHandler(Plugin plugin, PlayerSessionCoordinator sessionCoordinator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
    }

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.PERMISSION;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        String permissionNode = parsePermission(component.payloadData());
        if (permissionNode == null || permissionNode.isBlank()) {
            return DeliveryResult.failure("Invalid permission payload: " + component.payloadData());
        }

        Player player = findOnlinePlayerForProfile(recipient);
        if (player == null || !player.isOnline()) {
            return DeliveryResult.failure(
                    "Recipient profile " + recipient + " is offline; permission grant remains pending in inbox.");
        }

        try {
            player.addAttachment(plugin, permissionNode, true);
            return DeliveryResult.success(component.componentOperationId().value());
        } catch (Exception e) {
            return DeliveryResult.failure("Failed to grant permission: " + e.getMessage());
        }
    }

    private Player findOnlinePlayerForProfile(ProfileId profileId) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            var active = sessionCoordinator.activeProfile(online.getUniqueId());
            if (active.isPresent() && active.get().equals(profileId)) {
                return online;
            }
        }
        return null;
    }

    private String parsePermission(String payload) {
        if (payload == null) return null;
        String clean =
                payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("permission:");
        if (idx < 0) return clean.isEmpty() ? null : clean;
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        return clean.substring(idx + 11, end).trim();
    }
}
