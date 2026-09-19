package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryPoint;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;

/**
 * Enforces boundary physics shielding against fluid spillover, piston pushing across borders,
 * ender pearl boundary breaches, and manages virtual WorldBorder synchronization.
 */
public final class IslandBoundaryListener implements Listener {

    private static final Particle.DustOptions PERIMETER_DUST =
            new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1.0f);

    private final IslandBoundaryService boundaryService;
    private final IslandProtectionListener protectionListener;

    public IslandBoundaryListener(IslandBoundaryService boundaryService, IslandProtectionListener protectionListener) {
        this.boundaryService = Objects.requireNonNull(boundaryService, "boundaryService must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
    }

    public IslandBoundaryListener(
            IslandBoundaryService boundaryService,
            IslandProtectionListener protectionListener,
            @SuppressWarnings("unused") SchedulerPort schedulerPort) {
        this(boundaryService, protectionListener);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Location from = event.getBlock().getLocation();
        Location to = event.getToBlock().getLocation();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Optional<Island> fromIsland = protectionListener.findIslandAt(from);
        if (fromIsland.isPresent()) {
            IslandBounds bounds = fromIsland.get().bounds();
            if (boundaryService.isSpillover(
                    bounds, from.getBlockX(), from.getBlockZ(), to.getBlockX(), to.getBlockZ())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        Optional<Island> optIsland = protectionListener.findIslandAt(piston.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        for (Block block : event.getBlocks()) {
            int toX = block.getX() + direction.getModX();
            int toZ = block.getZ() + direction.getModZ();
            if (boundaryService.isSpillover(bounds, block.getX(), block.getZ(), toX, toZ)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!event.isSticky()) {
            return;
        }
        Block piston = event.getBlock();
        BlockFace direction = event.getDirection();
        Optional<Island> optIsland = protectionListener.findIslandAt(piston.getLocation());
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        for (Block block : event.getBlocks()) {
            int toX = block.getX() + direction.getModX();
            int toZ = block.getZ() + direction.getModZ();
            if (boundaryService.isSpillover(bounds, block.getX(), block.getZ(), toX, toZ)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Optional<Island> fromIsland = protectionListener.findIslandAt(event.getFrom());
        Optional<Island> toIsland = protectionListener.findIslandAt(to);

        if (fromIsland.isPresent() && toIsland.isEmpty()) {
            event.setCancelled(true);
            event.getPlayer()
                    .sendMessage(MiniMessage.miniMessage()
                            .deserialize("<red>Ender pearls cannot be thrown beyond island boundaries!</red>"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        Optional<Island> fromIsland = protectionListener.findIslandAt(from);
        Optional<Island> toIsland = protectionListener.findIslandAt(to);
        PlayerUuid playerUuid = new PlayerUuid(event.getPlayer().getUniqueId());

        if (fromIsland.isEmpty() && toIsland.isPresent()) {
            boundaryService.handlePlayerEnterIsland(playerUuid, toIsland.get().bounds());
        } else if (fromIsland.isPresent() && toIsland.isEmpty()) {
            boundaryService.handlePlayerExitIsland(playerUuid);
        } else if (fromIsland.isPresent()
                && toIsland.isPresent()
                && !fromIsland.get().id().equals(toIsland.get().id())) {
            boundaryService.handlePlayerEnterIsland(playerUuid, toIsland.get().bounds());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerUuid uuid = new PlayerUuid(event.getPlayer().getUniqueId());
        boundaryService.disablePerimeter(uuid);
    }

    /**
     * Renders particle perimeter outline for a specific player if they have border projection enabled.
     */
    public void renderPerimeterForPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        if (!boundaryService.isPerimeterActive(uuid)) {
            return;
        }

        Location loc = player.getLocation();
        Optional<Island> optIsland = protectionListener.findIslandAt(loc);
        if (optIsland.isEmpty()) {
            return;
        }

        IslandBounds bounds = optIsland.get().bounds();
        double playerY = loc.getY();
        double playerX = loc.getX();
        double playerZ = loc.getZ();

        List<IslandBoundaryPoint> points = boundaryService.calculatePerimeterPoints(bounds, playerY, 2);
        for (IslandBoundaryPoint pt : points) {
            double dx = pt.x() - playerX;
            double dz = pt.z() - playerZ;
            // Only spawn particles within 48 blocks of player for performance
            if (dx * dx + dz * dz <= 2304.0) {
                player.spawnParticle(Particle.DUST, pt.x(), pt.y(), pt.z(), 1, 0.0, 0.0, 0.0, 0.0, PERIMETER_DUST);
                // Also spawn at eye height + 1 block for visibility
                player.spawnParticle(
                        Particle.DUST, pt.x(), pt.y() + 1.2, pt.z(), 1, 0.0, 0.0, 0.0, 0.0, PERIMETER_DUST);
            }
        }
    }
}
