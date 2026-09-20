package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmlib.hook.permission.VaultPermission;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import org.jspecify.annotations.Nullable;

/**
 * Production reward delivery handler for permissions.
 *
 * <p>Enforces durable permission storage via {@link VaultPermission} backed by LuckPerms
 * or Vault providers. Never uses ephemeral Bukkit attachments ({@code player.addAttachment})
 * which are lost on restart. Fails closed with pending status when a durable provider is absent.
 */
public final class PermissionRewardDeliveryHandler implements RewardDeliveryHandler {

    public interface PermissionService {
        boolean has(OfflinePlayer player, String node);

        boolean add(OfflinePlayer player, String node);
    }

    private final @Nullable Supplier<Optional<PermissionService>> permissionSupplier;
    private final @Nullable ProfileSwitchPort profileSwitchPort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public PermissionRewardDeliveryHandler(
            @Nullable Supplier<Optional<PermissionService>> permissionSupplier,
            @Nullable ProfileSwitchPort profileSwitchPort,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.permissionSupplier =
                permissionSupplier != null ? permissionSupplier : PermissionRewardDeliveryHandler::fromVault;
        this.profileSwitchPort = profileSwitchPort;
        this.sessionCoordinator = sessionCoordinator;
    }

    public PermissionRewardDeliveryHandler(Plugin unused, PlayerSessionCoordinator sessionCoordinator) {
        this(PermissionRewardDeliveryHandler::fromVault, null, sessionCoordinator);
    }

    public static Optional<PermissionService> fromVault() {
        return VaultPermission.find().map(vp -> new PermissionService() {
            @Override
            public boolean has(OfflinePlayer player, String node) {
                return vp.has(player, node);
            }

            @Override
            public boolean add(OfflinePlayer player, String node) {
                return vp.add(player, node);
            }
        });
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

        Optional<PermissionService> optVaultPerm = permissionSupplier != null ? permissionSupplier.get() : fromVault();
        if (optVaultPerm == null || optVaultPerm.isEmpty()) {
            return DeliveryResult.failure(
                    "Durable permission provider (Vault/LuckPerms) is unavailable; permission grant remains pending in inbox.");
        }
        PermissionService vaultPerm = optVaultPerm.get();

        // 2. Resolve canonical PlayerUuid without casting ProfileId
        PlayerUuid playerUuid = resolvePlayerUuid(recipient);
        if (playerUuid == null) {
            return DeliveryResult.failure("Recipient profile " + recipient
                    + " cannot be resolved to a player account; grant remains pending.");
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid.value());

        // 3. Idempotent check: if already has permission, return success immediately
        try {
            if (vaultPerm.has(offlinePlayer, permissionNode)) {
                return DeliveryResult.success(component.componentOperationId().value());
            }

            boolean added = vaultPerm.add(offlinePlayer, permissionNode);
            if (added || vaultPerm.has(offlinePlayer, permissionNode)) {
                return DeliveryResult.success(component.componentOperationId().value());
            } else {
                return DeliveryResult.failure(
                        "Durable permission provider rejected granting permission: " + permissionNode);
            }
        } catch (Exception e) {
            return DeliveryResult.failure("Durable permission grant failed: " + e.getMessage());
        }
    }

    private @Nullable PlayerUuid resolvePlayerUuid(ProfileId profileId) {
        if (profileSwitchPort != null) {
            Optional<PlayerUuid> resolved = profileSwitchPort.resolvePlayerUuid(profileId);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        if (sessionCoordinator != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                var active = sessionCoordinator.activeProfile(online.getUniqueId());
                if (active.isPresent() && active.get().equals(profileId)) {
                    return new PlayerUuid(online.getUniqueId());
                }
            }
        }
        return null;
    }

    private @Nullable String parsePermission(@Nullable String payload) {
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
