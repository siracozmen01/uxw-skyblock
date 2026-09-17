package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Inbound Bukkit listener managing player session registration and active profile mappings.
 */
public final class PlayerSessionListener implements Listener {

    private final IslandProtectionListener protectionListener;

    public PlayerSessionListener(IslandProtectionListener protectionListener) {
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        // Default initial profile matches player UUID
        ProfileId profileId = new ProfileId(player.getUniqueId());
        protectionListener.setActiveProfile(uuid, profileId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        protectionListener.removeActiveProfile(uuid);
    }
}
