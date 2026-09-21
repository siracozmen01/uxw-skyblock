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
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
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
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
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
    volatile @Nullable IslandWarpService warpService;
    volatile @Nullable IslandSocialService socialService;
    volatile @Nullable IslandAllianceService allianceService;
    volatile @Nullable RewardInboxService rewardInboxService;
    volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    volatile @Nullable IslandMissionService missionService;

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

    public @Nullable BackupService backupService() {
        return backupService;
    }

    public void register(JavaPlugin plugin) {
        CommandRegistrar.register(plugin, buildRoot(), "Main Skyblock command tree", "is");
    }

    /**
     * The whole command tree, assembled but not registered.
     *
     * <p>Exposed so a test can hand it to Brigadier and ask whether a line parses. A guard that reads
     * the source instead can only guess at the shape, and one that guesses is believed until the day
     * a player types the line it guessed about.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return assembleRoot(new CommandGroupBuilder(this).build());
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
                .then(groups.lifecycleCommands().buildDelete())
                .then(groups.lifecycleCommands().buildCreate())
                .then(groups.navigationCommands().buildHome())
                .then(groups.navigationCommands().buildGo())
                .then(groups.navigationCommands().buildVisit())
                .then(groups.navigationCommands().buildNether())
                .then(groups.navigationCommands().buildEnd())
                .then(groups.mechanicsCommands().buildLimits())
                .then(groups.mechanicsCommands().buildQuarantine())
                .then(groups.mechanicsCommands().buildBooster())
                .then(groups.navigationCommands().buildSetSpawn())
                .then(groups.homeCommands().buildSetHome())
                .then(groups.homeCommands().buildTravelHome())
                .then(groups.homeCommands().buildNamedHome())
                .then(groups.homeCommands().buildDeleteHome())
                .then(groups.activityCommands().build())
                .then(groups.warpCommands().build())
                .then(groups.socialCommands().buildGuestbook())
                .then(groups.socialCommands().buildRate())
                .then(groups.socialCommands().buildBookmarks())
                .then(groups.allianceCommands().build())
                .then(groups.rewardCommands().build())
                .then(groups.flagCommands().build())
                .then(groups.visitorCommands().buildBan())
                .then(groups.visitorCommands().buildUnban())
                .then(groups.visitorCommands().buildBans())
                .then(groups.visitorCommands().buildLock())
                .then(groups.visitorCommands().buildUnlock())
                .then(Cmd.literal("vault")
                        .executes(ctx -> executeVault(ctx, 1))
                        .then(Cmd.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ctx -> executeVault(
                                        ctx,
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "page")))))
                .then(groups.lifecycleCommands().buildRename())
                .then(groups.adminCommands().buildRestore())
                .then(groups.bankCommands().build())
                .then(groups.progressionCommands().buildBiome())
                .then(groups.progressionCommands().buildTop())
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
