package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RestApiConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.health.BukkitServerHealthAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.rest.server.RestServer;
import org.jspecify.annotations.Nullable;

/**
 * Platform composition root orchestrating configuration, persistence, authority,
 * gameplay domain logic, feature modules, and outbound platform integrations.
 */
public final class SkyblockBootstrap implements AutoCloseable {

    private final JavaPlugin plugin;
    private final ConfigurationWiring configWiring;
    private final PersistenceWiring persistenceWiring;
    private final AuthorityWiring authorityWiring;
    private final GameplayWiring gameplayWiring;
    private final IntegrationWiring integrationWiring;
    private @Nullable RestServer restServer;
    private final FeatureModuleWiring featureModuleWiring;

    public SkyblockBootstrap(JavaPlugin plugin, PersistenceWiring persistenceWiring, ConfigurationWiring configWiring) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.persistenceWiring = Objects.requireNonNull(persistenceWiring, "persistenceWiring must not be null");
        this.configWiring = Objects.requireNonNull(configWiring, "configWiring must not be null");

        SchedulerPort scheduler = new FoliaSchedulerAdapter(plugin);
        AdaptiveBackpressureController backpressureController = new AdaptiveBackpressureController(
                () -> {
                    double[] tps = Bukkit.getTPS();
                    return (tps != null && tps.length > 0) ? tps[0] : 20.0;
                },
                configWiring.performanceConfig().adaptiveThrottle(),
                configWiring.performanceConfig().tpsThreshold(),
                configWiring.performanceConfig().normalBlocksPerTick(),
                configWiring.performanceConfig().throttledBlocksPerTick(),
                configWiring.performanceConfig().normalChunksPerSec(),
                configWiring.performanceConfig().throttledChunksPerSec());

        IslandAccessService accessService = new IslandAccessService();
        IslandAllianceService allianceService = new IslandAllianceService(
                persistenceWiring.bootstrap().islandAllianceStoragePort(),
                configWiring.allianceConfig().maxAllies(),
                configWiring.allianceConfig().inviteTimeout(),
                configWiring.allianceConfig().friendlyFireShielding(),
                configWiring.allianceConfig().privilegedVisitAccess(),
                configWiring.allianceConfig().allianceChatEnabled());
        TemporaryAccessService temporaryAccessService =
                new TemporaryAccessService(persistenceWiring.bootstrap().temporaryAccessStoragePort());
        BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter = new BukkitIslandVisitorEvictionAdapter(
                plugin,
                persistenceWiring.bootstrap().islandStoragePort(),
                scheduler,
                configWiring.nodeConfig().worldName(),
                configWiring.messages());
        IslandAdminFreezeService freezeService = new IslandAdminFreezeService(
                persistenceWiring.bootstrap().islandStoragePort(),
                persistenceWiring.bootstrap().islandAdminFreezePort(),
                visitorEvictionAdapter,
                persistenceWiring.bootstrap().outboxPort());

        IslandProtectionListener protectionListener = new IslandProtectionListener(
                persistenceWiring.bootstrap().islandStoragePort(),
                accessService,
                allianceService,
                temporaryAccessService,
                freezeService);

        this.authorityWiring = AuthorityWiring.create(
                configWiring.nodeConfig().nodeId(),
                configWiring.playerStateConfig(),
                persistenceWiring.bootstrap(),
                scheduler,
                protectionListener,
                configWiring.messages());

        ClusterTransportWiring clusterTransportWiring =
                ClusterTransportWiring.create(plugin, configWiring.nodeConfig());

        AtomicReference<SkyblockEconomyBridge> economyBridgeRef = new AtomicReference<>();
        this.gameplayWiring = new GameplayWiring(
                plugin,
                configWiring,
                persistenceWiring.bootstrap(),
                authorityWiring,
                protectionListener,
                accessService,
                allianceService,
                temporaryAccessService,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                backpressureController,
                economyBridgeRef::get,
                clusterTransportWiring.chatTransport(),
                persistenceWiring.objectStoragePort());

        this.integrationWiring = new IntegrationWiring(
                plugin,
                configWiring,
                persistenceWiring.bootstrap(),
                authorityWiring,
                gameplayWiring,
                clusterTransportWiring);
        economyBridgeRef.set(this.integrationWiring.economyBridge());

        this.featureModuleWiring =
                new FeatureModuleWiring(configWiring, persistenceWiring.bootstrap(), gameplayWiring, integrationWiring);
    }

    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        ConfigurationWiring configWiring = ConfigurationLoader.loadAndValidate(plugin);
        PersistenceWiring persistenceWiring =
                PersistenceWiring.resolve(configWiring.rootNode(), configWiring.dataDir());
        return new SkyblockBootstrap(plugin, persistenceWiring, configWiring);
    }

    public void enable() {
        featureModuleWiring.enable();
        gameplayWiring
                .protectionListener()
                .loadPersistedIslands(configWiring.nodeConfig().worldName());
        integrationWiring.enable();
        startRestApiIfConfigured();

        BootstrapEventRegistrar.registerEvents(
                Bukkit.getPluginManager(), plugin, featureModuleWiring, gameplayWiring, authorityWiring, configWiring);
    }

    /**
     * Opens the embedded REST API when the operator asked for it.
     *
     * <p>The module has existed since the enterprise foundation work and nothing ever started it:
     * the plugin did not even depend on it, so five endpoints, the bearer check and the deposit
     * idempotency were code that never ran on a server. It is off by default and refuses to open
     * while the bearer token is still the one the file ships with.
     */
    private void startRestApiIfConfigured() {
        RestApiConfiguration restConfig = RestApiConfiguration.load(configWiring.rootNode());
        if (restConfig.refusedForDefaultToken()) {
            plugin.getLogger()
                    .warning("The REST API is enabled but rest.bearer-token is still the shipped value, "
                            + "so the port was not opened. Set a token of your own.");
            return;
        }
        if (!restConfig.shouldStart()) {
            return;
        }
        RestServer server = new RestServer(
                restConfig.toRestConfiguration(),
                configWiring.nodeConfig().nodeId(),
                persistenceWiring.bootstrap().islandStoragePort(),
                gameplayWiring.bankService(),
                gameplayWiring.leaderboardService(),
                new BukkitServerHealthAdapter(
                        gameplayWiring.protectionListener().spatialIndex()));
        server.start();
        // The feed behind WS /api/v1/events carries exactly what the plugin durably staged. Nothing
        // is invented for the socket, and a viewer that drops a frame never makes the outbox retry.
        integrationWiring.outboxDispatcher().registerConsumer(server.liveEventFeed());
        this.restServer = server;
        plugin.getLogger().info("REST API listening on " + restConfig.host() + ":" + server.port());
    }

    public ConfigurationWiring configurationWiring() {
        return configWiring;
    }

    public PersistenceWiring persistenceWiring() {
        return persistenceWiring;
    }

    public AuthorityWiring authorityWiring() {
        return authorityWiring;
    }

    public GameplayWiring gameplayWiring() {
        return gameplayWiring;
    }

    public IntegrationWiring integrationWiring() {
        return integrationWiring;
    }

    public FeatureModuleWiring featureModuleWiring() {
        return featureModuleWiring;
    }

    public PersistenceBootstrap persistenceBootstrap() {
        return persistenceWiring.bootstrap();
    }

    public IslandProtectionListener protectionListener() {
        return gameplayWiring.protectionListener();
    }

    public IslandCommandTree commandTree() {
        return integrationWiring.commandTree();
    }

    public ServerNodeConfiguration nodeConfiguration() {
        return configWiring.nodeConfig();
    }

    public PlayerStateDurabilityConfig playerStateConfig() {
        return configWiring.playerStateConfig();
    }

    public ModuleRegistry moduleRegistry() {
        return featureModuleWiring.moduleRegistry();
    }

    public PlayerSessionCoordinator sessionCoordinator() {
        return authorityWiring.sessionCoordinator();
    }

    public SkyblockEconomyBridge economyBridge() {
        return integrationWiring.economyBridge();
    }

    public IslandControlMenu controlMenu() {
        return integrationWiring.controlMenu();
    }

    public SkyblockPlaceholderExpansion placeholderExpansion() {
        return integrationWiring.placeholderExpansion();
    }

    public TransactionalOutboxDispatcher outboxDispatcher() {
        return integrationWiring.outboxDispatcher();
    }

    public IslandSeasonService seasonService() {
        return gameplayWiring.seasonService();
    }

    public IslandSocialService socialService() {
        return gameplayWiring.socialService();
    }

    public IslandDiscordWebhookService discordService() {
        return integrationWiring.discordService();
    }

    public IslandWarpService warpService() {
        return gameplayWiring.warpService();
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return gameplayWiring.safeTeleportEngine();
    }

    public WarpConfiguration warpConfiguration() {
        return configWiring.warpConfig();
    }

    public IslandVaultService vaultService() {
        return gameplayWiring.vaultService();
    }

    public VaultConfiguration vaultConfiguration() {
        return configWiring.vaultConfig();
    }

    public IslandChatService chatService() {
        return gameplayWiring.chatService();
    }

    public ChatConfiguration chatConfiguration() {
        return configWiring.chatConfig();
    }

    @Nullable public IslandChatListener chatListener() {
        return gameplayWiring.chatListener();
    }

    public IslandInactivityService inactivityService() {
        return gameplayWiring.inactivityService();
    }

    public InactivityConfiguration inactivityConfiguration() {
        return configWiring.inactivityConfig();
    }

    @Override
    public void close() {
        RestServer server = this.restServer;
        if (server != null) {
            server.close();
            this.restServer = null;
        }
        featureModuleWiring.close();
        gameplayWiring.missionService().flushDirtyProgress();
        integrationWiring.close();
        authorityWiring.close();
        // Async work already handed out writes to the pool that is about to close. Waiting for it is
        // the difference between a write landing and a stack trace in a log nobody reads.
        if (gameplayWiring.scheduler() instanceof FoliaSchedulerAdapter folia
                && !folia.drainAsync(Duration.ofSeconds(5))) {
            plugin.getLogger()
                    .warning("Some background work was still running at shutdown, so the database was closed "
                            + "under it. Anything it was writing may not have landed.");
        }
        persistenceWiring.close();
    }
}
