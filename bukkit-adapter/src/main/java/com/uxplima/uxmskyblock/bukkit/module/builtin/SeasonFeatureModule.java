package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import org.jspecify.annotations.Nullable;

/**
 * Built-in internal feature module managing automated seasons, leaderboard snapshots,
 * and competitive payout queuing.
 */
public final class SeasonFeatureModule extends AbstractFeatureModule {

    private final IslandSeasonService seasonService;
    private final SchedulerPort scheduler;
    private final SeasonConfiguration configuration;
    private @Nullable AutoCloseable scheduledTask;

    public SeasonFeatureModule(
            IslandSeasonService seasonService, SchedulerPort scheduler, SeasonConfiguration configuration) {
        super(new ModuleDescriptor(
                "seasons", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-seasons"), ">=1.0.0", false));
        this.seasonService = Objects.requireNonNull(seasonService, "seasonService must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandSeasonService.class, seasonService);

        // Ensure active season exists
        if (seasonService.activeSeason().isEmpty()) {
            Instant now = Instant.now();
            seasonService.startSeason(
                    SeasonId.of(configuration.seasonNumber()),
                    configuration.seasonName(),
                    now,
                    now.plus(configuration.duration()));
        }

        // Schedule periodic evaluation
        this.scheduledTask = scheduler.repeatAsync(
                () -> seasonService.checkAndAdvanceSeason(
                        Instant.now(), configuration.tierRewards(), configuration.typedTierRewards()),
                Duration.ofSeconds(5),
                configuration.checkInterval());
    }

    @Override
    protected void onDisable() {
        if (scheduledTask != null) {
            try {
                scheduledTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            scheduledTask = null;
        }
    }
}
