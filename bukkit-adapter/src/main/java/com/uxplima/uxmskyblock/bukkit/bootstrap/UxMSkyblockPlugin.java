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

    /** This plugin's id on bStats. */
    private static final int METRICS_ID = 34261;

    private @Nullable SkyblockBootstrap bootstrap;

    private volatile boolean serverIsUp;

    @Override
    public void onEnable() {
        this.bootstrap = SkyblockBootstrap.createDefault(this);
        this.bootstrap.enable();
        AfterStartup.run(this, this::whenServerIsUp);
        getLogger().info("UXPLIMA Skyblock initialized successfully.");
        new org.bstats.bukkit.Metrics(this, METRICS_ID);
    }

    /** The half of enabling that needs the worlds and the other plugins. See {@link AfterStartup}. */
    private void whenServerIsUp() {
        // uxmLib is relocated into this jar, so no other plugin installs the skill source for this
        // one. mcMMO has enabled by now, so the source finds it.
        Operands.readingSkills(ContentHooks.skillLevels(getServer()));
        SkyblockBootstrap booted = bootstrap();
        booted.whenServerIsUp();
        String islandWorld = booted.nodeConfiguration().worldName();
        IslandWorldCheck.warningFor(islandWorld, getServer().getWorld(islandWorld), getName())
                .ifPresent(getLogger()::warning);
        this.serverIsUp = true;
    }

    /** Whether the half of enabling that waits for the server has run. */
    public boolean serverIsUp() {
        return serverIsUp;
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
