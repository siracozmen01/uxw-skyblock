package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in upgrades module managing island tiers, perks, and crop/spawner rates.
 */
public final class UpgradesModule extends AbstractFeatureModule {

    private final @Nullable IslandUpgradeService upgradeService;
    private final @Nullable UpgradesConfiguration upgradesConfiguration;
    private final @Nullable GeneratorsConfiguration generatorsConfiguration;
    private final @Nullable OreGeneratorListener oreGeneratorListener;
    private final @Nullable Plugin plugin;

    public UpgradesModule(
            @Nullable IslandUpgradeService upgradeService,
            @Nullable UpgradesConfiguration upgradesConfiguration,
            @Nullable GeneratorsConfiguration generatorsConfiguration,
            @Nullable OreGeneratorListener oreGeneratorListener,
            @Nullable Plugin plugin) {
        super(new ModuleDescriptor(
                "upgrades",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-upgrades"),
                ">=1.0.0",
                false));
        this.upgradeService = upgradeService;
        this.upgradesConfiguration = upgradesConfiguration;
        this.generatorsConfiguration = generatorsConfiguration;
        this.oreGeneratorListener = oreGeneratorListener;
        this.plugin = plugin;
    }

    public UpgradesModule(IslandUpgradeService upgradeService, UpgradesConfiguration upgradesConfiguration) {
        this(upgradeService, upgradesConfiguration, null, null, null);
    }

    public UpgradesModule() {
        this(null, null, null, null, null);
    }

    @Override
    protected void onEnable(ModuleContext context) {
        if (upgradeService != null) {
            context.registerService(IslandUpgradeService.class, upgradeService);
        }
        if (upgradesConfiguration != null) {
            context.registerService(UpgradesConfiguration.class, upgradesConfiguration);
        }
        if (generatorsConfiguration != null) {
            context.registerService(GeneratorsConfiguration.class, generatorsConfiguration);
        }
        if (oreGeneratorListener != null && plugin != null) {
            Bukkit.getPluginManager().registerEvents(oreGeneratorListener, plugin);
        }
    }

    @Override
    protected void onDisable() {
        if (oreGeneratorListener != null) {
            HandlerList.unregisterAll(oreGeneratorListener);
        }
    }

    public @Nullable IslandUpgradeService upgradeService() {
        return upgradeService;
    }

    public @Nullable UpgradesConfiguration upgradesConfiguration() {
        return upgradesConfiguration;
    }

    public @Nullable GeneratorsConfiguration generatorsConfiguration() {
        return generatorsConfiguration;
    }
}
