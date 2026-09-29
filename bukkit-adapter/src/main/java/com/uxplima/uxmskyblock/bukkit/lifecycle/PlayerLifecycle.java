package com.uxplima.uxmskyblock.bukkit.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent;

/**
 * Carries out on players what {@code modules/lifecycle.conf} says a leave, a kick, a death and a
 * reset do.
 *
 * <p>A leave, a kick and a reset are owed first and paid on the player's own thread if they are on
 * this server, and otherwise when their next session is made on any. A death is answered in the death
 * event itself, and a death that sends to spawn does so when the player respawns.
 */
public final class PlayerLifecycle implements Listener {

    private static final Logger LOGGER = Logger.getLogger(PlayerLifecycle.class.getName());

    private final LifecycleService service;
    private final SchedulerPort scheduler;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Set<UUID> respawnAtSpawn = ConcurrentHashMap.newKeySet();

    public PlayerLifecycle(
            LifecycleService service, SchedulerPort scheduler, Function<UUID, Optional<ProfileId>> activeProfile) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
    }

    /**
     * A leave, a kick or a reset happened to a player. Called off every player's thread, once the
     * change it follows has been written.
     */
    public void happened(LifecycleEvent event, PlayerUuid player, ProfileId profile) {
        Set<LifecycleEffect> effects;
        try {
            effects = service.owe(event, player, profile);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not record what a " + event + " owes " + player + ".");
            return;
        }
        Player online = Bukkit.getPlayer(player.value());
        if (!effects.isEmpty() && online != null) {
            scheduler.onEntity(player, () -> pay(online, effects));
        }
    }

    /** Reads the player's mode and ruleset ahead of a death, and pays anything still owed. */
    public void onSessionActive(Player player) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        scheduler.async(() -> {
            try {
                profile.ifPresent(service::learn);
                Set<LifecycleEffect> owed = service.owed(playerUuid);
                if (!owed.isEmpty()) {
                    scheduler.onEntity(playerUuid, () -> pay(player, owed));
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Could not read what " + player.getName() + " is owed.");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Optional<ProfileId> profile = activeProfile.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            return;
        }
        Set<LifecycleEffect> effects = service.onDeath(profile.get());
        if (effects.contains(LifecycleEffect.KEEP_INVENTORY)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        }
        if (effects.contains(LifecycleEffect.KEEP_EXPERIENCE)) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        // Emptying beats keeping: what is cleared is gone, neither kept nor dropped.
        if (effects.contains(LifecycleEffect.CLEAR_INVENTORY)) {
            event.setKeepInventory(false);
            event.getDrops().clear();
        }
        if (effects.contains(LifecycleEffect.RESET_EXPERIENCE)) {
            event.setKeepLevel(false);
            event.setDroppedExp(0);
            event.setNewExp(0);
            event.setNewLevel(0);
            event.setNewTotalExp(0);
        }
        if (effects.contains(LifecycleEffect.CLEAR_ENDER_CHEST)) {
            player.getEnderChest().clear();
        }
        if (effects.contains(LifecycleEffect.SEND_TO_SPAWN)) {
            respawnAtSpawn.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (respawnAtSpawn.remove(event.getPlayer().getUniqueId())) {
            event.setRespawnLocation(spawn());
        }
    }

    /** Applies the effects on the player's own thread and records them paid. */
    void pay(Player player, Set<LifecycleEffect> effects) {
        if (!player.isOnline()) {
            return;
        }
        if (effects.contains(LifecycleEffect.CLEAR_INVENTORY)) {
            player.getInventory().clear();
        }
        if (effects.contains(LifecycleEffect.CLEAR_ENDER_CHEST)) {
            player.getEnderChest().clear();
        }
        if (effects.contains(LifecycleEffect.RESET_EXPERIENCE)) {
            player.setExp(0.0f);
            player.setLevel(0);
            player.setTotalExperience(0);
        }
        if (effects.contains(LifecycleEffect.SEND_TO_SPAWN)) {
            var unused = player.teleportAsync(spawn());
        }
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        scheduler.async(() -> service.settle(playerUuid, effects));
    }

    /** The spawn of the server's first world, which is where a player with nowhere else goes. */
    private static Location spawn() {
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }
}
