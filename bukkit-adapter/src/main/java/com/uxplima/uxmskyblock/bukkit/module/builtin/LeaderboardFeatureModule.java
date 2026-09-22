package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.announce.IslandAnnouncer;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the leaderboards built, and tells whoever is listening who is on top.
 *
 * <p>Nothing built a board except the player who asked for one, so the first ask after a restart
 * paid for a sort across every island while that player waited. And the leaderboards topic of the
 * webhook service, which an operator can give a URL, had nothing that ever filled it.
 *
 * <p>The sort happens here, on a schedule, off every thread a player is on. The announcement only
 * goes out when the leading island has actually changed, because a board posted every few minutes
 * saying the same thing is a channel nobody reads.
 */
public final class LeaderboardFeatureModule extends AbstractFeatureModule {

    private static final Logger LOGGER = Logger.getLogger(LeaderboardFeatureModule.class.getName());

    /** How many islands an announcement names. */
    private static final int ANNOUNCED = 5;

    private final IslandLeaderboardService leaderboardService;
    private final SchedulerPort scheduler;
    private final Duration interval;
    private final @Nullable IslandAnnouncer announcer;

    private final java.util.Map<LeaderboardCategory, String> lastAnnouncedLeader =
            new java.util.EnumMap<>(LeaderboardCategory.class);

    private @Nullable AutoCloseable refreshTask;

    public LeaderboardFeatureModule(
            IslandLeaderboardService leaderboardService,
            SchedulerPort scheduler,
            Duration interval,
            @Nullable IslandAnnouncer announcer) {
        super(new ModuleDescriptor(
                "leaderboards",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-leaderboards", "top-islands"),
                ">=1.0.0",
                false));
        this.leaderboardService = Objects.requireNonNull(leaderboardService, "leaderboardService must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        this.announcer = announcer;
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandLeaderboardService.class, leaderboardService);
        this.refreshTask = scheduler.repeatAsync(this::rebuildAndAnnounce, interval, interval);
    }

    /** Builds every board and announces the ones whose leader has changed. Package private for the test. */
    void rebuildAndAnnounce() {
        for (LeaderboardCategory category : LeaderboardCategory.values()) {
            try {
                leaderboardService.refresh(category);
                announceIfTheLeaderChanged(category);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Rebuilding the " + category + " leaderboard failed.");
            }
        }
    }

    private void announceIfTheLeaderChanged(LeaderboardCategory category) {
        IslandAnnouncer listener = this.announcer;
        if (listener == null) {
            return;
        }
        List<LeaderboardEntry> top = leaderboardService.getTop(category, ANNOUNCED);
        if (top.isEmpty()) {
            return;
        }
        String leader = top.get(0).islandId().value().toString();
        if (leader.equals(lastAnnouncedLeader.get(category))) {
            return;
        }
        lastAnnouncedLeader.put(category, leader);
        try {
            listener.notifyLeaderboard(category.name(), top);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Announcing the " + category + " leaderboard failed.");
        }
    }

    @Override
    protected void onDisable() {
        if (refreshTask != null) {
            try {
                refreshTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            refreshTask = null;
        }
    }

    public IslandLeaderboardService leaderboardService() {
        return leaderboardService;
    }
}
