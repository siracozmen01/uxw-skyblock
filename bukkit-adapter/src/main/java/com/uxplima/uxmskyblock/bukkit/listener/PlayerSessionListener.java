package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;

/**
 * Inbound Bukkit listener managing player session registration and lifecycle via {@link PlayerSessionCoordinator}.
 */
public final class PlayerSessionListener implements Listener {

    private final PlayerSessionCoordinator sessionCoordinator;

    public PlayerSessionListener(PlayerSessionCoordinator sessionCoordinator) {
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        sessionCoordinator.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessionCoordinator.handlePlayerQuit(event.getPlayer());
    }
}
