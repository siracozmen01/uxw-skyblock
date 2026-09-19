package com.uxplima.uxmskyblock.bukkit.reward;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Production reward delivery handler for item grants.
 *
 * <p>Enforces the durability invariant from {@code GAMEMODE_ARCHITECTURE.md} Section 11.3:
 * <ul>
 *   <li>Requires an active player session; if offline, delivery fails safely so the item remains
 *       persisted in the durable reward inbox.</li>
 *   <li>Executes through write-ahead {@link InventoryMutationJournalPort} with OCC versioning.</li>
 *   <li>Never returns success unless the item is genuinely added to the player's inventory and
 *       committed to the write-ahead journal.</li>
 * </ul>
 */
public final class ItemRewardDeliveryHandler implements RewardDeliveryHandler {

    private final PlayerSessionCoordinator sessionCoordinator;
    private final InventoryMutationJournalPort journalPort;
    private final ServerNodeId nodeId;

    public ItemRewardDeliveryHandler(
            PlayerSessionCoordinator sessionCoordinator,
            InventoryMutationJournalPort journalPort,
            ServerNodeId nodeId) {
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.journalPort = Objects.requireNonNull(journalPort, "journalPort must not be null");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId must not be null");
    }

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.ITEM;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        // 1. Resolve online player and active session authority
        Player player = findOnlinePlayerForProfile(recipient);
        if (player == null || !player.isOnline()) {
            return DeliveryResult.failure("Recipient profile " + recipient
                    + " is offline or has no active online session; item reward remains in inbox.");
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        var activeSession = sessionCoordinator.getActiveSession(player.getUniqueId());
        if (activeSession == null || activeSession.isFenced()) {
            return DeliveryResult.failure("Recipient profile " + recipient + " has no unfenced active session.");
        }

        long sessionEpoch = activeSession.sessionEpoch();
        long expectedVersion = activeSession.lastDurableVersion();

        // 2. Parse item specification from payload
        ItemStack itemToDeliver = parseItemStack(component.payloadData());
        if (itemToDeliver == null || itemToDeliver.getType().isAir()) {
            return DeliveryResult.failure("Invalid item payload: " + component.payloadData());
        }

        // 3. Record write-ahead intent in inventory journal
        UUID opUuid = component.componentOperationId().value();
        InventoryMutationOperationId opId = new InventoryMutationOperationId(opUuid);

        InventoryMutationJournalOutcome intentOutcome = journalPort.recordIntent(
                playerUuid,
                recipient,
                nodeId,
                sessionEpoch,
                expectedVersion,
                opId,
                "REWARD_DELIVERY",
                "before",
                "after",
                component.payloadData(),
                Duration.ofSeconds(60));

        if (!intentOutcome.isSuccess()) {
            return DeliveryResult.failure("Journal intent rejected: "
                    + intentOutcome.rejectionReason().orElse("unknown"));
        }

        // 4. Safely apply to player's live inventory
        var overflow = player.getInventory().addItem(itemToDeliver);
        if (!overflow.isEmpty()) {
            // Drop overflow at player's feet so items are never lost
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        // 5. Commit journal mutation and trigger session checkpoint
        byte[] updatedInventoryNbt = BukkitInventorySerializer.serializeItemStacks(
                player.getInventory().getContents());
        InventoryMutationJournalOutcome commitOutcome = journalPort.commitMutation(
                playerUuid, recipient, nodeId, sessionEpoch, expectedVersion, opId, updatedInventoryNbt);

        if (!commitOutcome.isSuccess()) {
            return DeliveryResult.failure("Failed to commit journal mutation: "
                    + commitOutcome.rejectionReason().orElse("unknown"));
        }

        if (commitOutcome.version().isPresent()) {
            activeSession.setLastDurableVersion(commitOutcome.version().getAsLong());
        }

        sessionCoordinator.checkpointPlayer(playerUuid);
        return DeliveryResult.success(opUuid);
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

    private ItemStack parseItemStack(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        String clean =
                payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        String materialName = "DIRT";
        int amount = 1;

        int itemIdx = clean.indexOf("item:");
        if (itemIdx < 0) {
            itemIdx = clean.indexOf("material:");
        }
        if (itemIdx >= 0) {
            int endIdx = clean.indexOf(",", itemIdx);
            if (endIdx < 0) endIdx = clean.length();
            String matStr = clean.substring(itemIdx + (clean.startsWith("item:", itemIdx) ? 5 : 9), endIdx)
                    .trim();
            materialName = matStr.toUpperCase(Locale.ROOT);
        }

        int amtIdx = clean.indexOf("amount:");
        if (amtIdx >= 0) {
            int endIdx = clean.indexOf(",", amtIdx);
            if (endIdx < 0) endIdx = clean.length();
            try {
                amount = Integer.parseInt(clean.substring(amtIdx + 7, endIdx).trim());
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }

        Material mat = Material.matchMaterial(materialName);
        if (mat == null) {
            mat = Material.DIAMOND;
        }
        return new ItemStack(mat, Math.max(1, amount));
    }
}
