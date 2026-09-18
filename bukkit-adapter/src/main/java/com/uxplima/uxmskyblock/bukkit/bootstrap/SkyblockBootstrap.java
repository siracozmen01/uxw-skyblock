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
import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.integration.discord.JavaHttpClientDiscordAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AllianceFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BiomesModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.CoreModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DiscordFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.PresetsModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SeasonFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ShopFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SocialFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
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
    private final ModuleSettingsConfiguration moduleSettings;
    private final SeasonConfiguration seasonConfig;
    private final IslandSeasonService seasonService;
    private final SocialConfiguration socialConfig;
    private final IslandSocialService socialService;
    private final DiscordConfiguration discordConfig;
    private final IslandDiscordWebhookService discordService;
    private final AllianceConfiguration allianceConfig;
    private final IslandAllianceService allianceService;
    private final ShopConfiguration shopConfig;
    private final DynamicPricingEngine dynamicPricingEngine;
    private final ModuleRegistry moduleRegistry;
    private final BukkitModuleContext moduleContext;

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig,
            DiscordConfiguration discordConfig,
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.persistenceBootstrap =
                Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
        this.nodeConfiguration = Objects.requireNonNull(nodeConfiguration, "nodeConfiguration must not be null");
        this.playerStateConfig = Objects.requireNonNull(playerStateConfig, "playerStateConfig must not be null");
        this.moduleSettings = Objects.requireNonNull(moduleSettings, "moduleSettings must not be null");
        this.seasonConfig = Objects.requireNonNull(seasonConfig, "seasonConfig must not be null");
        this.socialConfig = Objects.requireNonNull(socialConfig, "socialConfig must not be null");
        this.discordConfig = Objects.requireNonNull(discordConfig, "discordConfig must not be null");
        this.allianceConfig = Objects.requireNonNull(allianceConfig, "allianceConfig must not be null");
        this.shopConfig = Objects.requireNonNull(shopConfig, "shopConfig must not be null");

        this.dynamicPricingEngine = new DynamicPricingEngine(shopConfig.dampingFactor());

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
                persistenceBootstrap.worldGridAllocationPort(),
                persistenceBootstrap.outboxPort());
        this.locationService = new IslandLocationService(persistenceBootstrap.islandStoragePort());
        this.bankService = new IslandBankService(
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandAuthorityPort(),
                persistenceBootstrap.outboxPort());
        this.leaderboardService = new IslandLeaderboardService(persistenceBootstrap.islandLeaderboardPort());
        this.seasonService = new IslandSeasonService(
                persistenceBootstrap.islandSeasonStoragePort(),
                persistenceBootstrap.islandLeaderboardPort(),
                persistenceBootstrap.islandStoragePort());
        this.socialService = new IslandSocialService(
                persistenceBootstrap.islandSocialStoragePort(),
                RatingPolicy.standardFiveStar(),
                persistenceBootstrap.islandStoragePort(),
                socialConfig.minDwellTime(),
                socialConfig.priorWeight(),
                socialConfig.priorMean(),
                socialConfig.maxPinned(),
                socialConfig.maxMessageLength());
        this.discordService = new IslandDiscordWebhookService(
                new JavaHttpClientDiscordAdapter(),
                discordConfig.webhookUrls(),
                discordConfig.enabled(),
                discordConfig.botUsername(),
                discordConfig.avatarUrl(),
                discordConfig.rateLimitPerSecond());
        this.allianceService = new IslandAllianceService(
                persistenceBootstrap.islandAllianceStoragePort(),
                allianceConfig.maxAllies(),
                allianceConfig.inviteTimeout(),
                allianceConfig.friendlyFireShielding(),
                allianceConfig.privilegedVisitAccess(),
                allianceConfig.allianceChatEnabled());

        this.protectionListener =
                new IslandProtectionListener(persistenceBootstrap.islandStoragePort(), accessService, allianceService);

        String worldName = nodeConfiguration.worldName();
        ServerNodeId serverNodeId = nodeConfiguration.nodeId();

        this.switchProfileUseCase = new SwitchProfileUseCase(
                persistenceBootstrap.profileSwitchPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.outboxPort());
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

        this.economyBridge =
                SkyblockEconomyBridge.createDefault(bankService, scheduler, persistenceBootstrap.economySagaPort());

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

        this.moduleRegistry = new ModuleRegistry();
        this.moduleContext = new BukkitModuleContext("1.0.0");
        this.moduleRegistry.register(new CoreModule(createIslandUseCase));
        this.moduleRegistry.register(new BankModule(bankService));
        this.moduleRegistry.register(new UpgradesModule());
        this.moduleRegistry.register(new BiomesModule(biomeAdapter));
        this.moduleRegistry.register(new PresetsModule(presetCatalog, schematicEngine));
        this.moduleRegistry.register(new SeasonFeatureModule(seasonService, scheduler, seasonConfig));
        this.moduleRegistry.register(new SocialFeatureModule(socialService));
        this.moduleRegistry.register(new DiscordFeatureModule(discordService));
        this.moduleRegistry.register(new AllianceFeatureModule(allianceService));
        this.moduleRegistry.register(new ShopFeatureModule(dynamicPricingEngine));
        this.moduleRegistry.configure(moduleSettings.moduleToggles(), moduleSettings.selectedProviders());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig,
            DiscordConfiguration discordConfig,
            AllianceConfiguration allianceConfig) {
        this(
                plugin,
                persistenceBootstrap,
                nodeConfiguration,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                socialConfig,
                discordConfig,
                allianceConfig,
                ShopConfiguration.defaultConfiguration());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig,
            DiscordConfiguration discordConfig) {
        this(
                plugin,
                persistenceBootstrap,
                nodeConfiguration,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                socialConfig,
                discordConfig,
                AllianceConfiguration.defaultConfiguration());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig,
            SocialConfiguration socialConfig) {
        this(
                plugin,
                persistenceBootstrap,
                nodeConfiguration,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                socialConfig,
                DiscordConfiguration.defaultConfiguration());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings,
            SeasonConfiguration seasonConfig) {
        this(
                plugin,
                persistenceBootstrap,
                nodeConfiguration,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                SocialConfiguration.defaultConfiguration(),
                DiscordConfiguration.defaultConfiguration());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig,
            ModuleSettingsConfiguration moduleSettings) {
        this(
                plugin,
                persistenceBootstrap,
                nodeConfiguration,
                playerStateConfig,
                moduleSettings,
                SeasonConfiguration.defaultConfiguration(),
                SocialConfiguration.defaultConfiguration());
    }

    public SkyblockBootstrap(
            JavaPlugin plugin,
            PersistenceBootstrap persistenceBootstrap,
            ServerNodeConfiguration nodeConfiguration,
            PlayerStateDurabilityConfig playerStateConfig) {
        this(plugin, persistenceBootstrap, nodeConfiguration, playerStateConfig, ModuleSettingsConfiguration.empty());
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

        Path modulesFile = dataDir.resolve("modules.conf");
        if (!java.nio.file.Files.exists(modulesFile)) {
            try (java.io.InputStream in = plugin.getResource("modules.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, modulesFile);
                }
            } catch (Exception expected) {
                // Ignore failure if modules.conf cannot be extracted
            }
        }

        ModuleSettingsConfiguration moduleSettings;
        if (java.nio.file.Files.exists(modulesFile)) {
            try {
                CommentedConfigurationNode modulesRoot = HoconConfigurationLoader.builder()
                        .path(modulesFile)
                        .build()
                        .load();
                moduleSettings = ModuleSettingsConfiguration.load(modulesRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load modules configuration from: " + modulesFile, e);
            }
        } else {
            moduleSettings = ModuleSettingsConfiguration.empty();
        }

        Path seasonsFile = dataDir.resolve("seasons.conf");
        if (!java.nio.file.Files.exists(seasonsFile)) {
            try (java.io.InputStream in = plugin.getResource("seasons.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, seasonsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if seasons.conf cannot be extracted
            }
        }

        SeasonConfiguration seasonConfig;
        if (java.nio.file.Files.exists(seasonsFile)) {
            try {
                CommentedConfigurationNode seasonsRoot = HoconConfigurationLoader.builder()
                        .path(seasonsFile)
                        .build()
                        .load();
                seasonConfig = SeasonConfiguration.load(seasonsRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load seasons configuration from: " + seasonsFile, e);
            }
        } else {
            seasonConfig = SeasonConfiguration.defaultConfiguration();
        }

        Path socialFile = dataDir.resolve("social.conf");
        if (!java.nio.file.Files.exists(socialFile)) {
            try (java.io.InputStream in = plugin.getResource("social.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, socialFile);
                }
            } catch (Exception expected) {
                // Ignore failure if social.conf cannot be extracted
            }
        }

        SocialConfiguration socialConfig;
        if (java.nio.file.Files.exists(socialFile)) {
            try {
                CommentedConfigurationNode socialRoot = HoconConfigurationLoader.builder()
                        .path(socialFile)
                        .build()
                        .load();
                socialConfig = SocialConfiguration.load(socialRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load social configuration from: " + socialFile, e);
            }
        } else {
            socialConfig = SocialConfiguration.defaultConfiguration();
        }

        Path discordFile = dataDir.resolve("discord.conf");
        if (!java.nio.file.Files.exists(discordFile)) {
            try (java.io.InputStream in = plugin.getResource("discord.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, discordFile);
                }
            } catch (Exception expected) {
                // Ignore failure if discord.conf cannot be extracted
            }
        }

        DiscordConfiguration discordConfig;
        if (java.nio.file.Files.exists(discordFile)) {
            try {
                CommentedConfigurationNode discordRoot = HoconConfigurationLoader.builder()
                        .path(discordFile)
                        .build()
                        .load();
                discordConfig = DiscordConfiguration.load(discordRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load discord configuration from: " + discordFile, e);
            }
        } else {
            discordConfig = DiscordConfiguration.defaultConfiguration();
        }

        Path allianceFile = dataDir.resolve("alliances.conf");
        if (!java.nio.file.Files.exists(allianceFile)) {
            try (java.io.InputStream in = plugin.getResource("alliances.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, allianceFile);
                }
            } catch (Exception expected) {
                // Ignore failure if alliances.conf cannot be extracted
            }
        }

        AllianceConfiguration allianceConfig;
        if (java.nio.file.Files.exists(allianceFile)) {
            try {
                CommentedConfigurationNode allianceRoot = HoconConfigurationLoader.builder()
                        .path(allianceFile)
                        .build()
                        .load();
                allianceConfig = AllianceConfiguration.load(allianceRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load alliance configuration from: " + allianceFile, e);
            }
        } else {
            allianceConfig = AllianceConfiguration.defaultConfiguration();
        }

        Path shopFile = dataDir.resolve("shop.conf");
        if (!java.nio.file.Files.exists(shopFile)) {
            try (java.io.InputStream in = plugin.getResource("shop.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, shopFile);
                }
            } catch (Exception expected) {
                // Ignore failure if shop.conf cannot be extracted
            }
        }

        ShopConfiguration shopConfig;
        if (java.nio.file.Files.exists(shopFile)) {
            try {
                CommentedConfigurationNode shopRoot = HoconConfigurationLoader.builder()
                        .path(shopFile)
                        .build()
                        .load();
                shopConfig = ShopConfiguration.load(shopRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load shop configuration from: " + shopFile, e);
            }
        } else {
            shopConfig = ShopConfiguration.defaultConfiguration();
        }

        return new SkyblockBootstrap(
                plugin,
                persistence,
                nodeConfig,
                playerStateConfig,
                moduleSettings,
                seasonConfig,
                socialConfig,
                discordConfig,
                allianceConfig,
                shopConfig);
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
        moduleRegistry.enableModules(moduleContext);
        protectionListener.loadPersistedIslands(nodeConfiguration.worldName());
        outboxDispatcher.start();
        placeholderExpansion.registerExpansion("uxplima", plugin.getPluginMeta().getVersion());

        PluginManager pm = Bukkit.getPluginManager();
        CatalogPermissions.registerAll(pm);
        pm.registerEvents(protectionListener, plugin);
        pm.registerEvents(sessionListener, plugin);

        commandTree.register(plugin);
        apiBridge.register();
        economyBridge.recoverPendingSagas(nodeConfiguration.nodeId());
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

    public ModuleSettingsConfiguration moduleSettings() {
        return moduleSettings;
    }

    public ModuleRegistry moduleRegistry() {
        return moduleRegistry;
    }

    public BukkitModuleContext moduleContext() {
        return moduleContext;
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

    public IslandSeasonService seasonService() {
        return seasonService;
    }

    public SeasonConfiguration seasonConfiguration() {
        return seasonConfig;
    }

    public IslandSocialService socialService() {
        return socialService;
    }

    public SocialConfiguration socialConfiguration() {
        return socialConfig;
    }

    public IslandDiscordWebhookService discordService() {
        return discordService;
    }

    public DiscordConfiguration discordConfiguration() {
        return discordConfig;
    }

    public IslandAllianceService allianceService() {
        return allianceService;
    }

    public AllianceConfiguration allianceConfiguration() {
        return allianceConfig;
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return dynamicPricingEngine;
    }

    public ShopConfiguration shopConfiguration() {
        return shopConfig;
    }

    @Override
    public void close() {
        moduleRegistry.disableModules();
        discordService.close();
        outboxDispatcher.close();
        if (Guis.isInstalled()) {
            Guis.uninstall();
        }
        sessionCoordinator.shutdown();
        apiBridge.unregister();
        persistenceBootstrap.close();
    }
}
