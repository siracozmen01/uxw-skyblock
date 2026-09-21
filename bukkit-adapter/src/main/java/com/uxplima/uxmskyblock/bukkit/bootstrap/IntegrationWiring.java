package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.command.IslandFeatures;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.discord.JavaHttpClientDiscordAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.network.BukkitVelocityBridge;
import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.BlueMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.CompositeWebMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.DynmapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.bukkit.webmap.Pl3xMapAdapter;
import com.uxplima.uxmskyblock.bukkit.webmap.WebMapAdapter;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.application.webmap.IslandWebMapService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/**
 * Encapsulates outbound integrations: Economy, Discord webhooks, Outbox event streaming,
 * Velocity proxy bridges, Placeholders, WebMaps, Menus, Commands, and the Public Skyblock API.
 */
public final class IntegrationWiring implements AutoCloseable {

    private final JavaPlugin plugin;
    private final ServerNodeId serverNodeId;
    private final SkyblockEconomyBridge economyBridge;
    private final BedrockDetector bedrockDetector;
    private final BedrockScreen bedrockScreen;
    private final BedrockFormService bedrockFormService;
    private final WorldDimensionSnapshotAdapter worldDimensionSnapshotAdapter;
    private final CompositeWebMapAdapter webMapAdapter;
    private final IslandWebMapService islandWebMapService;
    private final IslandMarkerSynchroniser markerSynchroniser;
    private final IslandControlMenu controlMenu;
    private final SkyblockMenuEngine menuEngine;
    private final SkyblockPlaceholderExpansion placeholderExpansion;
    private final TransactionalOutboxDispatcher outboxDispatcher;
    private final ClusterTransportWiring clusterTransport;
    private final DurableEventTransportPort eventTransport;
    private final VelocityBridgePort velocityBridge;
    private final ClusterRoutingDirectoryPort clusterRoutingDirectory;
    private final IslandNetworkRouter networkRouter;
    private final IslandDiscordWebhookService discordService;
    private final MessageProvider messageProvider;
    private final Messages messages;
    private final IslandCommandTree commandTree;
    private final BukkitSkyblockApiBridge apiBridge;

    public IntegrationWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay) {
        this(
                plugin,
                config,
                persistence,
                authority,
                gameplay,
                ClusterTransportWiring.create(plugin, config.nodeConfig()));
    }

    public IntegrationWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            GameplayWiring gameplay,
            ClusterTransportWiring clusterTransport) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.clusterTransport = Objects.requireNonNull(clusterTransport, "clusterTransport must not be null");
        this.serverNodeId = config.nodeConfig().nodeId();
        String worldName = config.nodeConfig().worldName();

        // The catalogue is built once, where the rest of the configuration is read.
        this.messages = config.messages();
        this.messageProvider = this.messages.provider();

        this.economyBridge = SkyblockEconomyBridge.createDefault(
                gameplay.bankService(), gameplay.scheduler(), persistence.economySagaPort());

        this.bedrockDetector = BedrockDetector.forServer(plugin.getServer());
        this.bedrockScreen = BedrockScreen.forServer(plugin.getServer());
        this.bedrockFormService = new BedrockFormService(bedrockDetector, bedrockScreen, this.messages);
        if (gameplay.resetConfirmationMenu() != null) {
            gameplay.resetConfirmationMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.missionsMenu() != null) {
            gameplay.missionsMenu().setBedrockFormService(this.bedrockFormService);
        }
        if (gameplay.boosterMenu() != null) {
            gameplay.boosterMenu().setBedrockFormService(this.bedrockFormService);
        }

        this.worldDimensionSnapshotAdapter = new WorldDimensionSnapshotAdapter(plugin, persistence.islandStoragePort());
        this.webMapAdapter = new CompositeWebMapAdapter(
                List.of(new DynmapAdapter(plugin), new BlueMapAdapter(plugin), new Pl3xMapAdapter(plugin)));
        this.islandWebMapService = new IslandWebMapService();
        // Dynmap, BlueMap and Pl3xMap were all built, all wired into a composite, and nothing ever
        // called one. Every server running this with Dynmap installed had a map with no islands.
        this.markerSynchroniser =
                new IslandMarkerSynchroniser(this.webMapAdapter, gameplay.locationService(), gameplay.scheduler());

        this.controlMenu = new IslandControlMenu(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandUpgradeStoragePort(),
                gameplay.locationService(),
                gameplay.scheduler(),
                worldName,
                authority.sessionCoordinator(),
                this.bedrockFormService,
                this.messages);

        // The menu files are the menus. Three of them shipped from the beginning and nothing read
        // one, so an operator who moved a slot and restarted saw no change.
        this.menuEngine = new SkyblockMenuEngine(plugin, this.messages, config.dataDir(), config.rootNode());
        this.menuEngine.loadSpecs();
        registerMenuVerbs(this.menuEngine, this.messages);
        this.menuEngine.install();
        this.controlMenu.useMenuEngine(this.menuEngine);

        this.placeholderExpansion = new SkyblockPlaceholderExpansion(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandUpgradeStoragePort(),
                persistence.islandLeaderboardPort(),
                gameplay.scheduler(),
                authority.sessionCoordinator());

        this.outboxDispatcher = new TransactionalOutboxDispatcher(
                persistence.outboxPort(), gameplay.scheduler(), serverNodeId.value() + "-outbox");
        this.eventTransport = this.clusterTransport.eventTransport();
        this.outboxDispatcher.registerConsumer(event -> {
            this.eventTransport.publish("uxmskyblock:stream:domain_events", event);
        });

        this.velocityBridge = new BukkitVelocityBridge(plugin);
        this.clusterRoutingDirectory = this.clusterTransport.clusterRoutingDirectory();
        this.networkRouter = new IslandNetworkRouter(
                serverNodeId,
                persistence.islandAuthorityPort(),
                velocityBridge,
                clusterRoutingDirectory,
                config.nodeConfig().routeCacheTtl());

        this.discordService = new IslandDiscordWebhookService(
                new JavaHttpClientDiscordAdapter(),
                config.discordConfig().webhookUrls(),
                config.discordConfig().enabled(),
                config.discordConfig().botUsername(),
                config.discordConfig().avatarUrl(),
                config.discordConfig().rateLimitPerSecond());

        this.commandTree = new IslandCommandTree(
                gameplay.createIslandUseCase(),
                gameplay.locationService(),
                new IslandFlagService(persistence.islandStoragePort()),
                gameplay.bankService(),
                persistence.islandUpgradeStoragePort(),
                gameplay.leaderboardService(),
                gameplay.biomeAdapter(),
                gameplay.presetCatalog(),
                gameplay.schematicEngine(),
                gameplay.protectionListener(),
                authority.sessionCoordinator(),
                gameplay.scheduler(),
                this.messages,
                config.homeConfig(),
                serverNodeId,
                worldName,
                this.economyBridge,
                IslandFeatures.builder()
                        .controlMenu(this.controlMenu)
                        .chatService(gameplay.chatService())
                        .inactivityService(gameplay.inactivityService())
                        .freezeService(gameplay.freezeService())
                        .missionsMenu(gameplay.missionsMenu())
                        .boundaryService(gameplay.boundaryService())
                        .recycleService(gameplay.recycleService())
                        .resetMenu(gameplay.resetConfirmationMenu())
                        .worthService(gameplay.worthService())
                        .dimensionListener(gameplay.dimensionListener())
                        .limitService(gameplay.limitService())
                        .antiAbuseService(gameplay.antiAbuseService())
                        .boosterService(gameplay.boosterService())
                        .boosterMenu(gameplay.boosterMenu())
                        .build());
        this.commandTree.setBankruptcyService(gameplay.bankruptcyService());
        this.commandTree.setHomeService(gameplay.homeService());
        this.commandTree.setVaultWindow(gameplay.vaultWindow());
        this.commandTree.setActivityFeedService(gameplay.activityFeedService());
        this.commandTree.setNameService(gameplay.islandNameService());
        this.commandTree.setNetworkRouter(this.networkRouter);
        this.commandTree.setRestoreService(gameplay.islandRestoreService());
        this.commandTree.setBackupService(gameplay.backupService());
        this.commandTree.setBackupBucket(gameplay.backupBucket());
        this.commandTree.setIslandBackupService(gameplay.islandBackupService());
        this.commandTree.setMembershipService(gameplay.membershipService());
        // Four subsystems that were running with no door. Every one of them had a service, a table
        // and a feature module, and no command a player could type.
        this.commandTree.setWarpService(gameplay.warpService());
        this.commandTree.setSocialService(gameplay.socialService());
        this.commandTree.setAllianceService(gameplay.allianceService());
        this.commandTree.setRewardInboxService(gameplay.rewardInboxService());
        this.commandTree.useMarkerSynchroniser(this.markerSynchroniser);
        this.commandTree.setMissionService(gameplay.missionService());

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistence.islandStoragePort(),
                persistence.islandBankPort(),
                persistence.islandLeaderboardPort(),
                gameplay.bankService(),
                gameplay.createIslandUseCase(),
                serverNodeId,
                gameplay.scheduler(),
                authority.sessionCoordinator(),
                worldName);
    }

    public void enable() {
        if (!Guis.isInstalled()) {
            Guis.install(plugin);
        }
        outboxDispatcher.start();
        placeholderExpansion.registerExpansion("uxplima", plugin.getPluginMeta().getVersion());
        commandTree.register(plugin);
        apiBridge.register();
        economyBridge.recoverPendingSagas(serverNodeId);
    }

    public SkyblockEconomyBridge economyBridge() {
        return economyBridge;
    }

    public BedrockDetector bedrockDetector() {
        return bedrockDetector;
    }

    public BedrockScreen bedrockScreen() {
        return bedrockScreen;
    }

    public BedrockFormService bedrockFormService() {
        return bedrockFormService;
    }

    public WorldDimensionSnapshotAdapter worldDimensionSnapshotAdapter() {
        return worldDimensionSnapshotAdapter;
    }

    public WebMapAdapter webMapAdapter() {
        return webMapAdapter;
    }

    /** Puts islands on the web map and takes them off again. */
    public IslandMarkerSynchroniser markerSynchroniser() {
        return markerSynchroniser;
    }

    public IslandWebMapService islandWebMapService() {
        return islandWebMapService;
    }

    /**
     * The verbs a skyblock menu file may name, on top of the five every plugin has.
     *
     * <p>Each one is what the window used to do in Java when its slot was clicked. The file decides
     * which slot runs which verb; this decides what each verb means.
     */
    private static void registerMenuVerbs(SkyblockMenuEngine engine, Messages messages) {
        engine.action("skyblock:teleport-home", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand("is home");
        });
        engine.action("skyblock:bank", ctx -> {
            ctx.player().closeInventory();
            messages.send(ctx.player(), "menu.control.bank_hint");
        });
        engine.action("skyblock:upgrades", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand("is upgrades");
        });
        engine.action("skyblock:members", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand("is members");
        });
        engine.action("skyblock:settings", ctx -> {
            ctx.player().closeInventory();
            messages.send(ctx.player(), "menu.control.settings_hint");
        });
        // The invite button sent a hint message, because there was no command behind it. The name
        // the player types is the verb's argument, so one slot serves every invite.
        engine.action("skyblock:invite", ctx -> {
            String name = ctx.arg().strip();
            ctx.player().closeInventory();
            if (name.isEmpty()) {
                messages.send(ctx.player(), "menu.control.members_hint");
                return;
            }
            ctx.player().performCommand("is invite " + name);
        });
        engine.action("skyblock:permissions", ctx -> {
            ctx.player().closeInventory();
            messages.send(ctx.player(), "menu.control.settings_hint");
        });
        // The upgrade key is the verb's argument, so one verb serves every upgrade a file names and
        // an operator can add a slot for a new one without a line of Java.
        engine.action("skyblock:buy-upgrade", ctx -> {
            String upgradeKey = ctx.arg().strip();
            ctx.player().closeInventory();
            if (upgradeKey.isEmpty()) {
                messages.send(ctx.player(), "menu.control.upgrade_unnamed");
                return;
            }
            ctx.player().performCommand("is upgrades buy " + upgradeKey);
        });
    }

    /** The menu engine, so a feature can register the verbs its own menu files name. */
    public SkyblockMenuEngine menuEngine() {
        return menuEngine;
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

    public DurableEventTransportPort eventTransport() {
        return eventTransport;
    }

    public VelocityBridgePort velocityBridge() {
        return velocityBridge;
    }

    public ClusterRoutingDirectoryPort clusterRoutingDirectory() {
        return clusterRoutingDirectory;
    }

    public IslandNetworkRouter networkRouter() {
        return networkRouter;
    }

    public IslandDiscordWebhookService discordService() {
        return discordService;
    }

    public Messages messages() {
        return messages;
    }

    public MessageProvider messageProvider() {
        return messageProvider;
    }

    public IslandCommandTree commandTree() {
        return commandTree;
    }

    public BukkitSkyblockApiBridge apiBridge() {
        return apiBridge;
    }

    public ClusterTransportWiring clusterTransport() {
        return clusterTransport;
    }

    public IslandChatTransportPort chatTransport() {
        return clusterTransport.chatTransport();
    }

    @Override
    public void close() {
        menuEngine.close();
        discordService.close();
        outboxDispatcher.close();
        clusterTransport.close();
        if (Guis.isInstalled()) {
            Guis.uninstall();
        }
        apiBridge.unregister();
    }
}
