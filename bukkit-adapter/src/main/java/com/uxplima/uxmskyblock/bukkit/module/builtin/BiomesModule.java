package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;

/**
 * Built-in biomes module managing chunk biome modifications and permissions.
 */
public final class BiomesModule extends AbstractFeatureModule {

    private final BukkitBiomeAdapter biomeAdapter;

    public BiomesModule(BukkitBiomeAdapter biomeAdapter) {
        super(new ModuleDescriptor(
                "biomes", "1.0.0", List.of("core >= 1.0.0"), List.of(), List.of("island-biomes"), ">=1.0.0", false));
        this.biomeAdapter = Objects.requireNonNull(biomeAdapter, "biomeAdapter must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(BukkitBiomeAdapter.class, biomeAdapter);
    }
}
