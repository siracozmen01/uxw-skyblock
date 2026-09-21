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
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
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

    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService islandLocationService;
    private final IslandBankService islandBankService;
    private final IslandUpgradeStoragePort islandUpgradePort;
    private final IslandLeaderboardService islandLeaderboardService;
    private final BiomeModificationPort biomeModificationPort;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final IslandProtectionListener protectionListener;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final HomeConfiguration homeConfiguration;
    private volatile @Nullable HomeService homeService;
    private volatile @Nullable IslandVaultWindow vaultWindow;
    private volatile @Nullable ActivityFeedService activityFeedService;
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final SkyblockEconomyBridge economyBridge;
    private final IslandFeatures features;
    private volatile @Nullable IslandBankruptcyService bankruptcyService;
    private volatile @Nullable IslandNameService nameService;
    private volatile @Nullable IslandNetworkRouter networkRouter;
    private volatile @Nullable IslandRestoreService restoreService;
    private volatile @Nullable BackupService backupService;

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
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

    public void setBackupService(@Nullable BackupService backupService) {
        this.backupService = backupService;
    }

    public @Nullable BackupService backupService() {
        return backupService;
    }

    public void register(JavaPlugin plugin) {
        CommandGroups groups = buildGroups();
        LiteralArgumentBuilder<CommandSourceStack> root = assembleRoot(groups);
        CommandRegistrar.register(plugin, root, "Main Skyblock command tree", "is");
    }

    /**
     * The command groups this tree is made of.
     *
     * <p>Building nine of them and assembling the tree out of them were one method, which is how
     * that method reached the size the standards draw a line at. The two halves answer different
     * questions: what the verbs are, and where they hang.
     */
    private record CommandGroups(
            IslandBankCommands bankCommands,
            IslandChatCommands chatCommands,
            IslandAdminCommands adminCommands,
            IslandLifecycleCommands lifecycleCommands,
            IslandNavigationCommands navigationCommands,
            IslandProgressionCommands progressionCommands,
            IslandMechanicsCommands mechanicsCommands,
            IslandActivityCommands activityCommands,
            IslandHomeCommands homeCommands) {}

    private CommandGroups buildGroups() {

        IslandBankCommands bankCommands = new IslandBankCommands(
                islandBankService,
                islandLocationService,
                economyBridge,
                schedulerPort,
                serverNodeId,
                () -> bankruptcyService,
                messages,
                sessionCoordinator);

        IslandChatCommands chatCommands =
                new IslandChatCommands(() -> features.chatService(), schedulerPort, messages, sessionCoordinator);

        IslandAdminCommands adminCommands = new IslandAdminCommands(
                () -> features.inactivityService(),
                () -> features.freezeService(),
                () -> restoreService,
                () -> backupService,
                () -> features.recycleService(),
                protectionListener,
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                worldName,
                messages);

        IslandLifecycleCommands lifecycleCommands = new IslandLifecycleCommands(
                createIslandUseCase,
                islandLocationService,
                presetCatalog,
                schematicEngine,
                protectionListener,
                sessionCoordinator,
                schedulerPort,
                serverNodeId,
                worldName,
                () -> features.antiAbuseService(),
                () -> features.recycleService(),
                () -> features.resetMenu(),
                () -> nameService,
                messages);

        IslandNavigationCommands navigationCommands = new IslandNavigationCommands(
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                worldName,
                () -> features.dimensionListener(),
                () -> networkRouter,
                messages);

        IslandProgressionCommands progressionCommands = new IslandProgressionCommands(
                islandLocationService,
                islandBankService,
                islandLeaderboardService,
                biomeModificationPort,
                sessionCoordinator,
                schedulerPort,
                () -> features.worthService(),
                messages);

        IslandMechanicsCommands mechanicsCommands = new IslandMechanicsCommands(
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                () -> features.limitService(),
                () -> features.antiAbuseService(),
                () -> features.boosterService(),
                () -> features.boosterMenu(),
                () -> features.missionsMenu(),
                () -> features.boundaryService(),
                messages);

        IslandActivityCommands activityCommands = new IslandActivityCommands(
                () -> activityFeedService, islandLocationService, schedulerPort, messages, sessionCoordinator);

        IslandHomeCommands homeCommands = new IslandHomeCommands(
                () -> homeService,
                islandLocationService,
                schedulerPort,
                homeConfiguration,
                messages,
                sessionCoordinator);

        return new CommandGroups(
                bankCommands,
                chatCommands,
                adminCommands,
                lifecycleCommands,
                navigationCommands,
                progressionCommands,
                mechanicsCommands,
                activityCommands,
                homeCommands);
    }

    private LiteralArgumentBuilder<CommandSourceStack> assembleRoot(CommandGroups groups) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(groups.mechanicsCommands().buildMissions())
                .then(groups.mechanicsCommands().buildChallenges())
                .then(groups.mechanicsCommands().buildBorder())
                .then(groups.mechanicsCommands().buildBounds())
                .then(groups.progressionCommands().buildLevel())
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
