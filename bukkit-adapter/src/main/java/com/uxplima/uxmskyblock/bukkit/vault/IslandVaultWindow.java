package com.uxplima.uxmskyblock.bukkit.vault;

import java.util.List;
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

    /** Marks an open vault window and carries what the close handler needs to commit it. */
    public record VaultHolder(IslandId islandId, int page, ProfileId profileId, String sessionId)
            implements InventoryHolder {

        public VaultHolder {
            Objects.requireNonNull(islandId, "islandId must not be null");
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(sessionId, "sessionId must not be null");
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
        VaultHolder holder = new VaultHolder(
                islandId, page, profileId, opened.session().sessionId().value().toString());
        Inventory inventory = Bukkit.createInventory(
                holder,
                configuration.slotsPerPage(),
                messages.renderPlain(player, "vault.title", Placeholder.unparsed("page", Integer.toString(page))));

        ItemStack[] stored = BukkitInventorySerializer.deserializeItemStacks(vaultPage.contentsNbt());
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
        schedulerPort.async(() -> {
            try {
                vaultService.commitVaultPage(
                        com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId.fromString(holder.sessionId()),
                        serialized,
                        holder.profileId().toString(),
                        null,
                        holder.profileId(),
                        List.of());
            } catch (RuntimeException e) {
                schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()), () -> messages.send(player, "vault.commit_refused"));
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
}
