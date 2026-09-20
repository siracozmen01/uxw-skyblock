package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing island missions, quest trees, and progression milestones.
 */
public final class MissionFeatureModule extends AbstractFeatureModule {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(MissionFeatureModule.class.getName());

    private final IslandMissionService missionService;
    private final MissionConfiguration configuration;
    private final SchedulerPort scheduler;
    private @Nullable AutoCloseable flushTaskHandle;

    public MissionFeatureModule(
            IslandMissionService missionService, MissionConfiguration configuration, SchedulerPort scheduler) {
        super(new ModuleDescriptor(
                "missions",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-missions", "island-quests"),
                ">=1.0.0",
                false));
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandMissionService.class, missionService);
        try {
            this.flushTaskHandle = scheduler.repeatAsync(
                    missionService::flushDirtyProgress, Duration.ofSeconds(15), Duration.ofSeconds(15));
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.SEVERE, "Failed to schedule mission dirty progress flusher", e);
            throw new IllegalStateException("Failed to schedule mission dirty progress flusher", e);
        }
    }

    @Override
    protected void onDisable() {
        if (flushTaskHandle != null) {
            try {
                flushTaskHandle.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Failed to close mission flush task handle: " + e.getMessage());
            }
            flushTaskHandle = null;
        }
        missionService.flushDirtyProgress();
    }

    public MissionConfiguration configuration() {
        return configuration;
    }
}
