package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
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
import com.uxplima.uxmskyblock.core.application.network.RouteOutcome;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.limit.LimitCategory;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
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
                boundaryService,
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
            @Nullable IslandAdminFreezeService freezeService,
            @Nullable IslandMissionsMenu missionsMenu,
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu) {
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
                boundaryService,
                recycleService,
                resetMenu,
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
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu,
            @Nullable IslandWorthService worthService) {
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
                boundaryService,
                recycleService,
                resetMenu,
                worthService,
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
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu,
            @Nullable IslandWorthService worthService,
            @Nullable IslandDimensionListener dimensionListener) {
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
                boundaryService,
                recycleService,
                resetMenu,
                worthService,
                dimensionListener,
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
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu,
            @Nullable IslandWorthService worthService,
            @Nullable IslandDimensionListener dimensionListener,
            @Nullable IslandLimitService limitService) {
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
                boundaryService,
                recycleService,
                resetMenu,
                worthService,
                dimensionListener,
                limitService,
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
            @Nullable IslandBoundaryService boundaryService,
            @Nullable IslandRecycleService recycleService,
            @Nullable IslandResetConfirmationMenu resetMenu,
            @Nullable IslandWorthService worthService,
            @Nullable IslandDimensionListener dimensionListener,
            @Nullable IslandLimitService limitService,
            @Nullable IslandAntiAbuseService antiAbuseService) {
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
                boundaryService,
                recycleService,
                resetMenu,
                worthService,
                dimensionListener,
                limitService,
                antiAbuseService,
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

    public void register(JavaPlugin plugin) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeRoot)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("menu").executes(this::executeMenu))
                .then(Cmd.literal("missions").executes(this::executeMissions))
                .then(Cmd.literal("challenges").executes(this::executeMissions))
                .then(Cmd.literal("border").executes(this::executeBorder))
                .then(Cmd.literal("bounds").executes(this::executeBorder))
                .then(Cmd.literal("level")
                        .executes(this::executeLevel)
                        .then(Cmd.literal("recalculate").executes(this::executeLevelRecalculate)))
                .then(Cmd.literal("worth").executes(this::executeWorth))
                .then(Cmd.literal("value").executes(this::executeWorth))
                .then(Cmd.literal("reset")
                        .executes(this::executeReset)
                        .then(Cmd.literal("confirm")
                                .then(Cmd.argument("code", StringArgumentType.word())
                                        .executes(this::executeResetConfirm))))
                .then(Cmd.literal("delete")
                        .executes(this::executeReset)
                        .then(Cmd.literal("confirm")
                                .then(Cmd.argument("code", StringArgumentType.word())
                                        .executes(this::executeResetConfirm))))
                .then(Cmd.literal("create")
                        .executes(ctx ->
                                executeCreate(ctx, presetCatalog.defaultPreset().id()))
                        .then(Cmd.argument("preset", StringArgumentType.word())
                                .executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "preset")))))
                .then(Cmd.literal("home").executes(this::executeHome))
                .then(Cmd.literal("go").executes(this::executeHome))
                .then(Cmd.literal("visit")
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(this::executeVisit)))
                .then(Cmd.literal("nether").executes(this::executeNether))
                .then(Cmd.literal("end").executes(this::executeEnd))
                .then(Cmd.literal("limits").executes(this::executeLimits))
                .then(Cmd.literal("quarantine").executes(this::executeQuarantine))
                .then(Cmd.literal("booster")
                        .executes(this::executeBooster)
                        .then(Cmd.literal("apply")
                                .then(Cmd.argument("category", StringArgumentType.word())
                                        .then(Cmd.argument("multiplier", DoubleArgumentType.doubleArg(1.0))
                                                .then(Cmd.argument("duration", StringArgumentType.word())
                                                        .executes(this::executeAdminApplyBooster))))))
                .then(Cmd.literal("setspawn").executes(this::executeSetSpawn))
                .then(Cmd.literal("rename")
                        .executes(this::executeGetRename)
                        .then(Cmd.argument("name", StringArgumentType.greedyString())
                                .executes(this::executeRename)))
                .then(Cmd.literal("bank")
                        .executes(this::executeBankBalance)
                        .then(Cmd.literal("balance").executes(this::executeBankBalance))
                        .then(Cmd.literal("status").executes(this::executeBankStatus))
                        .then(Cmd.literal("upkeep").executes(this::executeBankStatus))
                        .then(Cmd.literal("paydebt").executes(this::executeBankPayDebt))
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
                                        .executes(this::executeAdminInspect)))
                        .then(Cmd.literal("delete")
                                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                                        || src.getSender().isOp())
                                .then(Cmd.argument("target", StringArgumentType.word())
                                        .executes(this::executeAdminDelete))));

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
        send(src.getSender(), Component.text("/is nether - Teleport to your Nether island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is end - Teleport to your End island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is limits - View hardware & tile entity quotas", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is quarantine - View island quarantine status", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is booster - View active island boosters and multipliers", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is setspawn - Set your island spawn", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text(
                        "/is bank [deposit|withdraw|balance|status|paydebt] - Manage island bank & upkeep",
                        NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is biome <type> - Change island biome", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is top [level|worth|bank] - View leaderboards", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is profile switch <uuid> - Switch active profile", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is chat - Toggle island team chat", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is level - View island level and block valuation score", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text(
                        "/is level recalculate - Recalculate all island blocks and worth", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is worth - View island economic worth and valuation", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is reset - Reset and recycle your island", NamedTextColor.YELLOW));
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
            if (sessionCoordinator != null) {
                @SuppressWarnings("deprecation")
                OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
                if (offline.hasPlayedBefore() || offline.isOnline()) {
                    Optional<ProfileId> optProfile = sessionCoordinator.findDurableActiveProfile(offline.getUniqueId());
                    if (optProfile.isPresent()) {
                        return islandLocationService.findIslandId(optProfile.get());
                    }
                }
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
            return Optional.empty();
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
                    if (antiAbuseService != null) {
                        antiAbuseService.quarantineNewIsland(success.island().id(), Instant.now());
                    }

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

    private void teleportToIslandLocation(Player player, IslandLocation loc, String successMsg) {
        World world = Bukkit.getWorld(loc.worldName());
        if (world != null) {
            Location destination =
                    new Location(world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
            var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                if (Boolean.TRUE.equals(teleported)) {
                    player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                }
            });
            send(player, Component.text(successMsg, NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
        }
    }

    private int executeVisit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can visit islands.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(target);
            if (optIsland.isEmpty()) {
                send(player, Component.text("Could not find island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIsland.get();
            if (networkRouter == null) {
                Optional<IslandLocation> optLoc = islandLocationService.findLocation(islandId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (optLoc.isEmpty()) {
                        send(player, Component.text("Target island has no valid location.", NamedTextColor.RED));
                        return;
                    }
                    teleportToIslandLocation(player, optLoc.get(), "Teleported to island " + target + "!");
                });
                return;
            }

            var unused = networkRouter.routeVisit(playerUuid, islandId).thenAccept(outcome -> {
                schedulerPort.onEntity(playerUuid, () -> {
                    switch (outcome) {
                        case RouteOutcome.Local local -> {
                            Optional<IslandLocation> optLoc = islandLocationService.findLocation(local.islandId());
                            if (optLoc.isEmpty()) {
                                send(
                                        player,
                                        Component.text("Target island has no valid location.", NamedTextColor.RED));
                                return;
                            }
                            teleportToIslandLocation(player, optLoc.get(), "Teleported to island " + target + "!");
                        }
                        case RouteOutcome.CrossServer cross -> {
                            send(
                                    player,
                                    Component.text(
                                            "Connecting to "
                                                    + cross.targetNode().value() + "...",
                                            NamedTextColor.YELLOW));
                        }
                        case RouteOutcome.Unavailable unavail -> {
                            send(
                                    player,
                                    Component.text("Cannot visit island: " + unavail.reasonCode(), NamedTextColor.RED));
                        }
                    }
                });
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

    private Optional<IslandId> findIslandId(Player player) {
        return activeProfile(player).flatMap(islandLocationService::findIslandId);
    }

    private int executeGetRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can view or rename islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (nameService == null) {
            send(player, Component.text("Island naming service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You must have an island to view its name.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandName> current = nameService.getIslandName(optIslandId.get());
        if (current.isPresent()) {
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize(
                            "<green>Current island name: <gold>" + current.get().value()
                                    + "</gold>. Use <gold>/is rename <new-name></gold> to change it.</green>"));
        } else {
            player.sendMessage(
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<yellow>Your island has no custom name. Use <gold>/is rename <name></gold> to set one.</yellow>"));
        }
        return Cmd.OK;
    }

    private int executeRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can rename islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (nameService == null) {
            send(player, Component.text("Island naming service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You must have an island to rename it.", NamedTextColor.RED));
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
        String rawName = StringArgumentType.getString(ctx, "name");

        try {
            IslandName newName = nameService.renameIsland(optIslandId.get(), profileId, rawName);
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize(
                            "<green>Island successfully renamed to: <gold>" + newName.value() + "</gold>!</green>"));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + e.getMessage() + "</red>"));
        }
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

    private int executeBankStatus(CommandContext<CommandSourceStack> ctx) {
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
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, Component.text("You do not belong to an island.", NamedTextColor.RED));
                    return;
                }
                IslandId islandId = optIslandId.get();
                if (bankruptcyService == null) {
                    send(
                            player,
                            Component.text(
                                    "Island upkeep and bankruptcy subsystem is not active.", NamedTextColor.GRAY));
                    return;
                }

                Instant now = Instant.now();
                IslandBankruptcyRecord record = bankruptcyService.getBankruptcyRecord(islandId, now);
                NamedTextColor statusColor =
                        switch (record.status()) {
                            case SOLVENT -> NamedTextColor.GREEN;
                            case GRACE -> NamedTextColor.YELLOW;
                            case LOCKED -> NamedTextColor.RED;
                        };

                send(player, Component.text("--- Island Bank & Upkeep Status ---", NamedTextColor.GOLD));
                send(
                        player,
                        Component.text("Bankruptcy Status: ", NamedTextColor.GRAY)
                                .append(Component.text(record.status().name(), statusColor)));
                send(
                        player,
                        Component.text(
                                "Outstanding Debt: $" + String.format("%.2f", record.debtMinorUnits() / 100.0),
                                NamedTextColor.GRAY));

                if (record.status() == BankruptcyStatus.GRACE && record.graceUntil() != null) {
                    java.time.Duration remaining = java.time.Duration.between(now, record.graceUntil());
                    long hours = Math.max(0, remaining.toHours());
                    long minutes = Math.max(0, remaining.toMinutesPart());
                    send(
                            player,
                            Component.text(
                                    "Grace Remaining: " + hours + "h " + minutes + "m (until lockout)",
                                    NamedTextColor.YELLOW));
                } else if (record.status() == BankruptcyStatus.LOCKED) {
                    send(
                            player,
                            Component.text(
                                    "Island is LOCKED! Spawners, crops, and visitor entries are suppressed.",
                                    NamedTextColor.RED));
                    send(player, Component.text("Use /is bank paydebt or deposit to remediate.", NamedTextColor.AQUA));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankPayDebt(CommandContext<CommandSourceStack> ctx) {
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
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, Component.text("You do not belong to an island.", NamedTextColor.RED));
                    return;
                }
                if (bankruptcyService == null) {
                    send(
                            player,
                            Component.text(
                                    "Island upkeep and bankruptcy subsystem is not active.", NamedTextColor.GRAY));
                    return;
                }

                IslandId islandId = optIslandId.get();
                Instant now = Instant.now();
                BankruptcyRemediationResult result = bankruptcyService.settleArrears(islandId, now, serverNodeId);

                if (result instanceof BankruptcyRemediationResult.Settled settled) {
                    send(
                            player,
                            Component.text(
                                    "Successfully settled $"
                                            + String.format("%.2f", settled.amountPaid() / 100.0)
                                            + " in arrears! New bank balance: $"
                                            + String.format("%.2f", settled.remainingBalance() / 100.0)
                                            + ". Island is now SOLVENT.",
                                    NamedTextColor.GREEN));
                } else if (result instanceof BankruptcyRemediationResult.InsufficientFunds ins) {
                    send(
                            player,
                            Component.text(
                                    "Insufficient bank funds to settle arrears! Debt: $"
                                            + String.format("%.2f", ins.debtAmount() / 100.0)
                                            + ", Available bank balance: $"
                                            + String.format("%.2f", ins.currentBalance() / 100.0)
                                            + ". Deposit more funds to clear debt.",
                                    NamedTextColor.RED));
                } else if (result instanceof BankruptcyRemediationResult.NotInArrears) {
                    send(
                            player,
                            Component.text(
                                    "Your island has no outstanding arrears and is fully SOLVENT.",
                                    NamedTextColor.GREEN));
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
                if (bankruptcyService != null && bankruptcyService.policy().autoRemediateOnDeposit()) {
                    islandLocationService.findIslandId(profileId).ifPresent(islandId -> {
                        BankruptcyRemediationResult rem =
                                bankruptcyService.settleArrears(islandId, Instant.now(), serverNodeId);
                        if (rem instanceof BankruptcyRemediationResult.Settled settled) {
                            send(
                                    player,
                                    Component.text(
                                            "Outstanding arrears of $"
                                                    + String.format("%.2f", settled.amountPaid() / 100.0)
                                                    + " were automatically settled from deposit! Island is now SOLVENT.",
                                            NamedTextColor.GOLD));
                        }
                    });
                }
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
            send(
                    player,
                    Component.text(
                            "Perimeter particle projection enabled. Outlines will project around your island boundary.",
                            NamedTextColor.AQUA));
        } else {
            send(player, Component.text("Perimeter particle projection disabled.", NamedTextColor.YELLOW));
        }
        return Cmd.OK;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can reset islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (recycleService == null) {
            send(player, Component.text("Island recycle service is disabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island to reset.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        if (antiAbuseService != null) {
            boolean bypass = player.hasPermission("skyblock.antiabuse.bypass") || player.isOp();
            ResetCheckResult check = antiAbuseService.checkResetAllowed(new PlayerUuid(player.getUniqueId()), bypass);
            if (check instanceof ResetCheckResult.CooldownActive cd) {
                send(
                        player,
                        Component.text(
                                "Island reset is on cooldown. Remaining: " + formatDuration(cd.remaining()),
                                NamedTextColor.RED));
                return Cmd.OK;
            } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
                send(
                        player,
                        Component.text(
                                "You have reached the daily limit of " + dl.maxDailyResets()
                                        + " island resets. Available in: " + formatDuration(dl.remaining()),
                                NamedTextColor.RED));
                return Cmd.OK;
            }
        }

        ResetChallenge challenge = recycleService.generateResetChallenge(profileId, islandId);

        send(
                player,
                Component.text(
                        "WARNING: ISLAND RESET CANNOT BE UNDONE!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        send(
                player,
                Component.text(
                        "All island blocks, chests, items, and bank balance will be permanently wiped.",
                        NamedTextColor.GRAY));
        send(
                player,
                Component.text("To confirm in chat, type: ", NamedTextColor.YELLOW)
                        .append(Component.text(
                                "/is reset confirm " + challenge.code(), NamedTextColor.GOLD, TextDecoration.BOLD)));

        if (resetMenu != null) {
            resetMenu.open(player, challenge.code());
        }
        return Cmd.OK;
    }

    private int executeResetConfirm(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can confirm island resets.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (recycleService == null) {
            send(player, Component.text("Island recycle service is disabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String code = StringArgumentType.getString(ctx, "code");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island to reset.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        if (antiAbuseService != null) {
            boolean bypass = player.hasPermission("skyblock.antiabuse.bypass") || player.isOp();
            ResetCheckResult check = antiAbuseService.checkResetAllowed(new PlayerUuid(player.getUniqueId()), bypass);
            if (check instanceof ResetCheckResult.CooldownActive cd) {
                send(
                        player,
                        Component.text(
                                "Island reset is on cooldown. Remaining: " + formatDuration(cd.remaining()),
                                NamedTextColor.RED));
                return Cmd.OK;
            } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
                send(
                        player,
                        Component.text(
                                "You have reached the daily limit of " + dl.maxDailyResets()
                                        + " island resets. Available in: " + formatDuration(dl.remaining()),
                                NamedTextColor.RED));
                return Cmd.OK;
            }
        }

        schedulerPort.async(() -> {
            RecycleResult result = recycleService.executeReset(profileId, islandId, code, false);
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                switch (result) {
                    case RecycleResult.Success s -> {
                        if (antiAbuseService != null) {
                            antiAbuseService.recordReset(new PlayerUuid(player.getUniqueId()), Instant.now());
                            if (antiAbuseService.purgeInventoryOnReset()) {
                                player.getInventory().clear();
                                player.getInventory().setArmorContents(null);
                                player.getInventory().setItemInOffHand(null);
                                player.getEnderChest().clear();
                                player.setExp(0.0f);
                                player.setLevel(0);
                                player.setTotalExperience(0);
                            }
                        }
                        player.teleport(player.getWorld().getSpawnLocation());
                        send(
                                player,
                                Component.text(
                                        "Your island has been reset and recycled successfully!",
                                        NamedTextColor.GREEN,
                                        TextDecoration.BOLD));
                        send(player, Component.text("Create a new island with /is create.", NamedTextColor.GRAY));
                    }
                    case RecycleResult.NotOwner no ->
                        send(
                                player,
                                Component.text("Only the island owner can reset this island!", NamedTextColor.RED));
                    case RecycleResult.InvalidChallenge ic ->
                        send(player, Component.text("Reset confirmation failed: " + ic.reason(), NamedTextColor.RED));
                    case RecycleResult.IslandNotFound nf ->
                        send(player, Component.text("Island not found.", NamedTextColor.RED));
                    case RecycleResult.Failure f ->
                        send(player, Component.text("Reset failed: " + f.reason(), NamedTextColor.RED));
                }
            });
        });
        return Cmd.OK;
    }

    private int executeAdminDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (recycleService == null) {
            send(src.getSender(), Component.text("Island recycle service is disabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(target);
            if (optIsland.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not find island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIsland.get();
            send(
                    src.getSender(),
                    Component.text(
                            "Initiating administrative deletion of island " + islandId.value() + "...",
                            NamedTextColor.YELLOW));
            RecycleResult result = recycleService.executeReset(new ProfileId(UUID.randomUUID()), islandId, null, true);
            schedulerPort.onGlobal(() -> {
                if (result instanceof RecycleResult.Success) {
                    send(
                            src.getSender(),
                            Component.text(
                                    "Island " + islandId.value() + " was deleted and recycled successfully.",
                                    NamedTextColor.GREEN));
                } else {
                    send(
                            src.getSender(),
                            Component.text(
                                    "Administrative deletion failed for island " + islandId.value(),
                                    NamedTextColor.RED));
                }
            });
        });
        return Cmd.OK;
    }

    private int executeLevel(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can check island level.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (worthService == null) {
            send(player, Component.text("Island worth and level engine is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        schedulerPort.async(() -> {
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
            IslandScoreBreakdown score = worthService.calculateScore(islandId, 0, bankBalance);
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                send(
                        player,
                        Component.text("=== Island Level & Valuation ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                send(
                        player,
                        Component.text("Calculated Level: ", NamedTextColor.YELLOW)
                                .append(Component.text(
                                        String.format("%,d", score.calculatedLevel()),
                                        NamedTextColor.GREEN,
                                        TextDecoration.BOLD)));
                send(
                        player,
                        Component.text("Total Score: ", NamedTextColor.YELLOW)
                                .append(Component.text(String.format("%,d", score.totalScore()), NamedTextColor.AQUA)));
                send(
                        player,
                        Component.text(" • Block Score: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.format("%,d", score.blockScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text(" • Spawner Score: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.format("%,d", score.spawnerScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text(" • Bank Score: ", NamedTextColor.GRAY)
                                .append(Component.text(String.format("%,d", score.bankScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text("Economic Worth: ", NamedTextColor.YELLOW)
                                .append(Component.text(
                                        "$" + String.format("%,.2f", score.dampedEconomicWorthMinorUnits() / 100.0),
                                        NamedTextColor.GOLD)));
                send(
                        player,
                        Component.text(
                                "Use /is level recalculate to rescan all blocks on your island.",
                                NamedTextColor.DARK_GRAY));
            });
        });
        return Cmd.OK;
    }

    private int executeWorth(CommandContext<CommandSourceStack> ctx) {
        return executeLevel(ctx);
    }

    private int executeLevelRecalculate(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can recalculate island level.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (worthService == null) {
            send(player, Component.text("Island worth and level engine is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        send(
                player,
                Component.text(
                        "Recalculating island blocks and valuation across region chunks...", NamedTextColor.YELLOW));

        schedulerPort.async(() -> {
            Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> optLoc =
                    islandLocationService.findLocation(islandId);
            if (optLoc.isEmpty()) {
                schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()),
                        () -> send(player, Component.text("Could not find island details.", NamedTextColor.RED)));
                return;
            }
            com.uxplima.uxmskyblock.core.domain.island.IslandLocation loc = optLoc.get();
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);

            worthService.triggerAsyncRecalculation(islandId, loc.worldName(), loc.bounds(), 0, bankBalance, score -> {
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    send(
                            player,
                            Component.text(
                                    "Island recalculation complete!", NamedTextColor.GREEN, TextDecoration.BOLD));
                    send(
                            player,
                            Component.text("New Level: ", NamedTextColor.YELLOW)
                                    .append(Component.text(
                                            String.format("%,d", score.calculatedLevel()),
                                            NamedTextColor.GREEN,
                                            TextDecoration.BOLD))
                                    .append(Component.text(
                                            " (Total Score: " + String.format("%,d", score.totalScore()) + ")",
                                            NamedTextColor.GRAY)));
                    send(
                            player,
                            Component.text("Economic Worth: ", NamedTextColor.YELLOW)
                                    .append(Component.text(
                                            "$" + String.format("%,.2f", score.dampedEconomicWorthMinorUnits() / 100.0),
                                            NamedTextColor.GOLD)));
                });
            });
        });
        return Cmd.OK;
    }

    private int executeNether(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can travel to the Nether.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (dimensionListener == null) {
            send(player, Component.text("Multi-dimension travel is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.NETHER);
        return Cmd.OK;
    }

    private int executeEnd(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can travel to The End.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (dimensionListener == null) {
            send(player, Component.text("Multi-dimension travel is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        dimensionListener.executeDimensionTeleport(player, IslandDimensionType.THE_END);
        return Cmd.OK;
    }

    private int executeLimits(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can view island limits.", NamedTextColor.RED));
            return Cmd.OK;
        }

        if (limitService == null) {
            send(player, Component.text("Island limits subsystem is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You must have an active profile to view island limits.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                send(player, Component.text("You do not belong to an active island.", NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIslandId.get();
            java.util.Map<LimitType, Integer> counts = limitService.getCounts(islandId);
            java.util.Map<LimitType, Integer> limits = limitService.getLimits(islandId);

            send(
                    player,
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<gradient:#00e5ff:#0077ff><bold>--- Island Hardware & Anti-Lag Limits ---</bold></gradient>"));
            send(
                    player,
                    MiniMessage.miniMessage().deserialize("<yellow><bold>Tile Entities & Redstone:</bold></yellow>"));
            for (LimitType type : LimitType.values()) {
                if (type.category() == LimitCategory.TILE_ENTITY && limits.containsKey(type)) {
                    int c = counts.getOrDefault(type, 0);
                    int m = limits.get(type);
                    String color = c >= m ? "<red>" : (c >= m * 0.8 ? "<gold>" : "<aqua>");
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(" <gray>•</gray> <white>" + type.name() + "</white>: " + color + c
                                            + "</color><gray> / </gray><green>" + m + "</green>"));
                }
            }

            send(
                    player,
                    MiniMessage.miniMessage().deserialize("<yellow><bold>Living Entities & Vehicles:</bold></yellow>"));
            for (LimitType type : LimitType.values()) {
                if (type.category() == LimitCategory.ENTITY && limits.containsKey(type)) {
                    int c = counts.getOrDefault(type, 0);
                    int m = limits.get(type);
                    String color = c >= m ? "<red>" : (c >= m * 0.8 ? "<gold>" : "<aqua>");
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(" <gray>•</gray> <white>" + type.name() + "</white>: " + color + c
                                            + "</color><gray> / </gray><green>" + m + "</green>"));
                }
            }
        });

        return Cmd.OK;
    }

    private int executeQuarantine(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can check quarantine status.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (antiAbuseService == null) {
            send(player, Component.text("Starter quarantine protection is disabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        schedulerPort.async(() -> {
            Optional<Duration> optRemaining = antiAbuseService.getQuarantineRemaining(islandId, Instant.now());
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (optRemaining.isPresent()) {
                    Duration remaining = optRemaining.get();
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(
                                            "<gold>Island Starter Quarantine:</gold> <yellow><bold>ACTIVE</bold></yellow> "
                                                    + "(<white>" + formatDuration(remaining)
                                                    + "</white> remaining)<newline>"
                                                    + "<gray>Visitor access and dropping starter items are prohibited during quarantine.</gray>"));
                } else {
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(
                                            "<gold>Island Starter Quarantine:</gold> <green><bold>INACTIVE</bold></green> "
                                                    + "<gray>(Full visitor access and trade enabled)</gray>"));
                }
            });
        });
        return Cmd.OK;
    }

    private int executeBooster(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can access the booster menu.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (boosterMenu == null) {
            send(player, Component.text("Island boosters are disabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        boosterMenu.open(player);
        return Cmd.OK;
    }

    private int executeAdminApplyBooster(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (boosterService == null) {
            send(sender, Component.text("Island boosters are disabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (!sender.hasPermission("uxmskyblock.admin.booster")) {
            send(sender, Component.text("You do not have permission to apply boosters.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String catStr = StringArgumentType.getString(ctx, "category");
        Optional<BoosterCategory> optCategory = BoosterCategory.parse(catStr);
        if (optCategory.isEmpty()) {
            send(
                    sender,
                    Component.text(
                            "Invalid booster category: " + catStr
                                    + ". Available: SPAWNER_RATE, CROP_GROWTH, ORE_GENERATOR, MOB_EXP, ISLAND_WORTH, MISSION_REWARDS",
                            NamedTextColor.RED));
            return Cmd.OK;
        }

        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        String durStr = StringArgumentType.getString(ctx, "duration");
        Duration duration = parseDurationString(durStr);
        if (duration.isZero() || duration.isNegative()) {
            send(sender, Component.text("Invalid duration: " + durStr, NamedTextColor.RED));
            return Cmd.OK;
        }

        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Console must specify an island to apply boosters.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<IslandId> optIsland = islandLocationService.findIslandId(optProfile.get());
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        Instant now = Instant.now();
        BoosterApplyResult result = boosterService.applyBooster(islandId, optCategory.get(), multiplier, duration, now);

        send(
                player,
                MiniMessage.miniMessage()
                        .deserialize(
                                "<green>Successfully applied <yellow>" + multiplier + "x</yellow> booster to <gold>"
                                        + optCategory.get().displayName() + "</gold> for <white>"
                                        + formatDuration(duration) + "</white>! Result: "
                                        + result.getClass().getSimpleName() + "</green>"));
        return Cmd.OK;
    }

    private static Duration parseDurationString(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    private static String formatDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long seconds = duration.toSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        }
        return String.format("%ds", secs);
    }
}
