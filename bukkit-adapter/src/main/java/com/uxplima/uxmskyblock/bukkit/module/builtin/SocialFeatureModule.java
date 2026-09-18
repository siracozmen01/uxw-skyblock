package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in internal feature module managing island social discovery, ratings,
 * Bayesian score aggregation, and interactive guestbooks.
 */
public final class SocialFeatureModule extends AbstractFeatureModule {

    private final IslandSocialService socialService;

    public SocialFeatureModule(IslandSocialService socialService) {
        super(new ModuleDescriptor(
                "social", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-social"), ">=1.0.0", false));
        this.socialService = Objects.requireNonNull(socialService, "socialService must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandSocialService.class, socialService);
    }

    @Override
    protected void onDisable() {
        // No asynchronous schedulers or listeners to tear down
    }
}
