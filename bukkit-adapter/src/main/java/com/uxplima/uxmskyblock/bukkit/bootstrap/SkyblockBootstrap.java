package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Platform composition root wiring application services, outbound adapters,
 * listeners, and commands for the Bukkit runtime.
 */
public final class SkyblockBootstrap implements AutoCloseable {

    private final JavaPlugin plugin;
    private final PersistenceBootstrap persistenceBootstrap;
    private final IslandAccessService accessService;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final IslandProtectionListener protectionListener;
    private final PlayerSessionListener sessionListener;
    private final BukkitBiomeAdapter biomeAdapter;
    private final IslandCommandTree commandTree;
    private final BukkitSkyblockApiBridge apiBridge;

    public SkyblockBootstrap(JavaPlugin plugin, PersistenceBootstrap persistenceBootstrap) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.persistenceBootstrap = Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap");

        this.accessService = new IslandAccessService();
        this.presetCatalog = new StarterPresetCatalog();
        this.schematicEngine = new StarterSchematicEngine();
        this.coordinateAllocator = new SpiralGridCoordinateAllocator();

        this.protectionListener = new IslandProtectionListener(persistenceBootstrap.islandStoragePort(), accessService);
        this.sessionListener = new PlayerSessionListener(protectionListener);

        String worldName = "world";
        this.biomeAdapter = new BukkitBiomeAdapter(persistenceBootstrap.islandStoragePort(), worldName);

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandLeaderboardPort(),
                persistenceBootstrap.islandAuthorityPort());

        this.commandTree = new IslandCommandTree(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandAuthorityPort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandUpgradeStoragePort(),
                persistenceBootstrap.islandLeaderboardPort(),
                biomeAdapter,
                presetCatalog,
                schematicEngine,
                coordinateAllocator,
                protectionListener,
                worldName);
    }

    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to create plugin data directory: " + dataDir, e);
        }
        Path dbFile = dataDir.resolve("skyblock.db");
        PersistenceBootstrap persistence = PersistenceBootstrap.createSqlite(dbFile);
        return new SkyblockBootstrap(plugin, persistence);
    }

    public void enable() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(protectionListener, plugin);
        pm.registerEvents(sessionListener, plugin);

        commandTree.register(plugin);
        apiBridge.register();
    }

    public PersistenceBootstrap persistenceBootstrap() {
        return persistenceBootstrap;
    }

    public IslandProtectionListener protectionListener() {
        return protectionListener;
    }

    public IslandCommandTree commandTree() {
        return commandTree;
    }

    public BukkitSkyblockApiBridge apiBridge() {
        return apiBridge;
    }

    @Override
    public void close() {
        apiBridge.unregister();
        persistenceBootstrap.close();
    }
}
