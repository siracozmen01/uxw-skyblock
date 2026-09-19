package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in feature module managing anti-alt and starter economy protection (Section 2.32).
 */
public final class AntiAbuseFeatureModule extends AbstractFeatureModule {

    private final IslandAntiAbuseService antiAbuseService;

    public AntiAbuseFeatureModule(IslandAntiAbuseService antiAbuseService) {
        super(new ModuleDescriptor(
                "anti-abuse",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("starter-protection", "anti-alt", "coop-hopping-lock"),
                ">=1.0.0",
                false));
        this.antiAbuseService = Objects.requireNonNull(antiAbuseService, "antiAbuseService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandAntiAbuseService.class, antiAbuseService);
    }

    public IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
    }
}
