package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 *
 * <p>All storage, persistence, and distributed authority I/O is dispatched asynchronously off tick threads
 * via {@link SchedulerPort#async(Runnable)}. Platform mutations, player teleports, inventory updates, and
 * player feedback are strictly scheduled onto the player's owning Folia {@link org.bukkit.entity.Entity} region
 * thread via {@link SchedulerPort#onEntity(PlayerUuid, Runnable)}.
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
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final SkyblockEconomyBridge economyBridge;
    private final @Nullable IslandControlMenu controlMenu;
    private final @Nullable IslandChatService chatService;
    private final @Nullable IslandInactivityService inactivityService;
    private final @Nullable IslandAdminFreezeService freezeService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandBoundaryService boundaryService;

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
                serverNodeId,
                worldName,
                SkyblockEconomyBridge.createDefault(islandBankService, schedulerPort),
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu) {
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
                serverNodeId,
                worldName,
                economyBridge,
                controlMenu,
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService) {
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
                serverNodeId,
                worldName,
                economyBridge,
                controlMenu,
                chatService,
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService,
            @Nullable IslandInactivityService inactivityService) {
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
                serverNodeId,
                worldName,
                economyBridge,
                controlMenu,
                chatService,
                inactivityService,
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService,
            @Nullable IslandInactivityService inactivityService,
            @Nullable IslandAdminFreezeService freezeService) {
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
                serverNodeId,
                worldName,
                economyBridge,
                controlMenu,
                chatService,
                inactivityService,
                freezeService,
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService,
            @Nullable IslandInactivityService inactivityService,
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable IslandMissionsMenu missionsMenu) {
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
                serverNodeId,
                worldName,
                economyBridge,
                controlMenu,
                chatService,
                inactivityService,
                freezeService,
                missionsMenu,
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
            ServerNodeId serverNodeId,
            String worldName,
            SkyblockEconomyBridge economyBridge,
            @Nullable IslandControlMenu controlMenu,
            @Nullable IslandChatService chatService,
            @Nullable IslandInactivityService inactivityService,
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable IslandMissionsMenu missionsMenu,
            @Nullable IslandBoundaryService boundaryService) {
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
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.controlMenu = controlMenu;
        this.chatService = chatService;
        this.inactivityService = inactivityService;
        this.freezeService = freezeService;
        this.missionsMenu = missionsMenu;
        this.boundaryService = boundaryService;
    }

    public IslandUpgradeStoragePort islandUpgradePort() {
        return islandUpgradePort;
    }

    public void register(JavaPlugin plugin) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(Cmd.literal("missions").executes(this::executeMissions))
                .then(Cmd.literal("challenges").executes(this::executeMissions))
                .then(Cmd.literal("border").executes(this::executeBorder))
                .then(Cmd.literal("bounds").executes(this::executeBorder))
                .then(Cmd.literal("create")
                        .executes(ctx ->
                                executeCreate(ctx, presetCatalog.defaultPreset().id()))
                        .then(Cmd.argument("preset", StringArgumentType.word())
                                .executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "preset")))))
                .then(Cmd.literal("home").executes(this::executeHome))
                .then(Cmd.literal("go").executes(this::executeHome))
                .then(Cmd.literal("setspawn").executes(this::executeSetSpawn))
                .then(Cmd.literal("bank")
                        .executes(this::executeBankBalance)
                        .then(Cmd.literal("balance").executes(this::executeBankBalance))
                        .then(Cmd.literal("deposit")
                                .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                        .executes(this::executeBankDeposit)))
                        .then(Cmd.literal("withdraw")
                                .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                        .executes(this::executeBankWithdraw))))
                .then(Cmd.literal("biome")
                        .then(Cmd.argument("type", StringArgumentType.word()).executes(this::executeBiomeChange)))
                .then(Cmd.literal("top")
                        .executes(ctx -> executeTop(ctx, "level"))
                        .then(Cmd.argument("category", StringArgumentType.word())
                                .executes(ctx -> executeTop(ctx, StringArgumentType.getString(ctx, "category")))))
                .then(Cmd.literal("profile")
                        .then(Cmd.literal("switch")
                                .then(Cmd.argument("profileId", StringArgumentType.word())
                                        .executes(this::executeProfileSwitch))))
                .then(Cmd.literal("chat")
                        .executes(this::executeChatToggle)
                        .then(Cmd.argument("message", StringArgumentType.greedyString())
                                .executes(this::executeChatMessage)))
                .then(Cmd.literal("c")
                        .executes(this::executeChatToggle)
                        .then(Cmd.argument("message", StringArgumentType.greedyString())
                                .executes(this::executeChatMessage)))
                .then(Cmd.literal("spy").executes(this::executeSpyToggle))
                .then(Cmd.literal("admin")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                                || src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                || src.getSender().hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                                || src.getSender().isOp())
                        .then(Cmd.literal("inactivity")
                                .then(Cmd.literal("scan").executes(this::executeAdminInactivityScan)))
                        .then(Cmd.literal("freeze")
                                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                        || src.getSender().isOp())
                                .then(Cmd.argument("target", StringArgumentType.word())
                                        .executes(ctx -> executeAdminFreeze(ctx, "Administrative quarantine"))
                                        .then(Cmd.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> executeAdminFreeze(
                                                        ctx, StringArgumentType.getString(ctx, "reason"))))))
                        .then(Cmd.literal("unfreeze")
                                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                        || src.getSender().isOp())
                                .then(Cmd.argument("target", StringArgumentType.word())
                                        .executes(this::executeAdminUnfreeze)))
                        .then(Cmd.literal("inspect")
                                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                        || src.getSender().isOp())
                                .then(Cmd.argument("target", StringArgumentType.word())
                                        .executes(this::executeAdminInspect))));

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

    private int executeRoot(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player && controlMenu != null) {
            controlMenu.open(player);
            return Cmd.OK;
        }
        return executeHelp(ctx);
    }

    private int executeMenu(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can open the island menu.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (controlMenu != null) {
            controlMenu.open(player);
        } else {
            send(player, Component.text("Island menu is not enabled on this node.", NamedTextColor.RED));
        }
        return Cmd.OK;
    }

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        send(src.getSender(), Component.text("--- UXPLIMA Skyblock Commands ---", NamedTextColor.GOLD));
        send(src.getSender(), Component.text("/is menu - Open interactive island panel", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is create [preset] - Create your island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is home - Teleport to your island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is setspawn - Set your island spawn", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is bank [deposit|withdraw|balance] - Manage island bank", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is biome <type> - Change island biome", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is top [level|worth|bank] - View leaderboards", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is profile switch <uuid> - Switch active profile", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is chat - Toggle island team chat", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text(
                        "/is chat <message> (or /is c <msg>) - Send message to island team", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is spy - Toggle island chat staff spy", NamedTextColor.YELLOW));
        if (src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                || src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                || src.getSender().hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                || src.getSender().isOp()) {
            send(
                    src.getSender(),
                    Component.text("/is admin inactivity scan - Trigger manual inactivity scan", NamedTextColor.RED));
            send(
                    src.getSender(),
                    Component.text(
                            "/is admin freeze <target> [reason] - Quarantine and freeze island", NamedTextColor.RED));
            send(
                    src.getSender(),
                    Component.text(
                            "/is admin unfreeze <target> - Lift quarantine and unfreeze island", NamedTextColor.RED));
            send(
                    src.getSender(),
                    Component.text(
                            "/is admin inspect <target> - Inspect island dimensions and quarantine state",
                            NamedTextColor.RED));
        }
        return Cmd.OK;
    }

    private int executeAdminInactivityScan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (inactivityService == null) {
            send(src.getSender(), Component.text("Inactivity service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        send(src.getSender(), Component.text("Starting asynchronous island inactivity scan...", NamedTextColor.YELLOW));
        schedulerPort.async(() -> {
            try {
                IslandInactivityScanReport report = inactivityService.scanWorld(worldName, Instant.now());
                send(
                        src.getSender(),
                        Component.text(
                                String.format(
                                        "Inactivity scan complete: %d evaluated, %d successions, %d archived, %d deleted, %d skipped.",
                                        report.totalEvaluated(),
                                        report.successionsExecuted(),
                                        report.islandsArchived(),
                                        report.islandsDeleted(),
                                        report.islandsSkipped()),
                                NamedTextColor.GREEN));
            } catch (Exception e) {
                send(src.getSender(), Component.text("Inactivity scan failed: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private Optional<IslandId> resolveIslandId(String target) {
        try {
            return Optional.of(IslandId.of(UUID.fromString(target)));
        } catch (IllegalArgumentException notUuid) {
            Player online = Bukkit.getPlayerExact(target);
            if (online != null) {
                Optional<ProfileId> optProfile = activeProfile(online);
                if (optProfile.isPresent()) {
                    Optional<IslandId> id = islandLocationService.findIslandId(optProfile.get());
                    if (id.isPresent()) {
                        return id;
                    }
                }
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            if (offline.hasPlayedBefore() || offline.isOnline()) {
                ProfileId profileId = new ProfileId(offline.getUniqueId());
                return islandLocationService.findIslandId(profileId);
            }
            return Optional.empty();
        }
    }

    private int executeAdminFreeze(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSourceStack src = ctx.getSource();
        if (freezeService == null) {
            send(src.getSender(), Component.text("Freeze service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.freezeIsland(islandId, reason, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize(
                                        "<green>Successfully quarantined and froze island <yellow>" + islandId.value()
                                                + "</yellow> with reason: <aqua>" + reason + "</aqua></green>"));
            } catch (Exception e) {
                send(src.getSender(), Component.text("Failed to freeze island: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminUnfreeze(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (freezeService == null) {
            send(src.getSender(), Component.text("Freeze service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.unfreezeIsland(islandId, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize(
                                        "<green>Successfully lifted administrative quarantine and unfroze island <yellow>"
                                                + islandId.value() + "</yellow></green>"));
            } catch (Exception e) {
                send(
                        src.getSender(),
                        Component.text("Failed to unfreeze island: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            Optional<Island> optIsland = freezeService != null ? freezeService.findIsland(islandId) : Optional.empty();
            if (optIsland.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Island record not found: " + islandId.value(), NamedTextColor.RED));
                return;
            }

            Island island = optIsland.get();
            Optional<IslandLocation> optLoc = freezeService.findLocation(islandId);

            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gold>--- Island Inspection: <yellow>" + islandId.value()
                                    + "</yellow> ---</gold>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Owner UUID: <white>"
                                    + island.ownerPlayerUuid().value() + "</white></gray>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Lifecycle: <green>"
                                    + island.lifecycle().name() + "</green></gray>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Economic State: <aqua>"
                                    + island.economicState().name() + "</aqua></gray>"));
            String adminColor = island.isFrozen() ? "<red><bold>FROZEN</bold></red>" : "<green>NORMAL</green>";
            send(
                    src.getSender(),
                    MiniMessage.miniMessage().deserialize("<gray>Administrative State: " + adminColor + "</gray>"));
            if (island.isFrozen()) {
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize("<gray>Freeze Reason: <yellow>"
                                        + (island.freezeReason() != null ? island.freezeReason() : "None")
                                        + "</yellow></gray>"));
            }
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<gray>Members: <white>" + island.members().size() + "</white> | Roles: <white>"
                                            + island.roles().size() + "</white></gray>"));
            optLoc.ifPresent(loc -> send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Location: <white>" + loc.worldName() + " ("
                                    + loc.bounds().centerX() + ", "
                                    + loc.bounds().centerZ() + ") radius="
                                    + loc.bounds().radius() + "</white></gray>")));
        });
        return Cmd.OK;
    }

    private int executeProfileSwitch(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can switch profiles.", NamedTextColor.RED));
            return Cmd.OK;
        }
        String rawProf = StringArgumentType.getString(ctx, "profileId");
        try {
            UUID profUuid = UUID.fromString(rawProf);
            sessionCoordinator.switchProfile(player, new ProfileId(profUuid));
        } catch (IllegalArgumentException e) {
            send(player, Component.text("Invalid profile UUID format.", NamedTextColor.RED));
        }
        return Cmd.OK;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.of(new ProfileId(player.getUniqueId()));
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, String presetId) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can create an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result =
                    createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, worldName);

            schedulerPort.onEntity(playerUuid, () -> {
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    protectionListener.cacheIsland(success.island());

                    World resolvedWorld = Bukkit.getWorld(worldName);
                    if (resolvedWorld != null) {
                        int centerX = success.location().bounds().centerX();
                        int centerZ = success.location().bounds().centerZ();
                        int spawnY = 100;
                        int chunkX = centerX >> 4;
                        int chunkZ = centerZ >> 4;
                        String targetWorld = resolvedWorld.getName();

                        schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                            World w = Bukkit.getWorld(targetWorld);
                            if (w != null) {
                                schematicEngine.pastePreset(w, centerX, spawnY, centerZ, success.preset());
                            }
                            schedulerPort.onEntity(playerUuid, () -> {
                                if (!player.isOnline()) {
                                    return;
                                }
                                Location destination = new Location(
                                        w != null ? w : resolvedWorld,
                                        success.location().spawnX(),
                                        success.location().spawnY(),
                                        success.location().spawnZ(),
                                        0.0f,
                                        0.0f);
                                var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                                    if (Boolean.TRUE.equals(teleported)) {
                                        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                                        player.setFallDistance(0.0f);
                                    }
                                });
                                send(
                                        player,
                                        Component.text(
                                                "Island created successfully with preset '"
                                                        + success.preset().displayName() + "'!",
                                                NamedTextColor.GREEN));
                            });
                        });
                    } else {
                        send(
                                player,
                                Component.text(
                                        "Island created, but world '" + worldName + "' is not loaded on this node.",
                                        NamedTextColor.YELLOW));
                    }
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    send(
                            player,
                            Component.text(
                                    "You already own or belong to an island! Use /is home to visit it.",
                                    NamedTextColor.RED));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unknown) {
                    send(
                            player,
                            Component.text(
                                    "Unknown preset '" + unknown.presetId()
                                            + "'. Available: classic, desert, nether, cave.",
                                    NamedTextColor.RED));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.Failure failure) {
                    send(player, Component.text("Failed to create island: " + failure.reason(), NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeHome(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can teleport to an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandLocation> optLoc = islandLocationService.resolveHome(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optLoc.isEmpty()) {
                    send(
                            player,
                            Component.text(
                                    "You do not have an island yet! Use /is create to get started.",
                                    NamedTextColor.RED));
                    return;
                }
                IslandLocation loc = optLoc.get();
                World world = Bukkit.getWorld(loc.worldName());
                if (world != null) {
                    Location destination = new Location(
                            world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
                    var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                        if (Boolean.TRUE.equals(teleported)) {
                            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                            player.setFallDistance(0.0f);
                        }
                    });
                    send(player, Component.text("Welcome to your island!", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeSetSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can set spawn.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        Location current = player.getLocation();
        String currentWorld = current.getWorld() != null ? current.getWorld().getName() : this.worldName;
        double x = current.getX();
        double y = current.getY();
        double z = current.getZ();
        float yaw = current.getYaw();
        float pitch = current.getPitch();

        schedulerPort.async(() -> {
            boolean updated = islandLocationService.updateSpawn(profileId, currentWorld, x, y, z, yaw, pitch);
            schedulerPort.onEntity(playerUuid, () -> {
                if (updated) {
                    send(player, Component.text("Island spawn location updated.", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankBalance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Long> optBalance = islandBankService.getBalanceMinorUnits(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optBalance.isEmpty()) {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                } else {
                    send(
                            player,
                            Component.text(
                                    "Island Bank Balance: $" + (optBalance.get() / 100.0), NamedTextColor.GREEN));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        economyBridge.depositToIslandBank(player, profileId, amount, serverNodeId, outcome -> {
            if (outcome instanceof BankTransactionOutcome.Success) {
                send(player, Component.text("Deposited $" + amount + " into the island bank.", NamedTextColor.GREEN));
            } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                send(player, Component.text("Insufficient funds in your personal wallet.", NamedTextColor.RED));
            } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rej) {
                send(player, Component.text("Deposit rejected: " + rej.reason(), NamedTextColor.RED));
            } else {
                send(player, Component.text("Deposit failed: " + outcome, NamedTextColor.RED));
            }
        });

        return Cmd.OK;
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        economyBridge.withdrawFromIslandBank(player, profileId, amount, serverNodeId, outcome -> {
            if (outcome instanceof BankTransactionOutcome.Success) {
                send(player, Component.text("Withdrew $" + amount + " from the island bank.", NamedTextColor.GREEN));
            } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                send(player, Component.text("Insufficient funds in the island bank.", NamedTextColor.RED));
            } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rej) {
                send(player, Component.text("Withdrawal rejected: " + rej.reason(), NamedTextColor.RED));
            } else {
                send(player, Component.text("Withdrawal failed: " + outcome, NamedTextColor.RED));
            }
        });

        return Cmd.OK;
    }

    private int executeBiomeChange(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String biomeName = StringArgumentType.getString(ctx, "type");
        Optional<IslandBiome> optBiome = IslandBiome.fromId(biomeName);
        if (optBiome.isEmpty()) {
            send(player, Component.text("Unknown biome '" + biomeName + "'.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        IslandBiome targetBiome = optBiome.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(player, Component.text("You do not have an island.", NamedTextColor.RED)));
                return;
            }

            var unused = biomeModificationPort
                    .applyBiome(optIslandId.get(), targetBiome)
                    .thenAccept(success -> {
                        schedulerPort.onEntity(playerUuid, () -> {
                            if (success) {
                                send(
                                        player,
                                        Component.text(
                                                "Island biome changed to " + targetBiome.displayName() + "!",
                                                NamedTextColor.GREEN));
                            } else {
                                send(player, Component.text("Failed to update island biome.", NamedTextColor.RED));
                            }
                        });
                    });
        });

        return Cmd.OK;
    }

    private int executeTop(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        LeaderboardCategory cat =
                switch (category.toLowerCase(Locale.ROOT)) {
                    case "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> LeaderboardCategory.LEVEL;
                };

        schedulerPort.async(() -> {
            var entries = islandLeaderboardService.getTop(cat, 10);
            schedulerPort.onGlobal(() -> {
                send(src.getSender(), Component.text("--- Top Islands (" + cat.name() + ") ---", NamedTextColor.GOLD));
                if (entries.isEmpty()) {
                    send(src.getSender(), Component.text("No islands ranked yet.", NamedTextColor.GRAY));
                } else {
                    for (LeaderboardEntry entry : entries) {
                        String name = entry.islandName() != null
                                ? entry.islandName()
                                : entry.islandId().toString().substring(0, 8);
                        send(
                                src.getSender(),
                                Component.text(
                                        "#" + entry.rank() + " " + name + " - " + entry.formattedScore(),
                                        NamedTextColor.YELLOW));
                    }
                }
            });
        });

        return Cmd.OK;
    }

    private int executeChatToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can use island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            try {
                IslandChatChannel newChannel = chatService.toggleChannel(profileId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (newChannel == IslandChatChannel.ISLAND) {
                        send(
                                player,
                                Component.text(
                                        "Island chat enabled. All chat messages will now go to your island team.",
                                        NamedTextColor.GREEN));
                    } else {
                        send(
                                player,
                                Component.text(
                                        "Island chat disabled. Chat messages will now go to public chat.",
                                        NamedTextColor.YELLOW));
                    }
                });
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You must belong to an island to use island chat.", NamedTextColor.RED)));
            }
        });
        return Cmd.OK;
    }

    private int executeChatMessage(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can use island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        String message = StringArgumentType.getString(ctx, "message");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            try {
                chatService.sendChat(profileId, player.getName(), message);
            } catch (NoIslandForChatException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You must belong to an island to use island chat.", NamedTextColor.RED)));
            } catch (IslandChatPermissionDeniedException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You do not have permission to send messages in island chat.",
                                        NamedTextColor.RED)));
            } catch (ChatRateLimitExceededException e) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(
                                player,
                                Component.text(
                                        "You are sending messages too quickly. Please slow down.",
                                        NamedTextColor.RED)));
            }
        });
        return Cmd.OK;
    }

    private int executeSpyToggle(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can spy on island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (chatService == null) {
            send(player, Component.text("Island chat is not enabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (!player.hasPermission(CatalogPermissions.CHAT_SPY.node()) && !player.hasPermission("skyblock.chat.spy")) {
            send(player, Component.text("You do not have permission to spy on island chat.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        boolean enabled = chatService.toggleSpy(profileId);
        if (enabled) {
            send(player, Component.text("Island chat spy enabled.", NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Island chat spy disabled.", NamedTextColor.YELLOW));
        }
        return Cmd.OK;
    }

    private int executeMissions(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can view missions.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (missionsMenu == null) {
            send(player, Component.text("Missions are not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> missionsMenu.open(player));
        return Cmd.OK;
    }

    private int executeBorder(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can toggle border view.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (boundaryService == null) {
            send(player, Component.text("Island boundary visualization is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        boolean active = boundaryService.togglePerimeter(uuid);
        if (active) {
            send(player, Component.text("Perimeter particle projection enabled. Outlines will project around your island boundary.", NamedTextColor.AQUA));
        } else {
            send(player, Component.text("Perimeter particle projection disabled.", NamedTextColor.YELLOW));
        }
        return Cmd.OK;
    }
}
