package com.uxplima.uxmskyblock.core.application.module;

import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;

/**
 * Contract for internal Skyblock feature modules with explicit descriptors and deterministic lifecycle.
 */
public interface FeatureModule {

    /**
     * Returns declarative descriptor of identity, version, and dependencies.
     */
    ModuleDescriptor descriptor();

    /**
     * Enables module capabilities and registers domain services.
     */
    void enable(ModuleContext context);

    /**
     * Disables module capabilities cleanly during server shutdown.
     */
    void disable();

    /**
     * Current lifecycle state of the module.
     */
    ModuleState state();
}
