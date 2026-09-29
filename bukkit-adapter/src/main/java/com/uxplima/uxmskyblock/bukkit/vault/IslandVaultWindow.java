package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
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

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandVaultWindow.class.getName());

    /** How a moment is written in the log, in the server's own zone. */
    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(java.time.ZoneId.systemDefault());

    /**
     * Marks an open vault window and carries what the close handler needs to commit it.
     *
     * <p>{@code openedWith} is the page as it stood when the window opened, one entry per slot, air
     * where the slot was empty. A refused commit leaves the stored page exactly that, so the
     * difference between it and what the window holds at close is what the player put in, and that
     * is what has to go back to them. The same difference, read slot by slot, is what the audit log
     * records.
     *
     * <p>{@code mayDeposit} and {@code mayWithdraw} are what the viewer's role lets them do with the
     * page. They are read once, where the page is opened, and carried here so a click can ask the
     * holder rather than the database: that is the only way to answer on the thread a click arrives
     * on.
     */
    public record VaultHolder(
            IslandId islandId,
            int page,
            ProfileId profileId,
            String sessionId,
            List<ItemStack> openedWith,
            boolean mayDeposit,
            boolean mayWithdraw)
            implements InventoryHolder, com.uxplima.uxmskyblock.bukkit.session.WritesPlayerStateItself {

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

        /** The player as the stored page leaves them: see {@link WhatThePageOwes#asStored}. */
        @Override
        public ItemStack @Nullable [] inventoryAsStored(Player player, Inventory window) {
            return WhatThePageOwes.asStored(
                    player.getInventory().getContents(),
                    player.getItemOnCursor(),
                    WhatThePageOwes.between(openedWith, window.getContents()));
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
                boolean mayDeposit = role.hasPermission(IslandPermission.VAULT_DEPOSIT);
                boolean mayWithdraw = role.hasPermission(IslandPermission.VAULT_WITHDRAW);
                schedulerPort.onEntity(
                        playerUuid, () -> show(player, island.id(), page, profileId, opened, mayDeposit, mayWithdraw));
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

    /**
     * Sends the island's management what the vault has seen.
     *
     * <p>The audit entries were written and no one could read them: the service call that returns
     * them had no caller anywhere. A record nobody can look at is not a record.
     *
     * <p>The bar is the island's management permission, not the one that opens the vault. Every
     * member can open the chest; who took what out of it is the owner's question.
     */
    public void showLog(Player player, int limit) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findIslandById);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!roleOf(island, profileId).hasPermission(IslandPermission.SETTINGS_MODIFY)) {
                schedulerPort.onEntity(playerUuid, () -> messages.send(player, "vault.log_no_permission"));
                return;
            }

            List<VaultAuditLogEntry> entries = vaultService.getRecentAuditLogs(island.id(), limit);
            schedulerPort.onEntity(playerUuid, () -> sendLog(player, island, entries));
        });
    }

    private void sendLog(Player player, Island island, List<VaultAuditLogEntry> entries) {
        if (entries.isEmpty()) {
            messages.send(player, "vault.log_empty");
            return;
        }
        messages.send(player, "vault.log_header", Placeholder.unparsed("count", Integer.toString(entries.size())));
        for (VaultAuditLogEntry entry : entries) {
            messages.send(
                    player,
                    "vault.log_entry",
                    Placeholder.unparsed("actor", nameOf(island, entry.actorProfileId())),
                    Placeholder.unparsed("action", entry.actionType().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("quantity", Integer.toString(entry.quantity())),
                    Placeholder.unparsed("item", entry.itemSummary()),
                    Placeholder.unparsed("page", Integer.toString(entry.page())),
                    Placeholder.unparsed("slot", Integer.toString(entry.slot())),
                    Placeholder.unparsed("when", WHEN.format(entry.createdAt())));
        }
    }

    /**
     * The name behind a profile id, or the id itself when the island no longer holds that member.
     *
     * <p>The island is already in hand, so this costs no lookup. A member who has left keeps their
     * entries: a record that forgets who did something is worth less than the id.
     */
    private static String nameOf(Island island, String actorProfileId) {
        for (IslandMember member : island.members().values()) {
            if (member.profileId().toString().equals(actorProfileId)) {
                String name =
                        Bukkit.getOfflinePlayer(member.playerUuid().value()).getName();
                return name != null ? name : actorProfileId;
            }
        }
        return actorProfileId;
    }

    private void show(
            Player player,
            IslandId islandId,
            int page,
            ProfileId profileId,
            IslandVaultService.VaultOpenResult opened,
            boolean mayDeposit,
            boolean mayWithdraw) {
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
                WhatThePageOwes.openedWith(stored, configuration.slotsPerPage()),
                mayDeposit,
                mayWithdraw);
        Inventory inventory = Bukkit.createInventory(
                holder,
                configuration.slotsPerPage(),
                messages.renderPlain(player, "vault.title", Placeholder.unparsed("page", Integer.toString(page))));

        for (int slot = 0; slot < Math.min(stored.length, inventory.getSize()); slot++) {
            inventory.setItem(slot, stored[slot]);
        }
        player.openInventory(inventory);
        closeBeforeTheLeaseRunsOut(player, holder);
    }

    /**
     * Closes the window while its lease still holds, so what it saves is saved.
     *
     * <p>The lease was never renewed and the window never closed, so a player who left it open past
     * the lease closed it onto a refused commit. The page kept what it held, the player kept what
     * they had taken out of it, and opening it again handed the same items over a second time. The
     * window now closes at five sixths of the lease, which is when the save still lands.
     */
    void closeBeforeTheLeaseRunsOut(Player player, VaultHolder holder) {
        java.time.Duration lease = configuration.leaseDuration();
        java.time.Duration closeAt = lease.multipliedBy(5).dividedBy(6);
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        schedulerPort.asyncAfter(
                closeAt,
                () -> schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()
                            && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                        messages.send(player, "vault.closed_to_save");
                        player.closeInventory();
                    }
                }));
    }

    /**
     * Tells a player their role does not let them move items that way.
     *
     * <p>The two directions read differently to the player: one says they may not take anything
     * out, the other that they may not put anything in. The window says it because the window owns
     * the catalogue, and the click arrives on the player's own thread already.
     */
    public void sayTheRoleRefused(Player player, boolean takingOut) {
        messages.send(player, takingOut ? "vault.no_withdraw" : "vault.no_deposit");
    }

    /**
     * Writes the window back under its lease. A lease that expired while the window was open is
     * refused by the service, and the player is told rather than silently losing the edit.
     */
    public void commit(Player player, VaultHolder holder, ItemStack[] contents) {
        Objects.requireNonNull(holder, "holder must not be null");
        byte[] serialized = BukkitInventorySerializer.serializeItemStacks(contents);
        List<VaultAuditLogEntry> auditTrail = VaultAuditTrail.of(holder, contents);
        PlayerAtClose playerState = stateOf(player, holder);
        schedulerPort.async(() -> {
            try {
                commitPage(holder, serialized, playerState, auditTrail);
            } catch (RuntimeException e) {
                // The window is already closed and what it held is nowhere: not in the page, because
                // the commit was refused, and not with the player, because they put it in the vault.
                // Telling them it was refused and keeping the items is losing them.
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    messages.send(player, "vault.commit_refused");
                    settleTheRefusedCommit(player, holder, contents);
                });
            }
        });
    }

    /**
     * Writes a window the player still has open when the server stops, on the stopping thread.
     *
     * <p>The plugin is disabled before players are disconnected, so the close event of a window open
     * at a stop never reached the listener: the page kept what it held, and the stop wrote the player
     * holding what they had taken out of it. The page and the player are now written together here,
     * before the stop writes the player; a refused write puts the player back where the page says
     * they are. The window stays open, so nothing is written twice.
     */
    public void writeBeforeStop(Player player) {
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null || !(top.getHolder() instanceof VaultHolder holder)) {
            return;
        }
        ItemStack[] contents = top.getContents();
        PlayerAtClose playerState = stateOf(player, holder);
        try {
            commitPage(
                    holder,
                    BukkitInventorySerializer.serializeItemStacks(contents),
                    playerState,
                    VaultAuditTrail.of(holder, contents));
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    "The vault page open for " + player.getName()
                            + " could not be written at a stop; the player is put back as the page stands",
                    e);
            settleTheRefusedCommit(player, holder, contents);
        }
    }

    /**
     * Writes the page, and the player with it, under the session's write lock.
     *
     * <p>The player is written over the version the session stands at when the lock is taken, not the
     * one it stood at when the window closed: a checkpoint that landed in between moved it on, and a
     * save over the older version was refused and handed the page's items back.
     */
    private void commitPage(
            VaultHolder holder,
            byte[] serialized,
            @Nullable PlayerAtClose player,
            List<VaultAuditLogEntry> auditTrail) {
        com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId sessionId =
                com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId.fromString(holder.sessionId());
        if (player == null) {
            vaultService.commitVaultPage(
                    sessionId, serialized, holder.profileId().toString(), null, auditTrail);
            return;
        }
        ActiveSession session = player.session();
        session.writes().lock();
        try {
            long version = session.lastDurableVersion();
            PlayerStateWrite write = new PlayerStateWrite(
                    session.playerUuid(),
                    player.node(),
                    session.sessionEpoch(),
                    version,
                    player.state().withInventoryNbt(version, player.state().inventoryNbt()));
            vaultService.commitVaultPage(
                    sessionId, serialized, holder.profileId().toString(), write, auditTrail);
            // The session now stands at the version the commit wrote, so the next checkpoint builds on it.
            session.setLastDurableVersion(write.writtenVersion());
        } finally {
            session.writes().unlock();
        }
    }

    /** What the player holds as the window closes, and the session it is written under. */
    private record PlayerAtClose(
            ActiveSession session,
            com.uxplima.uxmskyblock.core.domain.session.ServerNodeId node,
            com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord state) {}

    /**
     * What the player holds as the window closes, to be written with the page.
     *
     * <p>The page was written alone and the player's inventory at the next checkpoint, up to a minute
     * later. A crash in between kept what the player had put in the vault in both places, or lost what
     * they had taken out. Both are now written in one transaction, under the player's session. A
     * player with no session here has nothing to write, and the page is written alone as before.
     */
    private @Nullable PlayerAtClose stateOf(Player player, VaultHolder holder) {
        PlayerSessionCoordinator sessions = this.sessionCoordinator;
        if (sessions == null) {
            return null;
        }
        ActiveSession session = sessions.getActiveSession(player.getUniqueId());
        if (session == null || !session.activeProfileId().equals(holder.profileId())) {
            return null;
        }
        return new PlayerAtClose(
                session,
                sessions.nodeId(),
                BukkitInventorySerializer.snapshotPlayer(
                        player, session.activeProfileId(), session.lastDurableVersion()));
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
     * Puts the player back where the stored page says they are, after a refused commit.
     *
     * <p>A refused commit leaves the page as it was when the window opened. What the window held at
     * close and the page did not is what the player put in, and it goes back to them. What the page
     * held and the window no longer did is what the player took out, and the page still holds it, so
     * it comes back out of the player's inventory. Handing back only what was put in let a player take
     * a page's items, let the commit be refused, and take them again.
     *
     * <p>What does not fit is dropped where they stand. What the player no longer holds cannot be
     * taken back, and is written to the log with who and what, for an administrator to settle.
     */
    private void settleTheRefusedCommit(Player player, VaultHolder holder, ItemStack[] atClose) {
        WhatThePageOwes.Moved moved = WhatThePageOwes.between(holder.openedWith(), atClose);
        List<ItemStack> stillInThePage = moved.takenOut();
        List<ItemStack> putIn = moved.putIn();

        // What the player took out, the page still holds.
        for (ItemStack takenOut : stillInThePage) {
            for (ItemStack kept : player.getInventory().removeItem(takenOut).values()) {
                LOGGER.warning(() -> player.getName() + " took " + kept.getAmount() + " " + kept.getType()
                        + " out of vault page " + holder.page() + " of island " + holder.islandId()
                        + " under a refused save and no longer holds them. The page still holds them.");
            }
        }
        for (ItemStack owed : putIn) {
            for (ItemStack overflow : player.getInventory().addItem(owed).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }
}
