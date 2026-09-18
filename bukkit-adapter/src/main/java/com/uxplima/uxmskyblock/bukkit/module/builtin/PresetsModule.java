package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in presets module managing island starter blueprints and schematic generation.
 */
public final class PresetsModule extends AbstractFeatureModule {

    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;

    public PresetsModule(StarterPresetCatalog presetCatalog, StarterSchematicEngine schematicEngine) {
        super(new ModuleDescriptor(
                "presets", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-presets"), ">=1.0.0", false));
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(StarterPresetCatalog.class, presetCatalog);
        context.registerService(StarterSchematicEngine.class, schematicEngine);
    }
}
