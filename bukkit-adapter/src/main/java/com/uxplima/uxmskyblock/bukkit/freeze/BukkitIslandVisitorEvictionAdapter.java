package com.uxplima.uxmskyblock.bukkit.freeze;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.core.application.freeze.IslandVisitorEvictionPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Bukkit/Folia platform implementation of {@link IslandVisitorEvictionPort} for administrative quarantine.
 * Scans online players currently positioned within a quarantined island's spatial bounds and teleports
 * non-staff occupants to the server spawn safely on their owning entity region thread.
 */
public final class BukkitIslandVisitorEvictionAdapter implements IslandVisitorEvictionPort {

    private final Plugin plugin;
    private final IslandStoragePort islandStoragePort;
    private final SchedulerPort schedulerPort;

    public BukkitIslandVisitorEvictionAdapter(
            Plugin plugin, IslandStoragePort islandStoragePort, SchedulerPort schedulerPort) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    @Override
    public void evictNonStaffVisitors(IslandId islandId, String reason) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optLocation.isEmpty()) {
            return;
        }

        IslandLocation location = optLocation.get();
        IslandBounds bounds = location.bounds();
        String worldName = location.worldName();

        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null && !Bukkit.getWorlds().isEmpty()) {
            targetWorld = Bukkit.getWorlds().get(0);
        }

        World spawnWorld = Bukkit.getWorld("world");
        if (spawnWorld == null && !Bukkit.getWorlds().isEmpty()) {
            spawnWorld = Bukkit.getWorlds().get(0);
        }
        Location spawnLocation = spawnWorld != null ? spawnWorld.getSpawnLocation() : null;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isStaffOrBypass(player)) {
                continue;
            }

            Location playerLoc = player.getLocation();
            if (playerLoc.getWorld() == null || !playerLoc.getWorld().getName().equals(worldName)) {
                continue;
            }

            if (bounds.contains(playerLoc.getBlockX(), playerLoc.getBlockZ())) {
                final Location finalSpawn = spawnLocation;
                schedulerPort.onEntity(player.getUniqueId(), () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    Location cur = player.getLocation();
                    if (cur.getWorld() != null
                            && cur.getWorld().getName().equals(worldName)
                            && bounds.contains(cur.getBlockX(), cur.getBlockZ())) {
                        if (finalSpawn != null) {
                            var unused = player.teleportAsync(finalSpawn).thenAccept(teleported -> {
                                if (Boolean.TRUE.equals(teleported)) {
                                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                                    player.setFallDistance(0.0f);
                                }
                            });
                        }
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize(
                                        "<red><bold>QUARANTINE:</bold> This island has been placed under administrative freeze. "
                                                + "Reason: <yellow>"
                                                + (reason != null ? reason : "Administrative quarantine")
                                                + "</yellow></red>"));
                    }
                });
            }
        }
    }

    private boolean isStaffOrBypass(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission("uxmskyblock.admin.inspect")
                || player.hasPermission("uxmskyblock.admin.freeze");
    }
}
