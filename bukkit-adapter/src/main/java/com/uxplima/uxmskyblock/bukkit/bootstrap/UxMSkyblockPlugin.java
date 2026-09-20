package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import org.jspecify.annotations.Nullable;

/**
 * Main Paper / Folia plugin entry point for UXPLIMA Skyblock.
 */
public class UxMSkyblockPlugin extends JavaPlugin {

    private @Nullable SkyblockBootstrap bootstrap;

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
        return Objects.requireNonNull(bootstrap, "Plugin has not been enabled yet");
    }
}
