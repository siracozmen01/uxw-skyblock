package com.uxplima.uxmskyblock.bukkit.freeze;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.core.application.freeze.IslandVisitorEvictionPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;

/**
 * Bukkit/Folia platform implementation of {@link IslandVisitorEvictionPort} for administrative quarantine.
 * Pipeline: async persistence location query -> immutable EvictionPlan -> onGlobal player enumeration -> onEntity per-player bounding box check & Folia-safe teleport.
 */
public final class BukkitIslandVisitorEvictionAdapter implements IslandVisitorEvictionPort {

    private final Plugin plugin;
    private final IslandStoragePort islandStoragePort;
    private final SchedulerPort schedulerPort;
    private volatile @Nullable String evacuationWorldName;
    private volatile @Nullable MessageProvider messageProvider;

    public record EvictionPlan(
            IslandId islandId,
            String worldName,
            IslandBounds bounds,
            @Nullable String reason) {}

    public BukkitIslandVisitorEvictionAdapter(
            Plugin plugin, IslandStoragePort islandStoragePort, SchedulerPort schedulerPort) {
        this(plugin, islandStoragePort, schedulerPort, null, null);
    }

    public BukkitIslandVisitorEvictionAdapter(
            Plugin plugin,
            IslandStoragePort islandStoragePort,
            SchedulerPort schedulerPort,
            @Nullable String evacuationWorldName,
            @Nullable MessageProvider messageProvider) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.evacuationWorldName = evacuationWorldName;
        this.messageProvider = messageProvider;
    }

    public void setEvacuationWorldName(@Nullable String evacuationWorldName) {
        this.evacuationWorldName = evacuationWorldName;
    }

    public void setMessageProvider(@Nullable MessageProvider messageProvider) {
        this.messageProvider = messageProvider;
    }

    public @Nullable String evacuationWorldName() {
        return evacuationWorldName;
    }

    public @Nullable MessageProvider messageProvider() {
        return messageProvider;
    }

    @Override
    public void evictNonStaffVisitors(IslandId islandId, @Nullable String reason) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        schedulerPort.async(() -> {
            Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
            if (optLocation.isEmpty()) {
                return;
            }

            IslandLocation location = optLocation.get();
            EvictionPlan plan = new EvictionPlan(islandId, location.worldName(), location.bounds(), reason);
            schedulerPort.onGlobal(() -> executeEvictionPlan(plan));
        });
    }

    private void executeEvictionPlan(EvictionPlan plan) {
        World evacuationWorld = null;
        if (evacuationWorldName != null && !evacuationWorldName.isBlank()) {
            evacuationWorld = plugin.getServer().getWorld(evacuationWorldName);
        }
        if (evacuationWorld == null) {
            evacuationWorld = plugin.getServer().getWorlds().isEmpty()
                    ? null
                    : plugin.getServer().getWorlds().get(0);
        }
        Location spawnLocation = evacuationWorld != null ? evacuationWorld.getSpawnLocation() : null;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isStaffOrBypass(player)) {
                continue;
            }

            UUID playerUuid = player.getUniqueId();
            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }

                Location cur = player.getLocation();
                if (cur.getWorld() != null
                        && cur.getWorld().getName().equals(plan.worldName())
                        && plan.bounds().contains(cur.getBlockX(), cur.getBlockZ())) {
                    if (spawnLocation != null) {
                        var unused = player.teleportAsync(spawnLocation).thenAccept(teleported -> {
                            if (Boolean.TRUE.equals(teleported)) {
                                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                                player.setFallDistance(0.0f);
                            }
                        });
                    }
                    sendFrozenNotice(player, plan.reason());
                }
            });
        }
    }

    @SuppressWarnings("EmptyCatch")
    private void sendFrozenNotice(Player player, @Nullable String reason) {
        String reasonText = reason != null ? reason : "Administrative quarantine";
        if (messageProvider != null) {
            String locale = "en";
            try {
                if (player.locale() != null) {
                    locale = player.locale().getLanguage();
                }
            } catch (Throwable ignored) {
            }
            player.sendMessage(messageProvider.getComponent(
                    "error.island_frozen", locale, Placeholder.parsed("reason", reasonText)));
        } else {
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize(
                            "<red><bold>QUARANTINE:</bold> This island has been placed under administrative freeze. "
                                    + "Reason: <yellow>"
                                    + reasonText
                                    + "</yellow></red>"));
        }
    }

    private boolean isStaffOrBypass(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission("uxmskyblock.admin.inspect")
                || player.hasPermission("uxmskyblock.admin.freeze");
    }
}
