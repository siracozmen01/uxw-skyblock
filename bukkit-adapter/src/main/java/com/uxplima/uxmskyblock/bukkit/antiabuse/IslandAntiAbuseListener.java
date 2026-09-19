package com.uxplima.uxmskyblock.bukkit.antiabuse;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * Inbound Bukkit listener enforcing island creation quarantine restrictions (Section 2.32):
 * 1. Cancels item dropping within quarantined island bounds.
 * 2. Locks visitor entry to quarantined islands until the quarantine window expires.
 */
public final class IslandAntiAbuseListener implements Listener {

    private final IslandAntiAbuseService antiAbuseService;
    private final AntiAbuseConfiguration configuration;
    private final SpatialIslandIndex spatialIndex;

    public IslandAntiAbuseListener(
            IslandStoragePort islandStoragePort,
            IslandAntiAbuseService antiAbuseService,
            AntiAbuseConfiguration configuration,
            @Nullable SpatialIslandIndex spatialIndex) {
        Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.antiAbuseService = Objects.requireNonNull(antiAbuseService, "antiAbuseService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.spatialIndex = spatialIndex != null ? spatialIndex : new SpatialIslandIndex(islandStoragePort, null);
    }

    public IslandAntiAbuseListener(
            IslandStoragePort islandStoragePort,
            IslandAntiAbuseService antiAbuseService,
            AntiAbuseConfiguration configuration) {
        this(islandStoragePort, antiAbuseService, configuration, null);
    }

    public void cacheIsland(Island island) {
        if (island != null) {
            if (org.bukkit.Bukkit.getServer() != null
                    && !org.bukkit.Bukkit.getWorlds().isEmpty()) {
                for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                    spatialIndex.indexIsland(island, world.getName());
                }
            } else {
                spatialIndex.indexIsland(island, "world");
                spatialIndex.indexIsland(island, "skyblock_world");
            }
        }
    }

    public void invalidateIsland(IslandId islandId) {
        if (islandId != null) {
            spatialIndex.removeIsland(islandId);
        }
    }

    public Optional<Island> findIslandAt(Location location) {
        return spatialIndex.findIslandAt(location);
    }

    private boolean hasBypass(Player player) {
        return player.isOp()
                || player.hasPermission("uxmskyblock.admin.bypass")
                || player.hasPermission(configuration.quarantineBypassPermission());
    }

    private boolean isMemberOrOwner(Island island, UUID playerUuid) {
        if (island.ownerPlayerUuid().value().equals(playerUuid)) {
            return true;
        }
        for (IslandMember member : island.members().values()) {
            if (member.playerUuid().value().equals(playerUuid)) {
                return true;
            }
        }
        return false;
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long remSec = seconds % 60;
        if (remSec == 0) {
            return minutes + "m";
        }
        return minutes + "m " + remSec + "s";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) {
            return;
        }

        findIslandAt(player.getLocation()).ifPresent(island -> {
            Instant now = antiAbuseService.clock().instant();
            if (antiAbuseService.isIslandQuarantined(island.id(), now)) {
                event.setCancelled(true);
                Duration remaining = antiAbuseService
                        .getQuarantineRemaining(island.id(), now)
                        .orElse(Duration.ZERO);
                player.sendMessage(MiniMessage.miniMessage()
                        .deserialize(
                                "<red><bold>QUARANTINE:</bold> Item dropping is disabled on this island during starter quarantine (<yellow>"
                                        + formatDuration(remaining)
                                        + "</yellow> remaining).</red>"));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (hasBypass(player)) {
            return;
        }

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }

        findIslandAt(to).ifPresent(island -> {
            Instant now = antiAbuseService.clock().instant();
            if (antiAbuseService.isIslandQuarantined(island.id(), now)) {
                if (!isMemberOrOwner(island, player.getUniqueId())) {
                    event.setCancelled(true);
                    Duration remaining = antiAbuseService
                            .getQuarantineRemaining(island.id(), now)
                            .orElse(Duration.ZERO);
                    player.sendMessage(MiniMessage.miniMessage()
                            .deserialize(
                                    "<red><bold>QUARANTINE:</bold> Visitor access to this island is locked during starter quarantine (<yellow>"
                                            + formatDuration(remaining)
                                            + "</yellow> remaining).</red>"));
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        if (hasBypass(player)) {
            return;
        }

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }

        findIslandAt(to).ifPresent(island -> {
            Instant now = antiAbuseService.clock().instant();
            if (antiAbuseService.isIslandQuarantined(island.id(), now)) {
                if (!isMemberOrOwner(island, player.getUniqueId())) {
                    // Check if from was already inside to avoid spamming if already stuck
                    Optional<Island> fromIsland = findIslandAt(event.getFrom());
                    if (fromIsland.isEmpty() || !fromIsland.get().id().equals(island.id())) {
                        event.setCancelled(true);
                        Duration remaining = antiAbuseService
                                .getQuarantineRemaining(island.id(), now)
                                .orElse(Duration.ZERO);
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize(
                                        "<red><bold>QUARANTINE:</bold> Visitor access to this island is locked during starter quarantine (<yellow>"
                                                + formatDuration(remaining)
                                                + "</yellow> remaining).</red>"));
                    }
                }
            }
        });
    }
}
