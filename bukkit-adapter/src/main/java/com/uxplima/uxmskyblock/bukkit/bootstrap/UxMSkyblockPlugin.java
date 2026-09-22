package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.content.ContentHooks;
import com.uxplima.uxmlib.content.Operands;
import org.jspecify.annotations.Nullable;

/**
 * Main Paper / Folia plugin entry point for UXPLIMA Skyblock.
 */
public class UxMSkyblockPlugin extends JavaPlugin {

    private @Nullable SkyblockBootstrap bootstrap;

    @Override
    public void onEnable() {
        // uxmLib is relocated into this jar, so no other plugin installs the skill source for this
        // one. mcMMO loads first, so the source finds it.
        Operands.readingSkills(ContentHooks.skillLevels(getServer()));
        this.bootstrap = SkyblockBootstrap.createDefault(this);
        this.bootstrap.enable();
        getLogger().info("UXPLIMA Skyblock initialized successfully.");
    }

    @Override
    public void onDisable() {
        if (this.bootstrap != null) {
            this.bootstrap.close();
        }
        // A reload that kept the old source would hold the old server's plugins.
        Operands.forgetEverything();
        getLogger().info("UXPLIMA Skyblock disabled.");
    }

    public SkyblockBootstrap bootstrap() {
        return Objects.requireNonNull(bootstrap, "Plugin has not been enabled yet");
    }
}
