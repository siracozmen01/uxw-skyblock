package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing island missions, quest trees, and progression milestones.
 */
public final class MissionFeatureModule extends AbstractFeatureModule {

    private final IslandMissionService missionService;
    private final MissionConfiguration configuration;

    public MissionFeatureModule(IslandMissionService missionService, MissionConfiguration configuration) {
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
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandMissionService.class, missionService);
    }

    @Override
    protected void onDisable() {
        // No persistent background tasks to unbind
    }

    public MissionConfiguration configuration() {
        return configuration;
    }
}
