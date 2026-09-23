package com.uxplima.uxmskyblock.bukkit.listener;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import org.jspecify.annotations.Nullable;

/**
 * Records a visit when a player arrives on an island that is not theirs.
 *
 * <p>A rating needs the rater to have visited the island for the configured dwell time, and nothing
 * ever recorded a visit: the social service could store one and no listener called it, so with the
 * shipped thirty second dwell time nobody could rate any island at all. An arrival is noticed on
 * foot and by teleport, which is how most visitors come, and it is written once per arrival, never
 * on every step.
 */
public final class IslandVisitRecorder implements Listener {

    private static final Logger LOGGER = Logger.getLogger(IslandVisitRecorder.class.getName());

    private final Function<Location, Optional<Island>> islandAt;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final IslandSocialService social;
    private final SchedulerPort scheduler;
    private final Clock clock;

    /** The island each player was last seen on, so a walk across it is one arrival. */
    private final Map<UUID, IslandId> lastSeenOn = new ConcurrentHashMap<>();

    public IslandVisitRecorder(
            Function<Location, Optional<Island>> islandAt,
            Function<UUID, Optional<ProfileId>> activeProfile,
            IslandSocialService social,
            SchedulerPort scheduler,
            Clock clock) {
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.social = Objects.requireNonNull(social, "social must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        arrive(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        arrive(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSeenOn.remove(event.getPlayer().getUniqueId());
    }

    private void arrive(Player player, @Nullable Location to) {
        UUID playerId = player.getUniqueId();
        Optional<Island> here = to == null ? Optional.empty() : islandAt.apply(to);
        if (here.isEmpty()) {
            lastSeenOn.remove(playerId);
            return;
        }
        Island island = here.get();
        if (island.id().equals(lastSeenOn.put(playerId, island.id()))) {
            return;
        }
        Optional<ProfileId> profile = activeProfile.apply(playerId);
        if (profile.isEmpty() || island.ownerProfileId().equals(profile.get()) || island.isMember(profile.get())) {
            return;
        }
        SocialSubjectRef subject = SocialSubjectRef.island(island.id());
        ProfileId visitor = profile.get();
        java.time.Instant at = clock.instant();
        scheduler.async(() -> {
            try {
                social.recordVisit(subject, visitor, at);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not record a visit to island " + island.id(), e);
            }
        });
    }
}
