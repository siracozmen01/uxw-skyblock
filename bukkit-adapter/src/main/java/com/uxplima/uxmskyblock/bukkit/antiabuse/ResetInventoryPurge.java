package com.uxplima.uxmskyblock.bukkit.antiabuse;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Empties a player's inventory after an island reset, when the operator asked for that.
 *
 * <p>The purge needs the player's own thread. A player who left while their island was erased had
 * none, and kept everything they carried into their next island. What a reset owes is now recorded
 * first and paid either at once or when the player's next session is made, on any server.
 */
public final class ResetInventoryPurge {

    private static final Logger LOGGER = Logger.getLogger(ResetInventoryPurge.class.getName());

    private final IslandAntiAbuseService antiAbuse;
    private final SchedulerPort scheduler;

    public ResetInventoryPurge(IslandAntiAbuseService antiAbuse, SchedulerPort scheduler) {
        this.antiAbuse = Objects.requireNonNull(antiAbuse, "antiAbuse must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    /** Pays a purge the player still owes, once their session is made. */
    public void onSessionActive(Player player) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        scheduler.async(() -> {
            try {
                if (antiAbuse.isInventoryPurgeOwed(playerUuid)) {
                    scheduler.onEntity(playerUuid, () -> purgeAndSettle(antiAbuse, scheduler, player));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not read whether " + player.getName() + " owes a purge", e);
            }
        });
    }

    /** Empties the inventory on the player's own thread and records that the purge is paid. */
    public static void purgeAndSettle(IslandAntiAbuseService antiAbuse, SchedulerPort scheduler, Player player) {
        if (!player.isOnline()) {
            return;
        }
        // A player inventory's clear covers the armour and the off hand too. The purge also set the
        // armour to a null array, which the server refuses with a NullPointerException: every reset
        // that purged stopped there, and the player was neither sent to spawn nor told.
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setExp(0.0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        scheduler.async(() -> antiAbuse.settleInventoryPurge(playerUuid));
    }
}
