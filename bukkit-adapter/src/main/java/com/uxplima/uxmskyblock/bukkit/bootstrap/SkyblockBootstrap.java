package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Platform composition root wiring application services, outbound adapters,
 * listeners, and commands for the Bukkit runtime.
 */
public final class SkyblockBootstrap implements AutoCloseable {

    private final JavaPlugin plugin;
    private final PersistenceBootstrap persistenceBootstrap;
    private final SchedulerPort scheduler;
    private final IslandAccessService accessService;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final SpiralWorldGridService gridService;
    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService locationService;
    private final IslandBankService bankService;
    private final IslandLeaderboardService leaderboardService;
    private final IslandProtectionListener protectionListener;
    private final SwitchProfileUseCase switchProfileUseCase;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final PlayerSessionListener sessionListener;
    private final BukkitBiomeAdapter biomeAdapter;
    private final IslandCommandTree commandTree;
    private final BukkitSkyblockApiBridge apiBridge;
    private final ServerNodeConfiguration nodeConfiguration;

    public SkyblockBootstrap(
            JavaPlugin plugin, PersistenceBootstrap persistenceBootstrap, ServerNodeConfiguration nodeConfiguration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.persistenceBootstrap =
                Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
        this.nodeConfiguration = Objects.requireNonNull(nodeConfiguration, "nodeConfiguration must not be null");

        this.scheduler = new FoliaSchedulerAdapter(plugin);
        this.accessService = new IslandAccessService();
        this.presetCatalog = new StarterPresetCatalog();
        this.schematicEngine = new StarterSchematicEngine();
        this.coordinateAllocator = new SpiralGridCoordinateAllocator();
        this.gridService = new SpiralWorldGridService(coordinateAllocator);

        this.createIslandUseCase = new CreateIslandUseCase(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandAuthorityPort(),
                persistenceBootstrap.islandBankPort(),
                presetCatalog,
                gridService,
                persistenceBootstrap.worldGridAllocationPort());
        this.locationService = new IslandLocationService(persistenceBootstrap.islandStoragePort());
        this.bankService = new IslandBankService(
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandAuthorityPort());
        this.leaderboardService = new IslandLeaderboardService(persistenceBootstrap.islandLeaderboardPort());

        this.protectionListener = new IslandProtectionListener(persistenceBootstrap.islandStoragePort(), accessService);

        String worldName = nodeConfiguration.worldName();
        ServerNodeId serverNodeId = nodeConfiguration.nodeId();

        this.switchProfileUseCase = new SwitchProfileUseCase(
                persistenceBootstrap.profileSwitchPort(), persistenceBootstrap.inventoryPort());
        this.sessionCoordinator = new PlayerSessionCoordinator(
                serverNodeId,
                persistenceBootstrap.sessionAuthorityPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.handoffFinalizationPort(),
                switchProfileUseCase,
                scheduler,
                protectionListener,
                Duration.ofSeconds(5),
                Duration.ofSeconds(60));
        this.sessionListener = new PlayerSessionListener(sessionCoordinator);

        this.biomeAdapter = new BukkitBiomeAdapter(persistenceBootstrap.islandStoragePort(), scheduler, worldName);

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandLeaderboardPort(),
                persistenceBootstrap.islandAuthorityPort(),
                serverNodeId);

        this.commandTree = new IslandCommandTree(
                createIslandUseCase,
                locationService,
                bankService,
                persistenceBootstrap.islandUpgradeStoragePort(),
                leaderboardService,
                biomeAdapter,
                presetCatalog,
                schematicEngine,
                protectionListener,
                sessionCoordinator,
                scheduler,
                serverNodeId,
                worldName);
    }

    public SkyblockBootstrap(JavaPlugin plugin, PersistenceBootstrap persistenceBootstrap) {
        this(plugin, persistenceBootstrap, ServerNodeConfiguration.of("skyblock-node-default", "world"));
    }

    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to create plugin data directory: " + dataDir, e);
        }
        Path configFile = dataDir.resolve("config.conf");
        ServerNodeConfiguration nodeConfig;
        if (java.nio.file.Files.exists(configFile)) {
            try {
                CommentedConfigurationNode root = HoconConfigurationLoader.builder()
                        .path(configFile)
                        .build()
                        .load();
                nodeConfig = ServerNodeConfiguration.load(root);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load server node configuration from: " + configFile, e);
            }
        } else {
            String envNode = System.getProperty("skyblock.node.id", System.getenv("SKYBLOCK_NODE_ID"));
            if (envNode != null && !envNode.isBlank()) {
                nodeConfig = ServerNodeConfiguration.of(envNode.trim(), "world");
            } else {
                nodeConfig = ServerNodeConfiguration.of("skyblock-node-default", "world");
            }
        }
        Path dbFile = dataDir.resolve("skyblock.db");
        PersistenceBootstrap persistence = PersistenceBootstrap.createSqlite(dbFile);
        return new SkyblockBootstrap(plugin, persistence, nodeConfig);
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

    public SchedulerPort scheduler() {
        return scheduler;
    }

    public IslandProtectionListener protectionListener() {
        return protectionListener;
    }

    public IslandCommandTree commandTree() {
        return commandTree;
    }

    public ServerNodeConfiguration nodeConfiguration() {
        return nodeConfiguration;
    }

    public PlayerSessionCoordinator sessionCoordinator() {
        return sessionCoordinator;
    }

    public SwitchProfileUseCase switchProfileUseCase() {
        return switchProfileUseCase;
    }

    public BukkitSkyblockApiBridge apiBridge() {
        return apiBridge;
    }

    @Override
    public void close() {
        sessionCoordinator.shutdown();
        apiBridge.unregister();
        persistenceBootstrap.close();
    }
}
