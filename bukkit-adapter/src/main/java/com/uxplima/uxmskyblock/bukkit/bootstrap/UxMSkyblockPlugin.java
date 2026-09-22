package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.content.ContentHooks;
import com.uxplima.uxmlib.content.Operands;
import com.uxplima.uxmskyblock.bukkit.world.IslandWorldCheck;
import com.uxplima.uxmskyblock.bukkit.world.VoidIslandGenerator;
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
        String islandWorld = this.bootstrap.nodeConfiguration().worldName();
        IslandWorldCheck.warningFor(islandWorld, getServer().getWorld(islandWorld), getName())
                .ifPresent(getLogger()::warning);
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

    /**
     * The island generator, for a world an operator gives it in {@code bukkit.yml}.
     *
     * <p>{@code generator: uxmSkyblock} and {@code generator: uxmSkyblock:void} both name it. Any other
     * id is not ours to answer, and the server makes that world its usual way.
     */
    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, @Nullable String id) {
        if (id == null || id.isBlank() || VoidIslandGenerator.ID.equalsIgnoreCase(id.strip())) {
            return new VoidIslandGenerator();
        }
        getLogger()
                .warning("No island generator is called '" + id + "'. The world '" + worldName
                        + "' is made the server's usual way. Write generator: " + getName() + " for an empty world.");
        return null;
    }

    public SkyblockBootstrap bootstrap() {
        return Objects.requireNonNull(bootstrap, "Plugin has not been enabled yet");
    }
}
