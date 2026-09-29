package com.uxplima.uxmskyblock.bukkit.bootstrap;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;

/**
 * Registers Bukkit event listeners for Skyblock subsystems based on enabled feature modules and configurations.
 */
public final class BootstrapEventRegistrar {

    private BootstrapEventRegistrar() {}

    public static void registerEvents(
            PluginManager pm,
            JavaPlugin plugin,
            FeatureModuleWiring featureModuleWiring,
            GameplayWiring gameplayWiring,
            AuthorityWiring authorityWiring,
            ConfigurationWiring configWiring) {
        CatalogPermissions.registerAll(pm);
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("core")) {
            pm.registerEvents(gameplayWiring.protectionListener(), plugin);
            pm.registerEvents(gameplayWiring.playerLifecycle(), plugin);
            // The island permissions the protection rules never asked for. They belong with the
            // protection listener, not behind a switch for something else.
            pm.registerEvents(gameplayWiring.actionPermissionListener(), plugin);
            pm.registerEvents(authorityWiring.sessionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("chat") && gameplayWiring.chatListener() != null) {
            pm.registerEvents(gameplayWiring.chatListener(), plugin);
        }
        if (gameplayWiring.chunkBlockWiring().enabled()) {
            pm.registerEvents(gameplayWiring.chunkBlockWiring().listener(), plugin);
        }
        if (gameplayWiring.boxedWiring().enabled()) {
            pm.registerEvents(gameplayWiring.boxedWiring().listener(), plugin);
        }
        // Whatever mode sealed a player, what they hold stays where it was made.
        pm.registerEvents(new com.uxplima.uxmskyblock.bukkit.creative.SealedInventoryGuard(), plugin);
        if (gameplayWiring.brixWiring().enabled()) {
            pm.registerEvents(gameplayWiring.brixWiring().modes(), plugin);
            pm.registerEvents(gameplayWiring.brixWiring().rules(), plugin);
        }
        if (gameplayWiring.parkourWiring().enabled()) {
            pm.registerEvents(gameplayWiring.parkourWiring().runs(), plugin);
            pm.registerEvents(gameplayWiring.parkourWiring().modes(), plugin);
        }
        if (gameplayWiring.strangerRealmsWiring().enabled()) {
            pm.registerEvents(gameplayWiring.strangerRealmsWiring().spawns(), plugin);
            if (gameplayWiring.strangerRealmsWiring().config().compass().enabled()) {
                pm.registerEvents(gameplayWiring.strangerRealmsWiring().compass(), plugin);
                gameplayWiring.strangerRealmsWiring().addRecipe(plugin.getServer());
            }
            if (gameplayWiring.strangerRealmsWiring().config().glimmer().enabled()) {
                pm.registerEvents(gameplayWiring.strangerRealmsWiring().glimmer(), plugin);
            }
        }
        if (gameplayWiring.acidIslandWiring().enabled()) {
            pm.registerEvents(gameplayWiring.acidIslandWiring().waterListener(), plugin);
            gameplayWiring.acidIslandWiring().addRecipes(plugin.getServer());
        }
        if (gameplayWiring.oneBlockWiring().enabled()) {
            pm.registerEvents(gameplayWiring.oneBlockWiring().listener(), plugin);
        }
        if (gameplayWiring.vaultListener() != null) {
            pm.registerEvents(gameplayWiring.vaultListener(), plugin);
        }
        {
            pm.registerEvents(gameplayWiring.notificationListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("social")) {
            pm.registerEvents(gameplayWiring.visitRecorder(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("missions")
                && gameplayWiring.missionListener() != null) {
            pm.registerEvents(gameplayWiring.missionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boundary")
                && gameplayWiring.boundaryListener() != null) {
            pm.registerEvents(gameplayWiring.boundaryListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("worth") && gameplayWiring.worthListener() != null) {
            pm.registerEvents(gameplayWiring.worthListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("dimensions")
                && gameplayWiring.dimensionListener() != null) {
            pm.registerEvents(gameplayWiring.dimensionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("limits") && gameplayWiring.limitListener() != null) {
            pm.registerEvents(gameplayWiring.limitListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("anti-abuse")
                && gameplayWiring.antiAbuseListener() != null) {
            pm.registerEvents(gameplayWiring.antiAbuseListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boosters")
                && gameplayWiring.boosterListener() != null) {
            pm.registerEvents(gameplayWiring.boosterListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("bank-upkeep")
                && gameplayWiring.bankruptcyListener() != null) {
            pm.registerEvents(gameplayWiring.bankruptcyListener(), plugin);
        }
        if (configWiring.protectionConfig().obsidianRecoveryEnabled()
                && configWiring.settingsConfig().obsidianToLava()) {
            pm.registerEvents(gameplayWiring.obsidianRecoveryListener(), plugin);
        }
        if (configWiring.protectionConfig().voidRecoveryEnabled()
                && (configWiring.settingsConfig().voidTeleportMembers()
                        || configWiring.settingsConfig().voidTeleportVisitors())) {
            pm.registerEvents(gameplayWiring.voidProtectionListener(), plugin);
        }
        if (!configWiring.interactablesConfig().isEmpty()) {
            pm.registerEvents(gameplayWiring.categoricalInteractablesListener(), plugin);
        }
        if (configWiring.protectionConfig().kineticWardEnabled()) {
            pm.registerEvents(gameplayWiring.kineticWardListener(), plugin);
        }
        if (configWiring.settingsConfig().disableRedstoneOffline()) {
            pm.registerEvents(gameplayWiring.redstoneOptimizationListener(), plugin);
        }
        if (!configWiring.worldConfig().suppressedStructures().isEmpty()) {
            pm.registerEvents(gameplayWiring.structureSuppressionListener(), plugin);
        }
    }
}
