package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 *
 * <p>Coordinates subcommand groups: {@link IslandBankCommands}, {@link IslandChatCommands},
 * {@link IslandAdminCommands}, {@link IslandLifecycleCommands}, {@link IslandNavigationCommands},
 * {@link IslandProgressionCommands}, and {@link IslandMechanicsCommands}.
 */
public final class IslandCommandTree {

    // The fields below carry no modifier on purpose. CommandGroupBuilder is this class's other half,
    // split out only because holding both what the verbs are and where they hang put one file back
    // over the size the standards draw a line at. It lives in this package and nowhere else, and it
    // reads these directly rather than through thirty accessors that would exist for nothing.

    final CreateIslandUseCase createIslandUseCase;
    final IslandLocationService islandLocationService;
    final IslandFlagService flagService;
    final IslandBankService islandBankService;
    private final IslandUpgradeStoragePort islandUpgradePort;
    final IslandLeaderboardService islandLeaderboardService;
    final BiomeModificationPort biomeModificationPort;
    final StarterPresetCatalog presetCatalog;
    final StarterSchematicEngine schematicEngine;
    final IslandProtectionListener protectionListener;
    final PlayerSessionCoordinator sessionCoordinator;
    final SchedulerPort schedulerPort;
    final Messages messages;
    final HomeConfiguration homeConfiguration;
    volatile @Nullable HomeService homeService;
    /** How many log lines a caller who names no number gets. */
    private static final int DEFAULT_VAULT_LOG_LINES = 10;

    private volatile @Nullable IslandVaultWindow vaultWindow;
    volatile @Nullable ActivityFeedService activityFeedService;
    final ServerNodeId serverNodeId;
    final String worldName;
    final SkyblockEconomyBridge economyBridge;
    final IslandFeatures features;
    volatile @Nullable IslandBankruptcyService bankruptcyService;
    volatile @Nullable IslandNameService nameService;
    volatile @Nullable IslandNetworkRouter networkRouter;
    volatile @Nullable IslandRestoreService restoreService;
    volatile @Nullable BackupService backupService;
    volatile @Nullable StorageBucket backupBucket;
    volatile @Nullable IslandBackupService islandBackupService;

    /** The whole database, for the day the database is gone. */
    volatile com.uxplima.uxmskyblock.core.application.backup.@Nullable DatabaseDisasterBackupService
            databaseBackupService;

    volatile @Nullable IslandMembershipService membershipService;
    volatile @Nullable IslandSeasonService seasonService;
    volatile @Nullable SkyblockReloader reloader;
    volatile java.util.function.Supplier<java.util.List<com.uxplima.uxmlib.health.HealthCheck>> healthChecks =
            java.util.List::of;
    volatile @Nullable IslandWarpService warpService;

    /** What the operator wrote for the milestones the commands reach. */
    volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects interactionEffects;

    /** The bypass permissions and the cooldowns the operator named for the anti abuse rules. */
    volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseConfiguration;

    /** Which biomes the operator offers and what an island has to reach first. */
    volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable BiomeConfiguration biomeConfiguration;

    volatile java.time.Duration recalculationCooldown =
            com.uxplima.uxmskyblock.core.application.worth.RecalculationGate.DEFAULT_COOLDOWN;

    /** The window the public warp directory opens in, so its icons are drawn. */
    volatile com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu;

    volatile @Nullable IslandSocialService socialService;
    volatile @Nullable IslandAllianceService allianceService;
    volatile @Nullable RewardInboxService rewardInboxService;
    volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    volatile @Nullable IslandMissionService missionService;

    /** What a trust grant carries and how long it may last. Set before the tree is registered. */
    volatile @Nullable TemporaryAccessConfiguration temporaryAccessConfiguration;

    /** Which node and which boot this is, for a grant that lasts until the node restarts. */
    volatile @Nullable CurrentNodeProcessIdentity nodeProcessIdentity;

    /** Which ruleset a profile plays under, so a grant cannot cross a ruleset boundary. */
    volatile com.uxplima.uxmskyblock.core.application.profile.@Nullable ProfileTypes profileTypes;

    /** Where a notice goes for a player who is not here to be told. */
    volatile com.uxplima.uxmskyblock.core.application.notification.@Nullable NotificationService notificationService;

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            IslandFlagService flagService,
            IslandBankService islandBankService,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            HomeConfiguration homeConfiguration,
            ServerNodeId serverNodeId,
            String worldName) {
        this(
                createIslandUseCase,
                islandLocationService,
                flagService,
                islandBankService,
                islandUpgradePort,
                islandLeaderboardService,
                biomeModificationPort,
                presetCatalog,
                schematicEngine,
                protectionListener,
                sessionCoordinator,
                schedulerPort,
                messages,
                homeConfiguration,
                serverNodeId,
                worldName,
                SkyblockEconomyBridge.createDefault(islandBankService, schedulerPort),
                IslandFeatures.none());
    }

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            IslandFlagService flagService,
            IslandBankService islandBankService,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            HomeConfiguration homeConfiguration,
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            IslandFeatures features) {
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.flagService = Objects.requireNonNull(flagService, "flagService must not be null");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandUpgradePort = Objects.requireNonNull(islandUpgradePort, "islandUpgradePort must not be null");
        this.islandLeaderboardService =
                Objects.requireNonNull(islandLeaderboardService, "islandLeaderboardService must not be null");
        this.biomeModificationPort =
                Objects.requireNonNull(biomeModificationPort, "biomeModificationPort must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.homeConfiguration = Objects.requireNonNull(homeConfiguration, "homeConfiguration must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.features = Objects.requireNonNull(features, "features must not be null");
    }

    public IslandUpgradeStoragePort islandUpgradePort() {
        return islandUpgradePort;
    }

    public @Nullable IslandWorthService worthService() {
        return features.worthService();
    }

    public void setBankruptcyService(@Nullable IslandBankruptcyService bankruptcyService) {
        this.bankruptcyService = bankruptcyService;
    }

    public @Nullable IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return features.dimensionListener();
    }

    public @Nullable IslandLimitService limitService() {
        return features.limitService();
    }

    public @Nullable IslandAntiAbuseService antiAbuseService() {
        return features.antiAbuseService();
    }

    public void setActivityFeedService(@Nullable ActivityFeedService activityFeedService) {
        this.activityFeedService = activityFeedService;
    }

    /** Tells the tree what a trust grant carries, so the trust branch can make one. */
    public void useTemporaryAccess(
            TemporaryAccessConfiguration configuration,
            CurrentNodeProcessIdentity nodeProcessIdentity,
            com.uxplima.uxmskyblock.core.application.profile.ProfileTypes profileTypes) {
        this.temporaryAccessConfiguration = configuration;
        this.nodeProcessIdentity = nodeProcessIdentity;
        this.profileTypes = profileTypes;
    }

    /** Hands the island's feed to the command groups that change something worth writing down. */
    public void useActivityFeed(com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService service) {
        this.activityFeedService = service;
    }

    /** Hands the inbox to the command groups that change a player's standing on an island. */
    public void useNotifications(com.uxplima.uxmskyblock.core.application.notification.NotificationService service) {
        this.notificationService = service;
    }

    public void setVaultWindow(@Nullable IslandVaultWindow vaultWindow) {
        this.vaultWindow = vaultWindow;
    }

    public void setHomeService(@Nullable HomeService homeService) {
        this.homeService = homeService;
    }

    public void setNameService(@Nullable IslandNameService nameService) {
        this.nameService = nameService;
    }

    public @Nullable IslandNameService nameService() {
        return nameService;
    }

    public void setNetworkRouter(@Nullable IslandNetworkRouter networkRouter) {
        this.networkRouter = networkRouter;
    }

    public @Nullable IslandNetworkRouter networkRouter() {
        return networkRouter;
    }

    public void setRestoreService(@Nullable IslandRestoreService restoreService) {
        this.restoreService = restoreService;
    }

    public @Nullable IslandRestoreService restoreService() {
        return restoreService;
    }

    /** Hands the warp subsystem to the command that reaches it. */
    public void setWarpService(@Nullable IslandWarpService warpService) {
        this.warpService = warpService;
    }

    /** Hands the operator's effect lists to the commands whose milestones fire them. */
    public void setInteractionEffects(com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects) {
        this.interactionEffects = effects;
    }

    /** Hands the operator's anti abuse rules to the commands that enforce them. */
    public void setAntiAbuseConfiguration(
            com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration configuration) {
        this.antiAbuseConfiguration = configuration;
    }

    /** Hands the operator's biome rules to the command that applies them. */
    public void setBiomeConfiguration(
            com.uxplima.uxmskyblock.bukkit.config.@Nullable BiomeConfiguration configuration) {
        this.biomeConfiguration = configuration;
    }

    /** Hands the operator's wait between two rescans of one island to the command that runs them. */
    public void setRecalculationCooldown(java.time.Duration cooldown) {
        this.recalculationCooldown = java.util.Objects.requireNonNull(cooldown, "cooldown must not be null");
    }

    /** Hands the directory window to the browse command, so a warp's icon is seen. */
    public void setWarpBrowseMenu(com.uxplima.uxmskyblock.bukkit.menu.@Nullable IslandWarpBrowseMenu warpBrowseMenu) {
        this.warpBrowseMenu = warpBrowseMenu;
    }

    /** Hands the social subsystem to the commands that reach it. */
    public void setSocialService(@Nullable IslandSocialService socialService) {
        this.socialService = socialService;
    }

    /** Hands the alliance subsystem to the command that reaches it. */
    public void setAllianceService(@Nullable IslandAllianceService allianceService) {
        this.allianceService = allianceService;
    }

    /** Hands the web map synchroniser to the create command, so a new island lands on the map. */
    public void useMarkerSynchroniser(@Nullable IslandMarkerSynchroniser markerSynchroniser) {
        this.markerSynchroniser = markerSynchroniser;
    }

    /** Hands the mission service to the level command, so finished missions count toward a level. */
    public void setMissionService(@Nullable IslandMissionService missionService) {
        this.missionService = missionService;
    }

    /** Hands the reward inbox to the command that opens it. */
    public void setRewardInboxService(@Nullable RewardInboxService rewardInboxService) {
        this.rewardInboxService = rewardInboxService;
    }

    public void setBackupService(@Nullable BackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * Names the bucket a restore reads from.
     *
     * <p>The restore command used to write the name into its own source, so an operator who renamed
     * their bucket wrote backups to one place and restored from another that does not exist.
     */
    public void setBackupBucket(@Nullable StorageBucket backupBucket) {
        this.backupBucket = backupBucket;
    }

    /** The thing that makes the backups {@code /is admin restore} puts back. */
    public void setIslandBackupService(@Nullable IslandBackupService islandBackupService) {
        this.islandBackupService = islandBackupService;
    }

    /** Hands the whole database backup to the command that takes one. */
    public void setDatabaseBackupService(
            com.uxplima.uxmskyblock.core.application.backup.@Nullable DatabaseDisasterBackupService service) {
        this.databaseBackupService = service;
    }

    /** Who belongs to an island, and what they may do on it. */
    public void setMembershipService(@Nullable IslandMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    /** Which season is running, how the last one went, and what it owes a player. */
    public void setSeasonService(@Nullable IslandSeasonService seasonService) {
        this.seasonService = seasonService;
    }

    /** Hands the doctor the checks it runs. */
    public void setHealthChecks(
            java.util.function.Supplier<java.util.List<com.uxplima.uxmlib.health.HealthCheck>> healthChecks) {
        this.healthChecks = java.util.Objects.requireNonNull(healthChecks, "healthChecks must not be null");
    }

    /** The files an operator may change while the server is running: catalogues and menus. */
    public void setReloader(@Nullable SkyblockReloader reloader) {
        this.reloader = reloader;
    }

    public @Nullable BackupService backupService() {
        return backupService;
    }

    /**
     * The names the operator gave the command and its branches, from {@code commands.conf}. Absent,
     * every word is the one the code ships.
     */
    private com.uxplima.uxmlib.command.annotation.@Nullable ConfiguredCommands commandNames;

    /** Reads the command's names from {@code commands.conf}, as {@link #register} does. */
    public void useCommandNames(com.uxplima.uxmlib.command.annotation.@Nullable ConfiguredCommands names) {
        this.commandNames = names;
    }

    public void register(JavaPlugin plugin) {
        java.nio.file.Path file = plugin.getDataFolder().toPath().resolve("commands.conf");
        if (!java.nio.file.Files.exists(file)) {
            plugin.saveResource("commands.conf", false);
        }
        this.commandNames = com.uxplima.uxmlib.command.annotation.ConfiguredCommands.load(file);
        ConfiguredCommandTree<CommandSourceStack> names = new ConfiguredCommandTree<>(this.commandNames);
        com.uxplima.uxmlib.command.annotation.ConfiguredCommands.Entry root = names.root();
        if (!root.enabled()) {
            // The operator turned the whole command off, which is how a server keeps only the menu.
            return;
        }
        CommandRegistrar.register(plugin, buildRoot().build(), "Main Skyblock command tree", root.aliases());
    }

    /**
     * The whole command tree, assembled but not registered.
     *
     * <p>Exposed so a test can hand it to Brigadier and ask whether a line parses. A guard that reads
     * the source instead can only guess at the shape, and one that guesses is believed until the day
     * a player types the line it guessed about.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return new ConfiguredCommandTree<CommandSourceStack>(commandNames)
                .apply(assembleRoot(new CommandGroupBuilder(this).build()));
    }

    /** The vault window under whichever word the caller typed. */
    private LiteralArgumentBuilder<CommandSourceStack> vaultBranch(String verb) {
        return Cmd.literal(verb)
                .executes(ctx -> executeVault(ctx, 1))
                .then(Cmd.literal("log")
                        .executes(ctx -> executeVaultLog(ctx, DEFAULT_VAULT_LOG_LINES))
                        .then(Cmd.argument("lines", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> executeVaultLog(
                                        ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "lines")))))
                .then(Cmd.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ctx -> executeVault(
                                ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page"))));
    }

    /**
     * Puts a verb behind the permission the catalogue publishes for it.
     *
     * <p>The catalogue registered eighteen nodes with the server and twelve of them were read
     * nowhere. An operator could take {@code uxmskyblock.island.create} off a group, see it taken
     * off in their permission plugin, and watch that group go on making islands.
     *
     * <p>Every one of these ships as true, so a server that grants nothing by hand sees no change
     * at all. What changes is that taking one away now does something.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> gated(
            LiteralArgumentBuilder<CommandSourceStack> verb, CatalogPermissions permission) {
        return verb.requires(src -> src.getSender().hasPermission(permission.node()));
    }

    private LiteralArgumentBuilder<CommandSourceStack> assembleRoot(CommandGroupBuilder.CommandGroups groups) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(groups.mechanicsCommands().buildMissions())
                .then(groups.mechanicsCommands().buildChallenges())
                .then(groups.mechanicsCommands().buildBorder())
                .then(groups.mechanicsCommands().buildBounds())
                .then(groups.progressionCommands().buildLevel())
                .then(groups.progressionCommands().buildRecalc())
                .then(groups.progressionCommands().buildWorth())
                .then(groups.progressionCommands().buildValue())
                .then(groups.lifecycleCommands().buildReset())
                .then(gated(groups.lifecycleCommands().buildDelete(), CatalogPermissions.ISLAND_DELETE))
                .then(gated(groups.lifecycleCommands().buildCreate(), CatalogPermissions.ISLAND_CREATE))
                .then(gated(groups.navigationCommands().buildHome(), CatalogPermissions.ISLAND_HOME))
                .then(groups.navigationCommands().buildGo())
                .then(groups.navigationCommands().buildVisit())
                .then(groups.navigationCommands().buildNether())
                .then(groups.navigationCommands().buildEnd())
                .then(groups.mechanicsCommands().buildLimits())
                .then(groups.mechanicsCommands().buildQuarantine())
                .then(groups.mechanicsCommands().buildBooster())
                .then(groups.navigationCommands().buildSetSpawn())
                .then(gated(groups.homeCommands().buildSetHome(), CatalogPermissions.ISLAND_SET_HOME))
                .then(gated(groups.homeCommands().buildTravelHome(), CatalogPermissions.ISLAND_HOME))
                .then(groups.homeCommands().buildNamedHome())
                .then(groups.homeCommands().buildDeleteHome())
                .then(groups.activityCommands().build())
                .then(groups.warpCommands().build())
                .then(groups.warpCommands().buildExplore("explore"))
                .then(groups.warpCommands().buildExplore("warps"))
                .then(groups.socialCommands().buildGuestbook())
                .then(groups.socialCommands().buildRate())
                .then(groups.socialCommands().buildBookmarks())
                .then(groups.allianceCommands().build())
                // The names the documents publish for branches that already exist under another
                // word: /is ally, /is disband, /is settings, and /is explore beside /is warps.
                .then(groups.allianceCommands().buildAlias("ally"))
                .then(gated(groups.lifecycleCommands().buildDisband(), CatalogPermissions.ISLAND_DELETE))
                .then(Cmd.literal("settings").executes(this::executeMenu))
                .then(groups.rewardCommands().build())
                .then(groups.flagCommands().build())
                .then(gated(groups.visitorCommands().buildBan(), CatalogPermissions.ISLAND_BAN))
                .then(gated(groups.visitorCommands().buildUnban(), CatalogPermissions.ISLAND_UNBAN))
                .then(groups.visitorCommands().buildBans())
                .then(groups.visitorCommands().buildLock())
                .then(groups.visitorCommands().buildUnlock())
                .then(gated(groups.membershipCommands().buildInvite(), CatalogPermissions.ISLAND_INVITE))
                .then(groups.membershipCommands().buildAccept())
                .then(groups.membershipCommands().buildDeny())
                .then(gated(groups.membershipCommands().buildKick(), CatalogPermissions.ISLAND_KICK))
                .then(groups.membershipCommands().buildLeave())
                .then(groups.membershipCommands().buildMembers())
                .then(groups.membershipCommands().buildRole())
                .then(groups.membershipCommands().buildPermissions())
                .then(groups.infoCommands().buildInfo())
                .then(groups.chatCommands().buildAllianceChat())
                .then(groups.chatCommands().buildAllianceChatAlias())
                .then(groups.seasonCommands().buildSeason())
                .then(groups.reloadCommands().buildReload())
                .then(groups.doctorCommands().buildDoctor())
                .then(gated(groups.upgradeCommands().build(), CatalogPermissions.ISLAND_UPGRADE))
                .then(groups.shopCommands().build())
                // The menu file and the documents both say upgrades; a player typing the singular
                // should not be told there is no such command.
                .then(gated(groups.upgradeCommands().buildUnder("upgrade"), CatalogPermissions.ISLAND_UPGRADE))
                .then(groups.trustCommands().buildTrust())
                .then(groups.trustCommands().buildUntrust())
                .then(groups.trustCommands().buildTrusted())
                .then(vaultBranch("vault"))
                // The design document publishes /is chest for the same window. A command an
                // operator reads about and types is either there or the document is wrong, and the
                // command is cheaper than the correction.
                .then(vaultBranch("chest"))
                .then(groups.warpCommands().buildWarpLock())
                .then(groups.warpCommands().buildWarpUnlock())
                .then(groups.lifecycleCommands().buildRename())
                .then(groups.adminCommands().buildRestore())
                .then(gated(groups.bankCommands().build(), CatalogPermissions.ISLAND_BANK))
                .then(gated(groups.progressionCommands().buildBiome(), CatalogPermissions.ISLAND_BIOME))
                .then(gated(groups.progressionCommands().buildTop(), CatalogPermissions.ISLAND_TOP))
                .then(Cmd.literal("profile")
                        .then(Cmd.literal("switch")
                                .then(Cmd.argument("profileId", StringArgumentType.word())
                                        .executes(this::executeProfileSwitch))))
                .then(groups.chatCommands().buildChat())
                .then(groups.chatCommands().buildChatAlias())
                .then(groups.chatCommands().buildSpy())
                .then(groups.adminCommands().buildAdmin());
        return root;
    }

    private void sendFeedback(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    private void send(Audience audience, Component component) {
        sendFeedback(audience, component);
    }

    private void send(Audience audience, String key) {
        sendFeedback(audience, messages.render(audience, key));
    }

    private int executeRoot(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player && features.controlMenu() != null) {
            features.controlMenu().open(player);
            return Cmd.OK;
        }
        return executeHelp(ctx);
    }

    private int executeMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        if (features.controlMenu() != null) {
            features.controlMenu().open(player);
        } else {
            send(player, "menu.not_enabled");
        }
        return Cmd.OK;
    }

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        send(sender, "help.header");
        for (Component line : messages.renderAll(sender, "help.lines")) {
            send(sender, line);
        }
        if (sender.hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                || sender.hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                || sender.hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                || sender.isOp()) {
            for (Component line : messages.renderAll(sender, "help.admin_lines")) {
                send(sender, line);
            }
        }
        return Cmd.OK;
    }

    private int executeVault(CommandContext<CommandSourceStack> ctx, int page) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandVaultWindow window = this.vaultWindow;
        if (window == null) {
            send(player, "vault.disabled");
            return Cmd.OK;
        }
        window.open(player, page);
        return Cmd.OK;
    }

    private int executeVaultLog(CommandContext<CommandSourceStack> ctx, int lines) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandVaultWindow window = this.vaultWindow;
        if (window == null) {
            send(player, "vault.disabled");
            return Cmd.OK;
        }
        window.showLog(player, lines);
        return Cmd.OK;
    }

    private int executeProfileSwitch(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        String rawProf = StringArgumentType.getString(ctx, "profileId");
        try {
            UUID profUuid = UUID.fromString(rawProf);
            sessionCoordinator.switchProfile(player, new ProfileId(profUuid));
        } catch (IllegalArgumentException e) {
            send(player, "profile.invalid_uuid");
        }
        return Cmd.OK;
    }
}
