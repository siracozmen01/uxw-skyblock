package com.uxplima.uxmskyblock.bukkit.bootstrap;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main Paper / Folia plugin entry point for UXPLIMA Skyblock.
 */
public class UxMSkyblockPlugin extends JavaPlugin {

    private SkyblockBootstrap bootstrap;

    @Override
    public void onEnable() {
        this.bootstrap = SkyblockBootstrap.createDefault(this);
        this.bootstrap.enable();
        getLogger().info("UXPLIMA Skyblock initialized successfully.");
    }

    @Override
    public void onDisable() {
        if (this.bootstrap != null) {
            this.bootstrap.close();
        }
        getLogger().info("UXPLIMA Skyblock disabled.");
    }

    public SkyblockBootstrap bootstrap() {
        return bootstrap;
    }
}
