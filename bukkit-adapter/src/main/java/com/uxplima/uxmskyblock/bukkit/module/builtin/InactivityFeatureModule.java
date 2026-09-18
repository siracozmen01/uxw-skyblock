package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module orchestrating automated leader inactivity scanning,
 * succession hierarchy execution, and island abandonment lifecycle.
 */
public final class InactivityFeatureModule extends AbstractFeatureModule {

    private final IslandInactivityService inactivityService;
    private final SchedulerPort scheduler;
    private final InactivityConfiguration configuration;
    private final String worldName;
    private @Nullable AutoCloseable scanTask;

    public InactivityFeatureModule(
            IslandInactivityService inactivityService,
            SchedulerPort scheduler,
            InactivityConfiguration configuration,
            String worldName) {
        super(new ModuleDescriptor(
                "inactivity",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-inactivity", "island-succession"),
                ">=1.0.0",
                false));
        this.inactivityService = Objects.requireNonNull(inactivityService, "inactivityService must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandInactivityService.class, inactivityService);

        if (configuration.enabled()) {
            this.scanTask = scheduler.repeatAsync(
                    () -> inactivityService.scanWorld(worldName, Instant.now()),
                    Duration.ofSeconds(60),
                    configuration.checkInterval());
        }
    }

    @Override
    protected void onDisable() {
        if (scanTask != null) {
            try {
                scanTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            scanTask = null;
        }
    }
}
