package com.uxplima.uxmskyblock.bukkit.reward;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.inventory.JournaledInventoryMutationService;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
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
    private final JournaledInventoryMutationService mutations;
    private final ServerNodeId nodeId;

    /**
     * Where the player's inventory is touched. A claim runs on an asynchronous thread, and this
     * handler used to read and change the inventory from there: on Folia the player's own region
     * owns it, and on Paper the main thread does, so the delivery raced the player's own clicks and
     * the undo read slots that were already moving. Absent in a test with no server threads.
     */
    private final @Nullable SchedulerPort scheduler;

    /** How long a claim waits for the player's thread before it leaves the item in the inbox. */
    private static final long PLAYER_THREAD_WAIT_SECONDS = 10;

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
        this(sessionCoordinator, journalPort, nodeId, null);
    }

    public ItemRewardDeliveryHandler(
            PlayerSessionCoordinator sessionCoordinator,
            InventoryMutationJournalPort journalPort,
            ServerNodeId nodeId,
            @Nullable SchedulerPort scheduler) {
        this.scheduler = scheduler;
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.journalPort = Objects.requireNonNull(journalPort, "journalPort must not be null");
        this.mutations = new JournaledInventoryMutationService(this.journalPort);
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

        // Verify inventory capacity before live mutation to prevent lost items / ground drops on crash,
        // then take the real BEFORE and simulated AFTER fingerprints, all on the player's own thread.
        UUID playerId = player.getUniqueId();
        Result<Optional<Fingerprints>, String> read = onThePlayersThread(playerId, () -> {
            if (!fits(player, itemToDeliver)) {
                return Result.ok(Optional.empty());
            }
            ItemStack[] contents = player.getInventory().getContents();
            byte[] beforeInventoryNbt = BukkitInventorySerializer.serializeItemStacks(contents);
            byte[] simulatedAfterNbt =
                    BukkitInventorySerializer.serializeItemStacks(simulateAddItem(contents, itemToDeliver));
            return Result.ok(
                    Optional.of(new Fingerprints(computeSha256(beforeInventoryNbt), computeSha256(simulatedAfterNbt))));
        });
        if (read.isErr()) {
            return DeliveryResult.failure(read.errorOrThrow());
        }
        Optional<Fingerprints> fingerprints = read.orElseThrow();
        if (fingerprints.isEmpty()) {
            return DeliveryResult.failure(
                    "Insufficient inventory space for item reward; item remains safely in inbox.");
        }
        String beforeFingerprint = fingerprints.get().before();
        String afterFingerprint = fingerprints.get().after();

        // 5. Run the two phase protocol: intent, mutate, commit, and undo the world if the commit
        // is refused. The protocol itself lives in JournaledInventoryMutationService; what is left
        // here is what only this handler knows, which is how to put an item into an inventory and
        // how to take it back out of the slots it landed in.
        Map<Integer, ItemStack> mutatedSlots = new java.util.HashMap<>();

        Result<JournaledInventoryMutationService.MutationSuccess<Boolean>, String> outcome = mutations.execute(
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
                Duration.ofSeconds(60),
                () -> onThePlayersThread(playerId, () -> applyItem(player, itemToDeliver, mutatedSlots)),
                () -> {
                    Result<Boolean, String> undone = onThePlayersThread(playerId, () -> {
                        rollbackMutatedSlots(player, mutatedSlots);
                        return Result.ok(Boolean.TRUE);
                    });
                    if (undone.isErr()) {
                        throw new IllegalStateException(
                                "The item reward could not be taken back: " + undone.errorOrThrow());
                    }
                });

        if (outcome.isErr()) {
            return DeliveryResult.failure(outcome.errorOrThrow());
        }

        activeSession.setLastDurableVersion(outcome.orElseThrow().committedVersion());

        sessionCoordinator.checkpointPlayer(playerUuid);
        return DeliveryResult.success(opUuid);
    }

    /** The inventory as it is and as it will be once the item is in. */
    private record Fingerprints(String before, String after) {}

    private static boolean fits(Player player, ItemStack item) {
        int maxStack = item.getMaxStackSize();
        int availableSpace = 0;
        for (ItemStack slot : player.getInventory().getContents()) {
            if (slot == null || slot.getType().isAir()) {
                availableSpace += maxStack;
            } else if (slot.isSimilar(item)) {
                availableSpace += Math.max(0, maxStack - slot.getAmount());
            }
        }
        return availableSpace >= item.getAmount();
    }

    /**
     * Runs {@code work} on the thread that owns the player and waits for it.
     *
     * <p>The work runs at most once and never after the wait gave up: an item put into an inventory
     * after the claim reported it undelivered would be in the inventory and still in the inbox. If
     * the player leaves first, or their thread does not get to it in time, nothing is done.
     */
    private <T> Result<T, String> onThePlayersThread(UUID playerId, Supplier<Result<T, String>> work) {
        SchedulerPort owner = scheduler;
        if (owner == null || owner.ownsEntity(playerId)) {
            return work.get();
        }
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<Result<T, String>> done = new CompletableFuture<>();
        owner.onEntity(
                playerId,
                () -> {
                    if (!claimed.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        done.complete(work.get());
                    } catch (RuntimeException e) {
                        done.completeExceptionally(e);
                    }
                },
                () -> {
                    if (claimed.compareAndSet(false, true)) {
                        done.complete(Result.err("The player left before the item reward reached them."));
                    }
                });
        try {
            return done.get(PLAYER_THREAD_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException slow) {
            if (claimed.compareAndSet(false, true)) {
                return Result.err("The player's thread did not take the item reward in time.");
            }
            // It started just now, so it finishes: wait for what it did.
            return done.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (claimed.compareAndSet(false, true)) {
                return Result.err("Interrupted before the item reward reached the player.");
            }
            return done.join();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        }
    }

    /**
     * Puts the item into the inventory and records which slots it changed.
     *
     * <p>Only the changed slots are recorded, so an undo puts back what this delivery touched and
     * leaves everything the player did in the meantime alone.
     */
    private static Result<JournaledInventoryMutationService.MutationExecution<Boolean>, String> applyItem(
            Player player, ItemStack itemToDeliver, Map<Integer, ItemStack> mutatedSlots) {
        ItemStack[] beforeContents = cloneContents(player.getInventory().getContents());
        try {
            player.getInventory().addItem(itemToDeliver);
            ItemStack[] afterContents = player.getInventory().getContents();
            for (int i = 0; i < beforeContents.length; i++) {
                if (slotChanged(beforeContents[i], afterContents[i])) {
                    mutatedSlots.put(i, beforeContents[i] != null ? beforeContents[i].clone() : null);
                }
            }
            // The protocol carries a value through; this delivery has none, so it carries the fact
            // that the item landed. What matters is the inventory bytes beside it.
            return Result.ok(new JournaledInventoryMutationService.MutationExecution<>(
                    Boolean.TRUE, BukkitInventorySerializer.serializeItemStacks(afterContents)));
        } catch (RuntimeException e) {
            return Result.err("Live inventory mutation failed: " + e.getMessage());
        }
    }

    private static boolean slotChanged(@Nullable ItemStack before, @Nullable ItemStack after) {
        if (before == null && after == null) {
            return false;
        }
        if (before == null || after == null) {
            return true;
        }
        return !before.isSimilar(after) || before.getAmount() != after.getAmount();
    }

    private static void rollbackMutatedSlots(Player player, Map<Integer, ItemStack> mutatedSlots) {
        for (Map.Entry<Integer, ItemStack> entry : mutatedSlots.entrySet()) {
            player.getInventory().setItem(entry.getKey(), entry.getValue());
        }
        player.updateInventory();
    }

    private @Nullable Player findOnlinePlayerForProfile(ProfileId profileId) {
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

    private @Nullable ItemStack parseItemStack(@Nullable String payload) {
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
