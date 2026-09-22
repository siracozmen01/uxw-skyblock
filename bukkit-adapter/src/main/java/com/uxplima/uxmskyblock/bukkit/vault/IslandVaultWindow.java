package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageBusyException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPermissionDeniedException;
import org.jspecify.annotations.Nullable;

/**
 * The shared island vault as a container a player can actually open.
 *
 * <p>The service, its escrow, its pessimistic lease and its V19 table have been here since the
 * vault work and nothing ever opened one. This is the missing half: a chest sized window over one
 * page, held under the lease the service hands out, written back when the window closes.
 *
 * <p>It builds a plain container rather than a menu, because a vault page is storage a player edits
 * freely. A menu engine window is the wrong shape: every slot would have to be a button.
 */
public final class IslandVaultWindow {

    /**
     * Marks an open vault window and carries what the close handler needs to commit it.
     *
     * <p>{@code openedWith} is the page as it stood when the window opened, one entry per slot, air
     * where the slot was empty. A refused commit leaves the stored page exactly that, so the
     * difference between it and what the window holds at close is what the player put in, and that
     * is what has to go back to them. The same difference, read slot by slot, is what the audit log
     * records.
     */
    public record VaultHolder(
            IslandId islandId, int page, ProfileId profileId, String sessionId, List<ItemStack> openedWith)
            implements InventoryHolder {

        public VaultHolder {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            Objects.requireNonNull(openedWith, "openedWith must not be null");
            openedWith = List.copyOf(openedWith);
        }

        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("The holder is a marker; the inventory owns it, not the reverse.");
        }
    }

    private final IslandVaultService vaultService;
    private final IslandStoragePort islandStoragePort;
    private final SchedulerPort schedulerPort;
    private final VaultConfiguration configuration;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandVaultWindow(
            IslandVaultService vaultService,
            IslandStoragePort islandStoragePort,
            SchedulerPort schedulerPort,
            VaultConfiguration configuration,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** Opens {@code page} of the island's vault for {@code player}, or says why it cannot. */
    public void open(Player player, int page) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Optional<Island> optIsland = islandStoragePort.findIslandById(optIslandId.get());
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            IslandRole role = roleOf(island, profileId);

            try {
                IslandVaultService.VaultOpenResult opened = vaultService.openVaultPage(
                        island, profileId, role, page, player.getUniqueId(), configuration.leaseDuration());
                schedulerPort.onEntity(playerUuid, () -> show(player, island.id(), page, profileId, opened));
            } catch (VaultPermissionDeniedException denied) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "vault.no_permission"));
            } catch (VaultPageLimitExceededException limit) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> messages.send(
                                player,
                                "vault.page_out_of_range",
                                Placeholder.unparsed("page", Integer.toString(page)),
                                Placeholder.unparsed("max", Integer.toString(limit.maxAllowedPages()))));
            } catch (VaultPageBusyException busy) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> messages.send(
                                player, "vault.busy", Placeholder.unparsed("page", Integer.toString(page))));
            }
        });
    }

    private void show(
            Player player,
            IslandId islandId,
            int page,
            ProfileId profileId,
            IslandVaultService.VaultOpenResult opened) {
        if (!player.isOnline()) {
            vaultService.abortVaultPage(opened.session().sessionId());
            return;
        }
        VaultPage vaultPage = opened.page();
        ItemStack[] stored = BukkitInventorySerializer.deserializeItemStacks(vaultPage.contentsNbt());
        VaultHolder holder = new VaultHolder(
                islandId,
                page,
                profileId,
                opened.session().sessionId().value().toString(),
                snapshotOf(stored, configuration.slotsPerPage()));
        Inventory inventory = Bukkit.createInventory(
                holder,
                configuration.slotsPerPage(),
                messages.renderPlain(player, "vault.title", Placeholder.unparsed("page", Integer.toString(page))));

        for (int slot = 0; slot < Math.min(stored.length, inventory.getSize()); slot++) {
            inventory.setItem(slot, stored[slot]);
        }
        player.openInventory(inventory);
    }

    /**
     * Writes the window back under its lease. A lease that expired while the window was open is
     * refused by the service, and the player is told rather than silently losing the edit.
     */
    public void commit(Player player, VaultHolder holder, ItemStack[] contents) {
        Objects.requireNonNull(holder, "holder must not be null");
        byte[] serialized = BukkitInventorySerializer.serializeItemStacks(contents);
        List<VaultAuditLogEntry> auditTrail = auditTrailOf(holder, contents);
        schedulerPort.async(() -> {
            try {
                vaultService.commitVaultPage(
                        com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId.fromString(holder.sessionId()),
                        serialized,
                        holder.profileId().toString(),
                        null,
                        holder.profileId(),
                        auditTrail);
            } catch (RuntimeException e) {
                // The window is already closed and what it held is nowhere: not in the page, because
                // the commit was refused, and not with the player, because they put it in the vault.
                // Telling them it was refused and keeping the items is losing them.
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    messages.send(player, "vault.commit_refused");
                    returnWhatThePlayerAdded(player, holder.openedWith(), contents);
                });
            }
        });
    }

    private IslandRole roleOf(Island island, ProfileId profileId) {
        IslandMember member = island.members().get(profileId);
        return member != null ? member.role() : IslandRole.VISITOR;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    /**
     * Gives back everything the window held that the stored page did not.
     *
     * <p>A refused commit leaves the page as it was when the window opened, so the difference is
     * exactly what the player put in and nothing that is already safely stored. Handing back the
     * whole window instead would duplicate every stack that never moved.
     *
     * <p>What does not fit is dropped where they stand. An item on the ground can be picked up; an
     * item that was never written and never returned cannot.
     */
    private void returnWhatThePlayerAdded(Player player, List<ItemStack> openedWith, ItemStack[] atClose) {
        List<ItemStack> alreadyStored = new ArrayList<>();
        for (ItemStack stack : openedWith) {
            if (!stack.getType().isAir()) {
                alreadyStored.add(stack.clone());
            }
        }

        for (ItemStack stack : atClose) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            ItemStack owed = stack.clone();
            // Take this stack's amount out of what the page still holds, stack by stack, so a player
            // who added ten to a stack of five gets ten back and not fifteen.
            for (Iterator<ItemStack> stored = alreadyStored.iterator(); stored.hasNext() && owed.getAmount() > 0; ) {
                ItemStack candidate = stored.next();
                if (!candidate.isSimilar(owed)) {
                    continue;
                }
                int settled = Math.min(candidate.getAmount(), owed.getAmount());
                owed.setAmount(owed.getAmount() - settled);
                candidate.setAmount(candidate.getAmount() - settled);
                if (candidate.getAmount() <= 0) {
                    stored.remove();
                }
            }
            if (owed.getAmount() > 0) {
                for (ItemStack overflow : player.getInventory().addItem(owed).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
            }
        }
    }

    /** The page as it stood when the window opened, one entry per slot, air where a slot was empty. */
    private static List<ItemStack> snapshotOf(ItemStack[] stored, int slots) {
        List<ItemStack> snapshot = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = slot < stored.length ? stored[slot] : null;
            snapshot.add(stack == null ? new ItemStack(Material.AIR) : stack.clone());
        }
        return snapshot;
    }

    /**
     * What the player moved, slot by slot, as audit entries.
     *
     * <p>The vault has held a security audit table, a port and a service call since the vault work,
     * and nothing ever wrote a row: the window passed an empty list at every commit. A shared chest
     * several island members can reach is exactly the thing an owner needs a record of.
     *
     * <p>A slot whose item changed outright is two entries, one out and one in, because that is what
     * happened. A slot that only changed amount is the one entry for the difference.
     */
    private static List<VaultAuditLogEntry> auditTrailOf(VaultHolder holder, ItemStack[] atClose) {
        List<ItemStack> openedWith = holder.openedWith();
        List<VaultAuditLogEntry> trail = new ArrayList<>();
        int slots = Math.max(openedWith.size(), atClose.length);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack before = somethingOrNothing(slot < openedWith.size() ? openedWith.get(slot) : null);
            ItemStack after = somethingOrNothing(slot < atClose.length ? atClose[slot] : null);

            if (before != null && after != null && before.isSimilar(after)) {
                int moved = after.getAmount() - before.getAmount();
                if (moved > 0) {
                    trail.add(entry(holder, slot, VaultActionType.DEPOSIT, after, moved));
                } else if (moved < 0) {
                    trail.add(entry(holder, slot, VaultActionType.WITHDRAW, before, -moved));
                }
                continue;
            }
            if (before != null) {
                trail.add(entry(holder, slot, VaultActionType.WITHDRAW, before, before.getAmount()));
            }
            if (after != null) {
                trail.add(entry(holder, slot, VaultActionType.DEPOSIT, after, after.getAmount()));
            }
        }
        return List.copyOf(trail);
    }

    /** An empty slot and an air stack are the same thing here: nothing. */
    private static @Nullable ItemStack somethingOrNothing(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack;
    }

    private static VaultAuditLogEntry entry(
            VaultHolder holder, int slot, VaultActionType action, ItemStack stack, int quantity) {
        String summary = stack.getType().name();
        return VaultAuditLogEntry.create(
                holder.islandId(),
                holder.page(),
                holder.profileId().toString(),
                action,
                slot,
                summary.length() > 128 ? summary.substring(0, 128) : summary,
                quantity);
    }
}
