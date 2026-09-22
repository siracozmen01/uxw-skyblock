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
import com.uxplima.uxmskyblock.bukkit.config.NotificationConfiguration;
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
import com.uxplima.uxmskyblock.core.application.event.DeduplicatingOutboxConsumer;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityService;
import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
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
    private final IslandAuthorityService authorityService;
    private final @org.jspecify.annotations.Nullable IslandRecycleService recycleService;
    private final NotificationService notificationService;
    private final com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService activityFeedService;
    private final NotificationConfiguration notificationConfig;

    private @org.jspecify.annotations.Nullable AutoCloseable notificationSweep;
    private final java.time.Duration authorityHeartbeatInterval;
    private final com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort scheduler;

    private @org.jspecify.annotations.Nullable AutoCloseable authorityHeartbeat;
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
        if (gameplay.shopMenu() != null) {
            gameplay.shopMenu().setBedrockFormService(this.bedrockFormService);
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

        // The authority lease was taken once, when an island was made, and nothing ever renewed it.
        // Every write that needs authority is refused once it runs out, so an island stopped being
        // able to use its own bank one lease after it was created. This is the missing heartbeat.
        this.scheduler = gameplay.scheduler();
        this.recycleService = gameplay.recycleService();
        this.notificationService = gameplay.notificationService();
        this.activityFeedService = gameplay.activityFeedService();
        this.notificationConfig = config.notificationConfig();
        this.authorityService = new IslandAuthorityService(
                persistence.islandAuthorityPort(),
                serverNodeId,
                config.nodeConfig().worldName(),
                config.nodeConfig().authorityLease());
        this.authorityHeartbeatInterval = config.nodeConfig().authorityHeartbeatInterval();
        this.eventTransport = this.clusterTransport.eventTransport();
        // Delivery is at least once: a worker whose claim lease runs out while it is delivering
        // loses the row to another worker, which delivers it again. The consumer inbox was built
        // for exactly that and nothing ever wrote a row to it, so a fenced claim published the
        // same event twice.
        this.outboxDispatcher.sweepInboxToo(persistence.consumerInboxPort());
        this.outboxDispatcher.registerConsumer(new DeduplicatingOutboxConsumer(
                serverNodeId.value() + "-event-transport",
                persistence.consumerInboxPort(),
                event -> this.eventTransport.publish("uxmskyblock:stream:domain_events", event)));

        this.velocityBridge = new BukkitVelocityBridge(plugin, gameplay.scheduler());
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

        // The service was built, given a URL per topic, rate limited and queued, and nothing ever
        // told it anything: not one of its notification methods had a caller. These are the two
        // that know when something worth announcing happened.
        gameplay.allianceService().setAnnouncer(this.discordService);
        gameplay.freezeService().setAnnouncer(this.discordService);

        this.commandTree = new IslandCommandTree(
                gameplay.createIslandUseCase(),
                gameplay.locationService(),
                new IslandFlagService(persistence.islandStoragePort(), persistence.islandMutationLock()),
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
                        .upgradeService(gameplay.upgradeService())
                        .shopService(gameplay.shopService())
                        .shopMenu(gameplay.shopMenu())
                        // The grant subsystem was complete underneath and nothing could make a
                        // grant: the whole write side had no caller, so the check on every click
                        // asked about grants that could not exist.
                        .temporaryAccessService(
                                config.moduleSettings().isModuleEnabled("temporary-access")
                                        ? gameplay.temporaryAccessService()
                                        : null)
                        .build());
        this.commandTree.useTemporaryAccess(config.temporaryAccessConfig(), authority.nodeProcessIdentity());
        // The inbox, its table, its ten categories and the delivery on join were all here and
        // nothing ever wrote a row, so "while you were away" was always empty.
        this.commandTree.useNotifications(gameplay.notificationService());
        // Nothing ever wrote an activity event, so every island's feed was empty for as long as the
        // server ran.
        this.commandTree.useActivityFeed(gameplay.activityFeedService());
        tellTheFeedWhenAMissionFinishes(gameplay);
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
        this.commandTree.setSeasonService(gameplay.seasonService());
        // A reload reads the catalogues and the menus and nothing else: no service is re-bound and
        // no table is touched, because hot swapping a subsystem is how a plugin leaks classloaders
        // and leaves listeners behind.
        this.commandTree.setReloader(new SkyblockReloader(this.messages.provider(), config.dataDir(), this.menuEngine));
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
        // The first beat runs at once: a server that has been down longer than the lease has to take
        // its islands back before anybody tries to bank on one.
        authorityService.heartbeat();
        this.authorityHeartbeat = scheduler.repeatAsync(
                authorityService::heartbeat, authorityHeartbeatInterval, authorityHeartbeatInterval);
        placeholderExpansion.registerExpansion("uxplima", plugin.getPluginMeta().getVersion());
        commandTree.register(plugin);
        apiBridge.register();
        economyBridge.recoverPendingSagas(serverNodeId);
        recoverIncompleteRecycles();
        this.notificationSweep = scheduler.repeatAsync(
                () -> {
                    sweepReadNotifications();
                    sweepOldActivity();
                },
                notificationConfig.sweepInterval(),
                notificationConfig.sweepInterval());
    }

    /**
     * Drops the activity lines an island's feed has outgrown.
     *
     * <p>A feed is a digest of what happened lately, not a ledger. Nothing wrote a line until now
     * and nothing ever deleted one.
     */
    private void sweepOldActivity() {
        try {
            int swept = activityFeedService.purgeOlderThan(
                    java.time.Instant.now().minus(notificationConfig.activityRetention()));
            if (swept > 0) {
                java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                        .fine(() -> "Swept " + swept + " activity events an island's feed had outgrown.");
            }
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(
                            java.util.logging.Level.WARNING,
                            "Sweeping the activity feed failed. The next sweep retries.",
                            e);
        }
    }

    /**
     * Deletes the notices a player has already read and long since acted on.
     *
     * <p>Nothing wrote a notification until now and nothing ever deleted one, so the table would
     * have grown for as long as the server ran the moment anything started writing to it.
     */
    private void sweepReadNotifications() {
        try {
            int swept =
                    notificationService.purgeRead(java.time.Instant.now().minus(notificationConfig.readRetention()));
            if (swept > 0) {
                java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                        .fine(() -> "Swept " + swept + " notifications that had been read.");
            }
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(
                            java.util.logging.Level.WARNING,
                            "Sweeping the notifications already read failed. The next sweep retries.",
                            e);
        }
    }

    /**
     * Writes a finished mission into the island's feed.
     *
     * <p>Only the mission service knows the moment a mission crosses its target: it happens inside
     * an advance, and the callers that trigger it are block breaks and hand-ins that know nothing
     * about it. The write is a row, so it hops off whichever thread the last block break arrived on.
     */
    private void tellTheFeedWhenAMissionFinishes(GameplayWiring gameplay) {
        com.uxplima.uxmskyblock.core.application.mission.IslandMissionService missions = gameplay.missionService();
        if (missions == null) {
            return;
        }
        missions.setFinishedListener(finished -> scheduler.async(() -> {
            try {
                activityFeedService.record(
                        finished.islandId().value().toString(),
                        finished.profileId(),
                        com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType.MISSION_COMPLETED,
                        com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility.MEMBERS_ONLY,
                        "activity.mission_completed",
                        java.util.Map.of("mission", finished.definition().displayName()));
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                        .log(java.util.logging.Level.WARNING, "Writing a finished mission into the feed failed.", e);
            }
        }));
    }

    /**
     * Finishes the island resets a crash left half done.
     *
     * <p>A reset deletes the island and then hands its grid slot back. A crash between the two
     * leaves the operation sitting in CANONICAL_DELETE and the slot marked allocated for an island
     * that no longer exists, so the grid never reuses it and the world grows a hole per crash. The
     * recovery for exactly this was written, said "startup recovery" in its own documentation, and
     * had no caller anywhere.
     *
     * <p>It runs off the thread that is starting the server, because it reads and writes rows.
     */
    private void recoverIncompleteRecycles() {
        IslandRecycleService recycleService = this.recycleService;
        if (recycleService == null) {
            return;
        }
        scheduler.async(() -> {
            try {
                recycleService.recoverIncompleteOperations();
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                        .log(
                                java.util.logging.Level.WARNING,
                                "Finishing the island resets a crash left half done failed.",
                                e);
            }
        });
    }

    private void closeQuietly(@org.jspecify.annotations.Nullable AutoCloseable task, String what) {
        if (task == null) {
            return;
        }
        try {
            task.close();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(java.util.logging.Level.WARNING, "Stopping " + what + " failed.", e);
        }
    }

    private void closeAuthorityHeartbeat() {
        @org.jspecify.annotations.Nullable AutoCloseable beat = this.authorityHeartbeat;
        this.authorityHeartbeat = null;
        if (beat == null) {
            return;
        }
        try {
            beat.close();
        } catch (Exception e) {
            java.util.logging.Logger.getLogger(IntegrationWiring.class.getName())
                    .log(java.util.logging.Level.WARNING, "Stopping the island authority heartbeat failed.", e);
        }
    }

    /** The heartbeat that keeps this node's authority alive. */
    public IslandAuthorityService authorityService() {
        return authorityService;
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
            ctx.player().performCommand("is settings");
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
        engine.action("skyblock:shop", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand("is shop");
        });
        engine.action("skyblock:permissions", ctx -> {
            ctx.player().closeInventory();
            ctx.player().performCommand("is permissions");
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
        closeAuthorityHeartbeat();
        closeQuietly(this.notificationSweep, "the notification sweep");
        this.notificationSweep = null;
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
