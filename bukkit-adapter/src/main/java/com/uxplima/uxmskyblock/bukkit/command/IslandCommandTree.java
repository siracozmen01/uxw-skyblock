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
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
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
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final SkyblockEconomyBridge economyBridge;
    private final @Nullable IslandControlMenu controlMenu;
    private final @Nullable IslandChatService chatService;
    private final @Nullable IslandInactivityService inactivityService;
    private final @Nullable IslandAdminFreezeService freezeService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandBoundaryService boundaryService;
    private final @Nullable IslandRecycleService recycleService;
    private final @Nullable IslandResetConfirmationMenu resetMenu;
    private final @Nullable IslandWorthService worthService;
    private final @Nullable IslandDimensionListener dimensionListener;
    private final @Nullable IslandLimitService limitService;
    private final @Nullable IslandAntiAbuseService antiAbuseService;
    private final @Nullable IslandBoosterService boosterService;
    private final @Nullable IslandBoosterMenu boosterMenu;
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
                serverNodeId,
                worldName,
                SkyblockEconomyBridge.createDefault(islandBankService, schedulerPort),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService,
            @Nullable IslandInactivityService inactivityService,
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable IslandMissionsMenu missionsMenu,
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu,
            @Nullable IslandWorthService worthService,
            @Nullable IslandDimensionListener dimensionListener,
            @Nullable IslandLimitService limitService,
            @Nullable IslandAntiAbuseService antiAbuseService,
            @Nullable IslandBoosterService boosterService,
            @Nullable IslandBoosterMenu boosterMenu) {
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
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.controlMenu = controlMenu;
        this.chatService = chatService;
        this.inactivityService = inactivityService;
        this.freezeService = freezeService;
        this.missionsMenu = missionsMenu;
        this.boundaryService = boundaryService;
        this.recycleService = recycleService;
        this.resetMenu = resetMenu;
        this.worthService = worthService;
        this.dimensionListener = dimensionListener;
        this.limitService = limitService;
        this.antiAbuseService = antiAbuseService;
        this.boosterService = boosterService;
        this.boosterMenu = boosterMenu;
    }

    public IslandUpgradeStoragePort islandUpgradePort() {
        return islandUpgradePort;
    }

    public @Nullable IslandWorthService worthService() {
        return worthService;
    }

    public void setBankruptcyService(@Nullable IslandBankruptcyService bankruptcyService) {
        this.bankruptcyService = bankruptcyService;
    }

    public @Nullable IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return dimensionListener;
    }

    public @Nullable IslandLimitService limitService() {
        return limitService;
    }

    public @Nullable IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
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
                new IslandChatCommands(() -> chatService, schedulerPort, messages, sessionCoordinator);

        IslandAdminCommands adminCommands = new IslandAdminCommands(
                () -> inactivityService,
                () -> freezeService,
                () -> restoreService,
                () -> backupService,
                () -> recycleService,
                protectionListener,
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                worldName);

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
                () -> antiAbuseService,
                () -> recycleService,
                () -> resetMenu,
                () -> nameService);

        IslandNavigationCommands navigationCommands = new IslandNavigationCommands(
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                worldName,
                () -> dimensionListener,
                () -> networkRouter,
                messages);

        IslandProgressionCommands progressionCommands = new IslandProgressionCommands(
                islandLocationService,
                islandBankService,
                islandLeaderboardService,
                biomeModificationPort,
                sessionCoordinator,
                schedulerPort,
                () -> worthService,
                messages);

        IslandMechanicsCommands mechanicsCommands = new IslandMechanicsCommands(
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                () -> limitService,
                () -> antiAbuseService,
                () -> boosterService,
                () -> boosterMenu,
                () -> missionsMenu,
                () -> boundaryService);

        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(mechanicsCommands.buildMissions())
                .then(mechanicsCommands.buildChallenges())
                .then(mechanicsCommands.buildBorder())
                .then(mechanicsCommands.buildBounds())
                .then(progressionCommands.buildLevel())
                .then(progressionCommands.buildWorth())
                .then(progressionCommands.buildValue())
                .then(lifecycleCommands.buildReset())
                .then(lifecycleCommands.buildDelete())
                .then(lifecycleCommands.buildCreate())
                .then(navigationCommands.buildHome())
                .then(navigationCommands.buildGo())
                .then(navigationCommands.buildVisit())
                .then(navigationCommands.buildNether())
                .then(navigationCommands.buildEnd())
                .then(mechanicsCommands.buildLimits())
                .then(mechanicsCommands.buildQuarantine())
                .then(mechanicsCommands.buildBooster())
                .then(navigationCommands.buildSetSpawn())
                .then(lifecycleCommands.buildRename())
                .then(adminCommands.buildRestore())
                .then(bankCommands.build())
                .then(progressionCommands.buildBiome())
                .then(progressionCommands.buildTop())
                .then(Cmd.literal("profile")
                        .then(Cmd.literal("switch")
                                .then(Cmd.argument("profileId", StringArgumentType.word())
                                        .executes(this::executeProfileSwitch))))
                .then(chatCommands.buildChat())
                .then(chatCommands.buildChatAlias())
                .then(chatCommands.buildSpy())
                .then(adminCommands.buildAdmin());

        CommandRegistrar.register(plugin, root, "Main Skyblock command tree", "is");
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
        if (ctx.getSource().getSender() instanceof Player player && controlMenu != null) {
            controlMenu.open(player);
            return Cmd.OK;
        }
        return executeHelp(ctx);
    }

    private int executeMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        if (controlMenu != null) {
            controlMenu.open(player);
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
