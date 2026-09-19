package com.uxplima.uxmskyblock.bukkit.booster;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import org.jspecify.annotations.Nullable;

/**
 * Inbound Bukkit listener managing booster pause-on-idle lifecycles and in-game multipliers.
 */
public final class IslandBoosterListener implements Listener {

    private final IslandStoragePort islandStoragePort;
    private final IslandBoosterService boosterService;
    private final BoosterConfiguration configuration;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final @Nullable SchedulerPort schedulerPort;
    private final Clock clock;
    private final Map<UUID, IslandId> playerIslandCache = new ConcurrentHashMap<>();

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable SchedulerPort schedulerPort,
            Clock clock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.boosterService = Objects.requireNonNull(boosterService, "boosterService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = schedulerPort;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            Clock clock) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, null, clock);
    }

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable SchedulerPort schedulerPort) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, schedulerPort, Clock.systemUTC());
    }

    public IslandBoosterListener(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, null, Clock.systemUTC());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!configuration.pauseWhenEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Runnable task = () -> {
            findIslandIdForPlayer(playerUuid).ifPresent(islandId -> {
                playerIslandCache.put(playerUuid, islandId);
                int onlineMembers = countOnlineIslandMembers(islandId, null);
                if (onlineMembers == 1) {
                    boosterService.resumeBoosters(islandId, clock.instant());
                }
            });
        };

        if (schedulerPort != null) {
            schedulerPort.async(task);
        } else {
            task.run();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!configuration.pauseWhenEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Runnable task = () -> {
            IslandId islandId = playerIslandCache.remove(playerUuid);
            if (islandId == null) {
                islandId = findIslandIdForPlayer(playerUuid).orElse(null);
            }
            if (islandId != null) {
                int remainingOnline = countOnlineIslandMembers(islandId, playerUuid);
                if (remainingOnline == 0) {
                    boosterService.pauseBoosters(islandId, clock.instant());
                }
            }
        };

        if (schedulerPort != null) {
            schedulerPort.async(task);
        } else {
            task.run();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getDroppedExp() <= 0) {
            return;
        }

        Player killer = event.getEntity().getKiller();
        if (killer == null
                && event.getDamageSource() != null
                && event.getDamageSource().getCausingEntity() instanceof Player p) {
            killer = p;
        }
        if (killer == null) {
            return;
        }

        IslandId islandId = playerIslandCache.get(killer.getUniqueId());
        if (islandId == null) {
            islandId = findIslandIdForPlayer(killer.getUniqueId()).orElse(null);
            if (islandId != null) {
                playerIslandCache.put(killer.getUniqueId(), islandId);
            }
        }
        if (islandId != null) {
            double multiplier =
                    boosterService.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, clock.instant());
            if (multiplier > 1.0) {
                int originalExp = event.getDroppedExp();
                int boostedExp = (int) Math.round(originalExp * multiplier);
                event.setDroppedExp(boostedExp);
            }
        }
    }

    public Optional<IslandId> findIslandIdForPlayer(UUID playerUuid) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(playerUuid);
        if (optProfile.isEmpty()) {
            return Optional.empty();
        }
        return islandStoragePort.findIslandIdByProfileId(optProfile.get());
    }

    public int countOnlineIslandMembers(IslandId islandId, @Nullable UUID excludingPlayerUuid) {
        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return 0;
        }

        Island island = optIsland.get();
        int count = 0;

        // Check owner
        UUID ownerUuid = island.ownerPlayerUuid().value();
        if (!ownerUuid.equals(excludingPlayerUuid) && Bukkit.getPlayer(ownerUuid) != null) {
            count++;
        }

        // Check members
        for (IslandMember member : island.members().values()) {
            UUID memberUuid = member.playerUuid().value();
            if (!memberUuid.equals(ownerUuid) && !memberUuid.equals(excludingPlayerUuid)) {
                if (Bukkit.getPlayer(memberUuid) != null) {
                    count++;
                }
            }
        }

        return count;
    }

    public Clock clock() {
        return clock;
    }
}
