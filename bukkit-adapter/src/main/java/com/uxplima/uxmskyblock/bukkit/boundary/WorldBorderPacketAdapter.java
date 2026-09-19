package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.boundary.WorldBorderPacketPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Bukkit/Paper adapter for per-player clientbound virtual WorldBorder packets.
 */
public final class WorldBorderPacketAdapter implements WorldBorderPacketPort {

    private final SchedulerPort schedulerPort;

    public WorldBorderPacketAdapter(SchedulerPort schedulerPort) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void sendWorldBorder(
            PlayerUuid playerUuid,
            int centerX,
            int centerZ,
            double radius,
            double oldRadius,
            long transitionDurationMs) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player == null || !player.isOnline()) {
                return;
            }
            WorldBorder border = Bukkit.createWorldBorder();
            border.setCenter(centerX + 0.5, centerZ + 0.5);
            if (oldRadius > 0.0 && transitionDurationMs > 0L) {
                border.setSize(oldRadius * 2.0);
                border.setSize(radius * 2.0, Math.max(1L, transitionDurationMs / 1000L));
            } else {
                border.setSize(radius * 2.0);
            }
            player.setWorldBorder(border);
        });
    }

    @Override
    public void resetWorldBorder(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player != null && player.isOnline()) {
                player.setWorldBorder(null);
            }
        });
    }
}
