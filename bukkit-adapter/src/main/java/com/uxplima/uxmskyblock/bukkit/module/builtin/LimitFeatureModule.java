package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing hardware and anti-lag tile entity and entity limits (Section 2.31).
 */
public final class LimitFeatureModule extends AbstractFeatureModule {

    private final IslandLimitService limitService;

    public LimitFeatureModule(IslandLimitService limitService) {
        super(new ModuleDescriptor(
                "limits",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-limits", "anti-lag", "tile-limits", "entity-limits"),
                ">=1.0.0",
                false));
        this.limitService = Objects.requireNonNull(limitService, "limitService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandLimitService.class, limitService);
    }

    public IslandLimitService limitService() {
        return limitService;
    }
}
