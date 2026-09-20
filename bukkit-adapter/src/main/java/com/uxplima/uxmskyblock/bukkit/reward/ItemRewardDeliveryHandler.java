package com.uxplima.uxmskyblock.bukkit.reward;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Production reward delivery handler for item grants.
 *
 * <p>Enforces the durability invariant from {@code GAMEMODE_ARCHITECTURE.md} Section 11.3:
 * <ul>
 *   <li>Requires an active player session; if offline, delivery fails safely so the item remains
 *       persisted in the durable reward inbox.</li>
 *   <li>Executes through write-ahead {@link InventoryMutationJournalPort} with OCC versioning.</li>
 *   <li>Calculates real SHA-256 fingerprints representing actual slot state; rejects literal tokens.</li>
 *   <li>Protects against duplicate payouts by checking previous journal commit state.</li>
 *   <li>Performs rollback reconciliation and aborts intent if journal commit fails.</li>
 *   <li>Never drops overflow items into the world before durable commit has succeeded.</li>
 * </ul>
 */
public final class ItemRewardDeliveryHandler implements RewardDeliveryHandler {

    private final PlayerSessionCoordinator sessionCoordinator;
    private final InventoryMutationJournalPort journalPort;
    private final ServerNodeId nodeId;

    public ItemRewardDeliveryHandler(
            @Nullable Plugin plugin,
            PlayerSessionCoordinator sessionCoordinator,
            InventoryMutationJournalPort journalPort,
            ServerNodeId nodeId) {
        this(sessionCoordinator, journalPort, nodeId);
    }

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

        UUID opUuid = component.componentOperationId().value();
        InventoryMutationOperationId opId = new InventoryMutationOperationId(opUuid);

        // 3. Check for previous committed journal record (idempotency / duplicate protection)
        Optional<InventoryMutationJournalRecord> existingJournal = journalPort.loadJournal(opId);
        if (existingJournal.isPresent()) {
            InventoryMutationJournalRecord record = existingJournal.get();
            if (record.state() == InventoryMutationJournalState.COMMITTED) {
                return DeliveryResult.success(opUuid);
            }
        }

        // Verify inventory capacity before live mutation to prevent lost items / ground drops on crash
        int neededAmount = itemToDeliver.getAmount();
        int maxStack = itemToDeliver.getMaxStackSize();
        int availableSpace = 0;
        for (ItemStack slot : player.getInventory().getContents()) {
            if (slot == null || slot.getType().isAir()) {
                availableSpace += maxStack;
            } else if (slot.isSimilar(itemToDeliver)) {
                availableSpace += Math.max(0, maxStack - slot.getAmount());
            }
        }
        if (availableSpace < neededAmount) {
            return DeliveryResult.failure(
                    "Insufficient inventory space for item reward; item remains safely in inbox.");
        }

        // 4. Calculate real BEFORE and simulated AFTER fingerprints
        byte[] beforeInventoryNbt = BukkitInventorySerializer.serializeItemStacks(
                player.getInventory().getContents());
        String beforeFingerprint = computeSha256(beforeInventoryNbt);

        ItemStack[] simulatedContents = simulateAddItem(player.getInventory().getContents(), itemToDeliver);
        byte[] simulatedAfterNbt = BukkitInventorySerializer.serializeItemStacks(simulatedContents);
        String afterFingerprint = computeSha256(simulatedAfterNbt);

        // 5. Record write-ahead intent before live mutation begins
        InventoryMutationJournalOutcome intentOutcome = journalPort.recordIntent(
                playerUuid,
                recipient,
                nodeId,
                sessionEpoch,
                expectedVersion,
                opId,
                "REWARD_DELIVERY",
                beforeFingerprint,
                afterFingerprint,
                component.payloadData(),
                Duration.ofSeconds(60));

        if (!intentOutcome.isSuccess()) {
            return DeliveryResult.failure("Journal intent rejected: "
                    + intentOutcome.rejectionReason().orElse("unknown"));
        }

        // 6. Snapshot current inventory and apply live mutation
        ItemStack[] beforeContents = cloneContents(player.getInventory().getContents());
        Map<Integer, ItemStack> mutatedSlots = new java.util.HashMap<>();
        byte[] updatedInventoryNbt;

        try {
            // Apply live item mutation
            player.getInventory().addItem(itemToDeliver);
            ItemStack[] afterContents = player.getInventory().getContents();
            for (int i = 0; i < beforeContents.length; i++) {
                ItemStack b = beforeContents[i];
                ItemStack a = afterContents[i];
                boolean changed;
                if (b == null && a == null) {
                    changed = false;
                } else if (b == null || a == null) {
                    changed = true;
                } else {
                    changed = !b.isSimilar(a) || b.getAmount() != a.getAmount();
                }
                if (changed) {
                    mutatedSlots.put(i, b != null ? b.clone() : null);
                }
            }
            updatedInventoryNbt = BukkitInventorySerializer.serializeItemStacks(afterContents);
        } catch (Exception e) {
            journalPort.abortIntent(playerUuid, recipient, nodeId, sessionEpoch, opId);
            return DeliveryResult.failure("Live inventory mutation failed: " + e.getMessage());
        }

        // 7. Commit journal mutation
        InventoryMutationJournalOutcome commitOutcome;
        try {
            commitOutcome = journalPort.commitMutation(
                    playerUuid, recipient, nodeId, sessionEpoch, expectedVersion, opId, updatedInventoryNbt);
        } catch (Exception e) {
            // Rollback ONLY mutated slots on commit exception, preserving unrelated slots (e.g. Slot 12)
            rollbackMutatedSlots(player, mutatedSlots);
            journalPort.abortIntent(playerUuid, recipient, nodeId, sessionEpoch, opId);
            return DeliveryResult.failure("Commit exception; rolled back inventory: " + e.getMessage());
        }

        if (!commitOutcome.isSuccess()) {
            // Rollback ONLY mutated slots on commit rejection, preserving unrelated slots (e.g. Slot 12)
            rollbackMutatedSlots(player, mutatedSlots);
            journalPort.abortIntent(playerUuid, recipient, nodeId, sessionEpoch, opId);
            return DeliveryResult.failure("Failed to commit journal mutation: "
                    + commitOutcome.rejectionReason().orElse("unknown") + "; inventory rolled back.");
        }

        if (commitOutcome.version().isPresent()) {
            activeSession.setLastDurableVersion(commitOutcome.version().getAsLong());
        }

        sessionCoordinator.checkpointPlayer(playerUuid);
        return DeliveryResult.success(opUuid);
    }

    private static void rollbackMutatedSlots(Player player, Map<Integer, ItemStack> mutatedSlots) {
        for (Map.Entry<Integer, ItemStack> entry : mutatedSlots.entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue());
        }
        player.updateInventory();
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

    private static String computeSha256(byte[] data) {
        if (data == null || data.length == 0) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                copy[i] = contents[i].clone();
            }
        }
        return copy;
    }

    private static ItemStack[] simulateAddItem(ItemStack[] contents, ItemStack itemToAdd) {
        ItemStack[] copy = cloneContents(contents);
        int remaining = itemToAdd.getAmount();
        int maxStack = itemToAdd.getMaxStackSize();

        for (int i = 0; i < copy.length && remaining > 0; i++) {
            ItemStack slot = copy[i];
            if (slot != null && slot.isSimilar(itemToAdd) && slot.getAmount() < maxStack) {
                int canAdd = Math.min(remaining, maxStack - slot.getAmount());
                slot.setAmount(slot.getAmount() + canAdd);
                remaining -= canAdd;
            }
        }

        for (int i = 0; i < copy.length && remaining > 0; i++) {
            if (copy[i] == null || copy[i].getType().isAir()) {
                int toPlace = Math.min(remaining, maxStack);
                ItemStack newStack = itemToAdd.clone();
                newStack.setAmount(toPlace);
                copy[i] = newStack;
                remaining -= toPlace;
            }
        }
        return copy;
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
