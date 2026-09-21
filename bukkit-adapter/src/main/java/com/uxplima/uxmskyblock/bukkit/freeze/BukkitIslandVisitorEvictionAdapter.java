package com.uxplima.uxmskyblock.bukkit.freeze;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private final Messages messages;

    public record EvictionPlan(
            IslandId islandId,
            String worldName,
            IslandBounds bounds,
            @Nullable String reason) {}

    public BukkitIslandVisitorEvictionAdapter(
            Plugin plugin, IslandStoragePort islandStoragePort, SchedulerPort schedulerPort, Messages messages) {
        this(plugin, islandStoragePort, schedulerPort, null, messages);
    }

    public BukkitIslandVisitorEvictionAdapter(
            Plugin plugin,
            IslandStoragePort islandStoragePort,
            SchedulerPort schedulerPort,
            @Nullable String evacuationWorldName,
            Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.evacuationWorldName = evacuationWorldName;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public void setEvacuationWorldName(@Nullable String evacuationWorldName) {
        this.evacuationWorldName = evacuationWorldName;
    }

    public @Nullable String evacuationWorldName() {
        return evacuationWorldName;
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
                if (cur != null
                        && cur.getWorld() != null
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

    private void sendFrozenNotice(Player player, @Nullable String reason) {
        Component reasonText = reason != null
                ? Component.text(reason)
                : messages.renderPlain(player, "protection.quarantine_default_reason");
        messages.send(player, "error.island_frozen", Placeholder.component("reason", reasonText));
    }

    private boolean isStaffOrBypass(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission("uxmskyblock.admin.inspect")
                || player.hasPermission("uxmskyblock.admin.freeze");
    }
}
