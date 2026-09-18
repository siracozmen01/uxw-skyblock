package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in core module managing island lifecycle, spatial boundaries, and identity.
 */
public final class CoreModule extends AbstractFeatureModule {

    private final CreateIslandUseCase createIslandUseCase;

    public CoreModule(CreateIslandUseCase createIslandUseCase) {
        super(new ModuleDescriptor("core", "1.0.0", List.of(), List.of(), List.of("island-core"), ">=1.0.0", true));
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(CreateIslandUseCase.class, createIslandUseCase);
    }
}
