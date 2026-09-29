package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * How a course is played: its own team builds it in one game mode, and everybody running it, and
 * everybody else on it, plays in another. A player who leaves a course, or the server, has the mode
 * they came with back.
 *
 * <p>A beat hands each player to their own thread, where their mode is set; a run that starts sets it
 * at once rather than waiting for the beat.
 */
public final class ParkourModes implements Listener {

    private final ParkourService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration.Modes modes;
    private final Predicate<Player> running;
    private final Map<UUID, GameMode> cameWith = new ConcurrentHashMap<>();

    /** @param running whether the player has a run under way */
    public ParkourModes(
            ParkourService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            ParkourConfiguration.Modes modes,
            Predicate<Player> running) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.modes = Objects.requireNonNull(modes, "modes must not be null");
        this.running = Objects.requireNonNull(running, "running must not be null");
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
    }

    private void round() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> check(player));
        }
        cameWith.keySet().retainAll(online);
    }

    /** Sets the player's mode for where they stand, on their own thread. Returns the mode they are in. */
    public GameMode check(Player player) {
        GameMode now = player.getGameMode();
        if (now == GameMode.SPECTATOR
                || (!modes.keepPermission().isEmpty() && player.hasPermission(modes.keepPermission()))) {
            cameWith.remove(player.getUniqueId());
            return now;
        }
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islands.findIslandAt(at);
        if (island.isEmpty() || !service.isCourse(island.get().id())) {
            return giveBack(player);
        }
        GameMode wanted = running.test(player) || !isTeam(island.get(), player) ? modes.play() : modes.build();
        if (now != wanted) {
            cameWith.putIfAbsent(player.getUniqueId(), now);
            player.setGameMode(wanted);
        }
        return wanted;
    }

    /** Gives a player who is leaving the server the mode they came to the course with. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        giveBack(event.getPlayer());
    }

    private GameMode giveBack(Player player) {
        GameMode was = cameWith.remove(player.getUniqueId());
        if (was != null && player.getGameMode() != was) {
            player.setGameMode(was);
        }
        return player.getGameMode();
    }

    private static boolean isTeam(Island island, Player player) {
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        return island.members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(who));
    }
}
