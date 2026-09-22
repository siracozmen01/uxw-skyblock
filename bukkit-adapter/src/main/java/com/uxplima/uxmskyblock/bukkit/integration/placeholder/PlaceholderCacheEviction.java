package com.uxplima.uxmskyblock.bukkit.integration.placeholder;

import java.util.Objects;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Forgets a player's placeholder line when they leave.
 *
 * <p>Nothing called {@link SkyblockPlaceholderExpansion#invalidate} on a running server, so the cache
 * kept one line for every player who had ever joined for as long as the server ran. A player who
 * comes back is read again on their first placeholder, which is what happens on a first join too.
 *
 * <p>This is its own listener rather than a handler on the expansion, because the expansion reads
 * storage when it refreshes a line and a listener's every method runs on the event's thread.
 */
public final class PlaceholderCacheEviction implements Listener {

    private final SkyblockPlaceholderExpansion expansion;

    public PlaceholderCacheEviction(SkyblockPlaceholderExpansion expansion) {
        this.expansion = Objects.requireNonNull(expansion, "expansion must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        expansion.invalidate(event.getPlayer().getUniqueId());
    }
}
