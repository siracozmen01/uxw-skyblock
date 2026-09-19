package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing flexible island boosters and multipliers (Section 2.34).
 */
public final class BoosterFeatureModule extends AbstractFeatureModule {

    private final IslandBoosterService boosterService;
    private final BoosterConfiguration configuration;
    private final com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler;
    private AutoCloseable purgeTask;

    public BoosterFeatureModule(
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler) {
        super(new ModuleDescriptor(
                "boosters",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-boosters", "multipliers", "pause-on-idle"),
                ">=1.0.0",
                false));
        this.boosterService = Objects.requireNonNull(boosterService, "boosterService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandBoosterService.class, boosterService);
        context.registerService(BoosterConfiguration.class, configuration);

        this.purgeTask = scheduler.repeatAsync(
                () -> boosterService.purgeExpired(java.time.Instant.now()),
                configuration.cleanInterval(),
                configuration.cleanInterval());
    }

    @Override
    protected void onDisable() {
        if (purgeTask != null) {
            try {
                purgeTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            purgeTask = null;
        }
    }

    public IslandBoosterService boosterService() {
        return boosterService;
    }

    public BoosterConfiguration configuration() {
        return configuration;
    }
}
