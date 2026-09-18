package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;
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
    private final SkyblockEconomyBridge economyBridge;
    private final IslandControlMenu controlMenu;
    private final SkyblockPlaceholderExpansion placeholderExpansion;
    private final TransactionalOutboxDispatcher outboxDispatcher;
    private final IslandCommandTree commandTree;
    private final BukkitSkyblockApiBridge apiBridge;
    private final ServerNodeConfiguration nodeConfiguration;
    private final PlayerStateDurabilityConfig playerStateConfig;

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.persistenceBootstrap =
                Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
        this.nodeConfiguration = Objects.requireNonNull(nodeConfiguration, "nodeConfiguration must not be null");
        this.playerStateConfig = Objects.requireNonNull(playerStateConfig, "playerStateConfig must not be null");

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
                playerStateConfig.ambientCheckpointInterval());
        this.sessionListener = new PlayerSessionListener(sessionCoordinator);

        this.biomeAdapter = new BukkitBiomeAdapter(persistenceBootstrap.islandStoragePort(), scheduler, worldName);

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandLeaderboardPort(),
                persistenceBootstrap.islandAuthorityPort(),
                serverNodeId,
                createIslandUseCase,
                sessionCoordinator,
                worldName);

        this.economyBridge = SkyblockEconomyBridge.createDefault(bankService, scheduler);

        this.controlMenu = new IslandControlMenu(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandUpgradeStoragePort(),
                locationService,
                scheduler,
                worldName,
                sessionCoordinator);

        this.placeholderExpansion = new SkyblockPlaceholderExpansion(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandUpgradeStoragePort(),
                persistenceBootstrap.islandLeaderboardPort(),
                scheduler,
                sessionCoordinator);

        this.outboxDispatcher = new TransactionalOutboxDispatcher(
                persistenceBootstrap.outboxPort(), scheduler, serverNodeId.value() + "-outbox");

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
                worldName,
                economyBridge,
                controlMenu);
    }

    public SkyblockBootstrap(
            JavaPlugin plugin, PersistenceBootstrap persistenceBootstrap, ServerNodeConfiguration nodeConfiguration) {
        this(plugin, persistenceBootstrap, nodeConfiguration, PlayerStateDurabilityConfig.defaultPolicy());
    }

    public SkyblockBootstrap(JavaPlugin plugin, PersistenceBootstrap persistenceBootstrap) {
        this(plugin, persistenceBootstrap, ServerNodeConfiguration.of("skyblock-node-default", "world"));
    }

    @SuppressWarnings("EmptyCatch")
    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to create plugin data directory: " + dataDir, e);
        }

        Path configFile = dataDir.resolve("config.conf");
        if (!java.nio.file.Files.exists(configFile)) {
            try (java.io.InputStream in = plugin.getResource("config.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, configFile);
                }
            } catch (Exception ignored) {
            }
        }

        CommentedConfigurationNode root = null;
        if (java.nio.file.Files.exists(configFile)) {
            try {
                root = HoconConfigurationLoader.builder()
                        .path(configFile)
                        .build()
                        .load();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load configuration from: " + configFile, e);
            }
        }

        ServerNodeConfiguration nodeConfig;
        PlayerStateDurabilityConfig playerStateConfig;
        if (root != null) {
            nodeConfig = ServerNodeConfiguration.load(root);
            playerStateConfig = PlayerStateConfigurationAdapter.load(root);
        } else {
            String envNode = System.getProperty("skyblock.node.id", System.getenv("SKYBLOCK_NODE_ID"));
            String nodeId = (envNode != null && !envNode.isBlank()) ? envNode.trim() : "skyblock-node-default";
            nodeConfig = ServerNodeConfiguration.of(nodeId, "world");
            playerStateConfig = PlayerStateDurabilityConfig.defaultPolicy();
        }

        PersistenceBootstrap persistence = resolvePersistence(root, dataDir);
        return new SkyblockBootstrap(plugin, persistence, nodeConfig, playerStateConfig);
    }

    private static PersistenceBootstrap resolvePersistence(@Nullable CommentedConfigurationNode root, Path dataDir) {
        String envJdbc = System.getProperty("skyblock.jdbc.url", System.getenv("SKYBLOCK_JDBC_URL"));
        String envUser = System.getProperty("skyblock.db.user", System.getenv("SKYBLOCK_DB_USER"));
        String envPass = System.getProperty("skyblock.db.password", System.getenv("SKYBLOCK_DB_PASSWORD"));

        if (envJdbc != null && !envJdbc.isBlank()) {
            return PersistenceBootstrap.createRemote(envJdbc.trim(), envUser, envPass, 10);
        }

        if (root != null) {
            CommentedConfigurationNode dbNode = root.node("database");
            String dbType = dbNode.node("type").getString("sqlite");
            if ("remote".equalsIgnoreCase(dbType)) {
                String jdbcUrl = dbNode.node("jdbc-url").getString();
                if (jdbcUrl != null && !jdbcUrl.isBlank()) {
                    String user = dbNode.node("username").getString("");
                    String pass = dbNode.node("password").getString("");
                    int poolSize = dbNode.node("max-pool-size").getInt(10);
                    return PersistenceBootstrap.createRemote(jdbcUrl.trim(), user, pass, poolSize);
                }
            }
        }

        Path dbFile = dataDir.resolve("skyblock.db");
        return PersistenceBootstrap.createSqlite(dbFile);
    }

    public void enable() {
        if (!Guis.isInstalled()) {
            Guis.install(plugin);
        }
        outboxDispatcher.start();
        placeholderExpansion.registerExpansion("uxplima", plugin.getPluginMeta().getVersion());

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

    public PlayerStateDurabilityConfig playerStateConfig() {
        return playerStateConfig;
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

    public SkyblockEconomyBridge economyBridge() {
        return economyBridge;
    }

    public IslandControlMenu controlMenu() {
        return controlMenu;
    }

    public SkyblockPlaceholderExpansion placeholderExpansion() {
        return placeholderExpansion;
    }

    public TransactionalOutboxDispatcher outboxDispatcher() {
        return outboxDispatcher;
    }

    @Override
    public void close() {
        outboxDispatcher.close();
        if (Guis.isInstalled()) {
            Guis.uninstall();
        }
        sessionCoordinator.shutdown();
        apiBridge.unregister();
        persistenceBootstrap.close();
    }
}
