package com.uxplima.uxmskyblock.core.application.module;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;

/**
 * Base implementation of {@link FeatureModule} managing state transitions and descriptor binding.
 */
public abstract class AbstractFeatureModule implements FeatureModule {

    private final ModuleDescriptor descriptor;
    private volatile ModuleState state = ModuleState.UNINITIALIZED;

    protected AbstractFeatureModule(ModuleDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor cannot be null");
    }

    @Override
    public final ModuleDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public final synchronized void enable(ModuleContext context) {
        try {
            onEnable(context);
            this.state = ModuleState.ENABLED;
        } catch (Exception e) {
            this.state = ModuleState.FAILED;
            throw e;
        }
    }

    @Override
    public final synchronized void disable() {
        if (this.state == ModuleState.ENABLED) {
            try {
                onDisable();
            } finally {
                this.state = ModuleState.DISABLED;
            }
        } else {
            this.state = ModuleState.DISABLED;
        }
    }

    @Override
    public final ModuleState state() {
        return state;
    }

    protected abstract void onEnable(ModuleContext context);

    protected void onDisable() {
        // Default no-op
    }
}
