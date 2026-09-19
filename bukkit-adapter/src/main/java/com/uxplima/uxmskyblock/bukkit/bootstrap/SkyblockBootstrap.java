package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandChatDeliveryAdapter;
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitPlayerActivityProvider;
import com.uxplima.uxmskyblock.bukkit.integration.discord.JavaHttpClientDiscordAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.listener.PlayerSessionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AllianceFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.AntiAbuseFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankUpkeepFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BiomesModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoosterFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BoundaryFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ChatFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.CoreModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DimensionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.DiscordFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.FreezeFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.InactivityFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.LimitFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.MissionFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.PresetsModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RecycleFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.RewardInboxFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SeasonFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.ShopFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.SocialFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.TemporaryAccessFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.VaultFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WarpFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.WorthFeatureModule;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.world.AsyncStructureSuppressionListener;
import com.uxplima.uxmskyblock.bukkit.worth.FoliaIslandChunkScanner;
import com.uxplima.uxmskyblock.bukkit.worth.IslandWorthListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.reward.RewardClaimCoordinator;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.application.webmap.IslandWebMapService;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
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
    private final CurrentNodeProcessIdentity nodeProcessIdentity;
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
    private final TemporaryAccessConfiguration temporaryAccessConfig;
    private final TemporaryAccessService temporaryAccessService;
    private final RewardInboxConfiguration rewardConfig;
    private final RewardInboxService rewardInboxService;
    private final WarpConfiguration warpConfig;
    private final SafeTeleportEngine safeTeleportEngine;
    private final IslandWarpService warpService;
    private final VaultConfiguration vaultConfig;
    private final IslandVaultService vaultService;
    private final ChatConfiguration chatConfig;
    private final IslandChatService chatService;
    private final IslandChatListener chatListener;
    private final InactivityConfiguration inactivityConfig;
    private final IslandInactivityService inactivityService;
    private final BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;
    private final IslandAdminFreezeService freezeService;
    private final MissionConfiguration missionConfig;
    private final IslandMissionService missionService;
    private final IslandMissionsMenu missionsMenu;
    private final IslandMissionListener missionListener;
    private final WorldBorderPacketAdapter worldBorderAdapter;
    private final IslandBoundaryService boundaryService;
    private final IslandBoundaryListener boundaryListener;
    private final FoliaIslandVoidingAdapter voidingAdapter;
    private final NbtIslandBackupAdapter islandBackupAdapter;
    private final IslandRecycleService recycleService;
    private final IslandResetConfirmationMenu resetConfirmationMenu;
    private final ModuleRegistry moduleRegistry;
    private final BukkitModuleContext moduleContext;
    private final LevelConfiguration levelConfig;
    private final FoliaIslandChunkScanner chunkScanner;
    private final IslandWorthService worthService;
    private final IslandWorthListener worthListener;
    private final DimensionConfiguration dimensionConfig;
    private final IslandDimensionService dimensionService;
    private final IslandDimensionListener dimensionListener;
    private final LimitConfiguration limitConfig;
    private final IslandLimitService limitService;
    private final IslandLimitListener limitListener;
    private final AntiAbuseConfiguration antiAbuseConfig;
    private final IslandAntiAbuseService antiAbuseService;
    private final IslandAntiAbuseListener antiAbuseListener;
    private final BoosterConfiguration boosterConfig;
    private final IslandBoosterService boosterService;
    private final IslandBoosterListener boosterListener;
    private final IslandBoosterMenu boosterMenu;
    private final BankConfiguration bankConfig;
    private final IslandBankruptcyService bankruptcyService;
    private final IslandBankruptcyListener bankruptcyListener;
    private final BankUpkeepFeatureModule bankUpkeepFeatureModule;
    private final SettingsConfiguration settingsConfig;
    private final ProtectionConfiguration protectionConfig;
    private final PerformanceConfiguration performanceConfig;
    private final InteractablesConfiguration interactablesConfig;
    private final WorldConfiguration worldConfig;
    private final IslandNameService islandNameService;
    private final AdaptiveBackpressureController backpressureController;
    private final KineticWardService kineticWardService;
    private final IslandWebMapService islandWebMapService;
    private final ObsidianRecoveryListener obsidianRecoveryListener;
    private final VoidProtectionListener voidProtectionListener;
    private final CategoricalInteractablesListener categoricalInteractablesListener;
    private final KineticWardListener kineticWardListener;
    private final IslandRedstoneOptimizationListener redstoneOptimizationListener;
    private final AsyncStructureSuppressionListener structureSuppressionListener;

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
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                LevelConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                DimensionConfiguration.defaultConfiguration(),
                LimitConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                LimitConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                limitConfig,
                AntiAbuseConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                limitConfig,
                antiAbuseConfig,
                BoosterConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig,
            BoosterConfiguration boosterConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                limitConfig,
                antiAbuseConfig,
                boosterConfig,
                BankConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig,
            BoosterConfiguration boosterConfig,
            BankConfiguration bankConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                limitConfig,
                antiAbuseConfig,
                boosterConfig,
                bankConfig,
                SettingsConfiguration.defaultConfiguration(),
                ProtectionConfiguration.defaultConfiguration(),
                PerformanceConfiguration.defaultConfiguration(),
                InteractablesConfiguration.defaultConfiguration(),
                WorldConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig,
            MissionConfiguration missionConfig,
            LevelConfiguration levelConfig,
            DimensionConfiguration dimensionConfig,
            LimitConfiguration limitConfig,
            AntiAbuseConfiguration antiAbuseConfig,
            BoosterConfiguration boosterConfig,
            BankConfiguration bankConfig,
            SettingsConfiguration settingsConfig,
            ProtectionConfiguration protectionConfig,
            PerformanceConfiguration performanceConfig,
            InteractablesConfiguration interactablesConfig,
            WorldConfiguration worldConfig) {
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
        this.temporaryAccessConfig =
                Objects.requireNonNull(temporaryAccessConfig, "temporaryAccessConfig must not be null");
        this.rewardConfig = Objects.requireNonNull(rewardConfig, "rewardConfig must not be null");
        this.warpConfig = Objects.requireNonNull(warpConfig, "warpConfig must not be null");
        this.vaultConfig = Objects.requireNonNull(vaultConfig, "vaultConfig must not be null");
        this.chatConfig = Objects.requireNonNull(chatConfig, "chatConfig must not be null");
        this.inactivityConfig = Objects.requireNonNull(inactivityConfig, "inactivityConfig must not be null");
        this.missionConfig = Objects.requireNonNull(missionConfig, "missionConfig must not be null");
        this.levelConfig = Objects.requireNonNull(levelConfig, "levelConfig must not be null");
        this.dimensionConfig = Objects.requireNonNull(dimensionConfig, "dimensionConfig must not be null");
        this.limitConfig = Objects.requireNonNull(limitConfig, "limitConfig must not be null");
        this.antiAbuseConfig = Objects.requireNonNull(antiAbuseConfig, "antiAbuseConfig must not be null");
        this.boosterConfig = Objects.requireNonNull(boosterConfig, "boosterConfig must not be null");
        this.bankConfig = Objects.requireNonNull(bankConfig, "bankConfig must not be null");
        this.settingsConfig = Objects.requireNonNull(settingsConfig, "settingsConfig must not be null");
        this.protectionConfig = Objects.requireNonNull(protectionConfig, "protectionConfig must not be null");
        this.performanceConfig = Objects.requireNonNull(performanceConfig, "performanceConfig must not be null");
        this.interactablesConfig = Objects.requireNonNull(interactablesConfig, "interactablesConfig must not be null");
        this.worldConfig = Objects.requireNonNull(worldConfig, "worldConfig must not be null");

        LocalIslandChatTransportAdapter chatTransport = new LocalIslandChatTransportAdapter();
        BukkitIslandChatDeliveryAdapter chatDelivery = new BukkitIslandChatDeliveryAdapter(chatConfig);
        AtomicReference<PlayerSessionCoordinator> sessionCoordinatorRef = new AtomicReference<>();
        Function<UUID, Optional<ProfileId>> activeProfileProvider =
                uuid -> {
                    PlayerSessionCoordinator coord = sessionCoordinatorRef.get();
                    return coord != null ? coord.activeProfile(uuid) : Optional.empty();
                };
        BukkitIslandOnlineMemberProvider chatMemberProvider =
                new BukkitIslandOnlineMemberProvider(persistenceBootstrap.islandStoragePort(), activeProfileProvider);
        this.chatService = new IslandChatService(
                persistenceBootstrap.islandStoragePort(),
                chatTransport,
                chatDelivery,
                chatMemberProvider,
                chatConfig.rateLimitMessagesPerSecond());
        this.chatListener = new IslandChatListener(chatService, activeProfileProvider);

        BukkitPlayerActivityProvider activityProvider = new BukkitPlayerActivityProvider();
        this.inactivityService = new IslandInactivityService(
                persistenceBootstrap.islandStoragePort(),
                activityProvider,
                inactivityConfig.toPolicy(),
                null,
                null,
                persistenceBootstrap.outboxPort());

        this.dynamicPricingEngine = new DynamicPricingEngine(shopConfig.dampingFactor());
        this.temporaryAccessService = new TemporaryAccessService(persistenceBootstrap.temporaryAccessStoragePort());

        this.scheduler = new FoliaSchedulerAdapter(plugin);
        this.visitorEvictionAdapter =
                new BukkitIslandVisitorEvictionAdapter(plugin, persistenceBootstrap.islandStoragePort(), scheduler);
        this.freezeService = new IslandAdminFreezeService(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandAdminFreezePort(),
                visitorEvictionAdapter,
                persistenceBootstrap.outboxPort());
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

        this.safeTeleportEngine = new SafeTeleportEngine(warpConfig.searchRadius());
        this.warpService = new IslandWarpService(
                persistenceBootstrap.islandWarpStoragePort(),
                safeTeleportEngine,
                null,
                warpConfig.baseWarpLimit(),
                (targetIslandId, visitorProfileId) -> {
                    if (!allianceConfig.privilegedVisitAccess()) {
                        return false;
                    }
                    return persistenceBootstrap
                            .islandStoragePort()
                            .findIslandIdByProfileId(visitorProfileId)
                            .map(visitorIslandId -> allianceService.canPrivilegedVisit(visitorIslandId, targetIslandId))
                            .orElse(false);
                });

        this.vaultService = new IslandVaultService(
                persistenceBootstrap.islandVaultStoragePort(),
                null,
                vaultConfig.basePages(),
                vaultConfig.maxPages(),
                vaultConfig.leaseDuration());

        this.protectionListener = new IslandProtectionListener(
                persistenceBootstrap.islandStoragePort(),
                accessService,
                allianceService,
                temporaryAccessService,
                freezeService);

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
        sessionCoordinatorRef.set(this.sessionCoordinator);

        this.nodeProcessIdentity =
                CurrentNodeProcessIdentity.create(nodeConfiguration.nodeId().value());
        this.protectionListener.setNodeIdentitySupplier(() -> this.nodeProcessIdentity);
        this.protectionListener.setSessionRecordProvider(uuid -> {
            PlayerSessionCoordinator.ActiveSession session = sessionCoordinator.getActiveSession(uuid.value());
            if (session == null) {
                return Optional.empty();
            }
            return Optional.of(new PlayerSessionRecord(
                    uuid,
                    session.activeProfileId(),
                    nodeConfiguration.nodeId(),
                    session.sessionEpoch(),
                    session.state(),
                    Instant.now().plusSeconds(60),
                    session.lastDurableVersion(),
                    null,
                    null,
                    null));
        });

        this.biomeAdapter = new BukkitBiomeAdapter(persistenceBootstrap.islandStoragePort(), scheduler, worldName);

        this.apiBridge = new BukkitSkyblockApiBridge(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandLeaderboardPort(),
                bankService,
                createIslandUseCase,
                serverNodeId,
                scheduler,
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

        this.missionService = new IslandMissionService(persistenceBootstrap.islandMissionStoragePort());
        this.missionService.registerMissions(missionConfig.missions());
        this.missionsMenu = new IslandMissionsMenu(
                missionService, persistenceBootstrap.islandStoragePort(), sessionCoordinator, scheduler);
        this.missionListener = new IslandMissionListener(
                missionService, persistenceBootstrap.islandStoragePort(), sessionCoordinator, scheduler);

        this.worldBorderAdapter = new WorldBorderPacketAdapter(scheduler);
        this.boundaryService = new IslandBoundaryService(worldBorderAdapter);
        this.boundaryListener = new IslandBoundaryListener(boundaryService, protectionListener, scheduler);
        this.boundaryListener.setStopBorderCrossing(this.settingsConfig.stopBorderCrossing());

        this.voidingAdapter = new FoliaIslandVoidingAdapter(scheduler);
        this.islandBackupAdapter = new NbtIslandBackupAdapter(plugin.getDataFolder());
        this.recycleService = new IslandRecycleService(
                persistenceBootstrap.islandStoragePort(),
                persistenceBootstrap.worldGridAllocationPort(),
                persistenceBootstrap.spiralSlotPoolPort(),
                voidingAdapter,
                islandBackupAdapter,
                persistenceBootstrap.outboxPort());
        this.resetConfirmationMenu = new IslandResetConfirmationMenu(
                recycleService, persistenceBootstrap.islandStoragePort(), sessionCoordinator, scheduler);

        this.chunkScanner = new FoliaIslandChunkScanner(
                this.scheduler,
                this.levelConfig.blockWeights().keySet(),
                this.levelConfig.spawnerWeights().keySet());
        MaterialValuationIndex valuationIndex = new MaterialValuationIndex();
        this.levelConfig.blockWeights().forEach(valuationIndex::setWeight);
        this.levelConfig.basePricesMinorUnits().forEach(valuationIndex::setPrice);
        this.worthService = new IslandWorthService(
                valuationIndex,
                this.levelConfig.spawnerWeights(),
                this.levelConfig.defaultSpawnerWeight(),
                this.levelConfig.questWeight(),
                this.levelConfig.pointsPerLevel(),
                this.levelConfig.bankMinorUnitsPerPoint(),
                this.levelConfig.dampingFactor(),
                persistenceBootstrap.islandLeaderboardPort(),
                this.chunkScanner);
        this.worthListener = new IslandWorthListener(this.worthService, this.protectionListener);

        this.dimensionService = new IslandDimensionService(
                persistenceBootstrap.islandUpgradeStoragePort(), this.dimensionConfig.mappings());
        this.dimensionListener = new IslandDimensionListener(
                this.dimensionService,
                locationService,
                schematicEngine,
                this.scheduler,
                p -> sessionCoordinator.activeProfile(p.getUniqueId()),
                nodeConfiguration.worldName());

        this.limitService =
                new IslandLimitService(persistenceBootstrap.islandUpgradeStoragePort(), this.limitConfig.quotas());
        this.limitListener = new IslandLimitListener(
                this.limitService, this.protectionListener, this.limitConfig.bypassPermission());

        this.antiAbuseService = new IslandAntiAbuseService(
                persistenceBootstrap.antiAbuseStoragePort(),
                this.antiAbuseConfig.purgeInventoryOnReset(),
                this.antiAbuseConfig.quarantineDuration(),
                this.antiAbuseConfig.resetCooldown(),
                this.antiAbuseConfig.maxResetsPerDay(),
                this.antiAbuseConfig.resetWindowDuration(),
                this.antiAbuseConfig.coopJoinCooldown(),
                java.time.Clock.systemUTC());
        this.antiAbuseListener = new IslandAntiAbuseListener(
                persistenceBootstrap.islandStoragePort(), this.antiAbuseService, this.antiAbuseConfig);

        this.boosterService = new IslandBoosterService(
                persistenceBootstrap.islandBoosterStoragePort(),
                this.boosterConfig::policy,
                this.boosterConfig.pauseWhenEmpty());
        this.boosterListener = new IslandBoosterListener(
                persistenceBootstrap.islandStoragePort(),
                this.boosterService,
                this.boosterConfig,
                this.sessionCoordinator,
                this.scheduler);
        this.boosterMenu = new IslandBoosterMenu(
                persistenceBootstrap.islandStoragePort(),
                this.boosterService,
                this.boosterConfig,
                this.sessionCoordinator,
                this.scheduler);

        this.bankruptcyService = new IslandBankruptcyService(
                persistenceBootstrap.islandBankruptcyStoragePort(),
                persistenceBootstrap.islandBankPort(),
                persistenceBootstrap.islandAuthorityPort(),
                this.bankConfig::upkeepPolicy);
        this.bankruptcyListener = new IslandBankruptcyListener(
                this.bankruptcyService,
                this.protectionListener,
                persistenceBootstrap.islandStoragePort(),
                this.sessionCoordinator,
                this.scheduler);
        this.bankUpkeepFeatureModule = new BankUpkeepFeatureModule(
                this.bankruptcyService,
                this.bankConfig,
                this.scheduler,
                persistenceBootstrap.islandStoragePort(),
                worldName,
                serverNodeId);

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
                controlMenu,
                chatService,
                inactivityService,
                freezeService,
                missionsMenu,
                boundaryService,
                recycleService,
                resetConfirmationMenu,
                worthService,
                dimensionListener,
                limitService,
                antiAbuseService,
                boosterService,
                boosterMenu);
        this.commandTree.setBankruptcyService(this.bankruptcyService);
        this.islandNameService = new IslandNameService(
                persistenceBootstrap.islandNameStoragePort(),
                persistenceBootstrap.islandStoragePort(),
                this.accessService,
                persistenceBootstrap.outboxPort());
        this.commandTree.setNameService(this.islandNameService);

        this.backpressureController = new AdaptiveBackpressureController(
                () -> {
                    double[] tps = Bukkit.getTPS();
                    return (tps != null && tps.length > 0) ? tps[0] : 20.0;
                },
                this.performanceConfig.adaptiveThrottle(),
                this.performanceConfig.tpsThreshold(),
                this.performanceConfig.normalBlocksPerTick(),
                this.performanceConfig.throttledBlocksPerTick(),
                this.performanceConfig.normalChunksPerSec(),
                this.performanceConfig.throttledChunksPerSec());

        this.kineticWardService = new KineticWardService(
                this.protectionConfig.kineticWardRadius(),
                this.protectionConfig.kineticWardForce(),
                this.protectionConfig.kineticWardVerticalLift());
        this.kineticWardListener = new KineticWardListener(this.protectionConfig, this.kineticWardService);

        this.islandWebMapService = new IslandWebMapService();

        this.obsidianRecoveryListener = new ObsidianRecoveryListener(this.protectionConfig);

        this.voidProtectionListener = new VoidProtectionListener(
                this.protectionConfig, this.settingsConfig, this.protectionListener::findIslandAt);

        this.categoricalInteractablesListener = new CategoricalInteractablesListener(
                this.interactablesConfig,
                this.protectionListener::findIslandAt,
                uuid -> this.sessionCoordinator.activeProfile(uuid.value()).orElse(null),
                this.temporaryAccessService);
        this.categoricalInteractablesListener.setNodeIdentitySupplier(() -> this.nodeProcessIdentity);
        this.categoricalInteractablesListener.setSessionRecordProvider(uuid -> {
            PlayerSessionCoordinator.ActiveSession session = this.sessionCoordinator.getActiveSession(uuid.value());
            if (session == null) {
                return Optional.empty();
            }
            return Optional.of(new PlayerSessionRecord(
                    uuid,
                    session.activeProfileId(),
                    this.nodeConfiguration.nodeId(),
                    session.sessionEpoch(),
                    session.state(),
                    Instant.now().plusSeconds(60),
                    session.lastDurableVersion(),
                    null,
                    null,
                    null));
        });

        this.redstoneOptimizationListener =
                new IslandRedstoneOptimizationListener(this.settingsConfig, this.protectionListener::findIslandAt);

        this.structureSuppressionListener = new AsyncStructureSuppressionListener(this.worldConfig);

        RewardDeliveryHandler itemDeliveryHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.ITEM;
            }

            @Override
            public DeliveryResult deliver(
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    com.uxplima.uxmskyblock.core.domain.identity.ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler currencyDeliveryHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.SQL_CURRENCY;
            }

            @Override
            public DeliveryResult deliver(
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    com.uxplima.uxmskyblock.core.domain.identity.ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler vaultDeliveryHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.EXTERNAL_VAULT;
            }

            @Override
            public DeliveryResult deliver(
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    com.uxplima.uxmskyblock.core.domain.identity.ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler cosmeticDeliveryHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.COSMETIC;
            }

            @Override
            public DeliveryResult deliver(
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    com.uxplima.uxmskyblock.core.domain.identity.ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler permDeliveryHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.PERMISSION;
            }

            @Override
            public DeliveryResult deliver(
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    com.uxplima.uxmskyblock.core.domain.identity.ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardClaimCoordinator rewardClaimCoordinator = new RewardClaimCoordinator(
                persistenceBootstrap.rewardStoragePort(),
                List.of(
                        itemDeliveryHandler,
                        currencyDeliveryHandler,
                        vaultDeliveryHandler,
                        cosmeticDeliveryHandler,
                        permDeliveryHandler));

        this.rewardInboxService =
                new RewardInboxService(persistenceBootstrap.rewardStoragePort(), rewardClaimCoordinator);

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
        this.moduleRegistry.register(
                new TemporaryAccessFeatureModule(temporaryAccessService, scheduler, temporaryAccessConfig));
        this.moduleRegistry.register(new RewardInboxFeatureModule(rewardInboxService, scheduler, rewardConfig));
        this.moduleRegistry.register(new WarpFeatureModule(warpService, safeTeleportEngine, warpConfig));
        this.moduleRegistry.register(new VaultFeatureModule(vaultService, vaultConfig));
        this.moduleRegistry.register(new ChatFeatureModule(chatService, chatConfig));
        this.moduleRegistry.register(new InactivityFeatureModule(
                inactivityService, scheduler, inactivityConfig, nodeConfiguration.worldName()));
        this.moduleRegistry.register(new FreezeFeatureModule(freezeService));
        this.moduleRegistry.register(new MissionFeatureModule(missionService, missionConfig));
        this.moduleRegistry.register(new BoundaryFeatureModule(boundaryService, boundaryListener, scheduler));
        this.moduleRegistry.register(new RecycleFeatureModule(recycleService));
        this.moduleRegistry.register(new WorthFeatureModule(worthService));
        this.moduleRegistry.register(new DimensionFeatureModule(dimensionService));
        this.moduleRegistry.register(new LimitFeatureModule(limitService));
        this.moduleRegistry.register(new AntiAbuseFeatureModule(antiAbuseService));
        this.moduleRegistry.register(new BoosterFeatureModule(boosterService, boosterConfig, scheduler));
        this.moduleRegistry.register(bankUpkeepFeatureModule);
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig,
            InactivityConfiguration inactivityConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                MissionConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig,
            ChatConfiguration chatConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                InactivityConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig,
            VaultConfiguration vaultConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                ChatConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig,
            WarpConfiguration warpConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                VaultConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig,
            RewardInboxConfiguration rewardConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                WarpConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig,
            TemporaryAccessConfiguration temporaryAccessConfig) {
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
                shopConfig,
                temporaryAccessConfig,
                RewardInboxConfiguration.defaultConfiguration());
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
            AllianceConfiguration allianceConfig,
            ShopConfiguration shopConfig) {
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
                shopConfig,
                TemporaryAccessConfiguration.defaultConfiguration());
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

        Path temporaryAccessFile = dataDir.resolve("temporary-access.conf");
        if (!java.nio.file.Files.exists(temporaryAccessFile)) {
            try (java.io.InputStream in = plugin.getResource("temporary-access.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, temporaryAccessFile);
                }
            } catch (Exception expected) {
                // Ignore failure if temporary-access.conf cannot be extracted
            }
        }

        TemporaryAccessConfiguration temporaryAccessConfig;
        if (java.nio.file.Files.exists(temporaryAccessFile)) {
            try {
                CommentedConfigurationNode accessRoot = HoconConfigurationLoader.builder()
                        .path(temporaryAccessFile)
                        .build()
                        .load();
                temporaryAccessConfig = TemporaryAccessConfiguration.load(accessRoot);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load temporary access configuration from: " + temporaryAccessFile, e);
            }
        } else {
            temporaryAccessConfig = TemporaryAccessConfiguration.defaultConfiguration();
        }

        Path rewardsFile = dataDir.resolve("rewards.conf");
        if (!java.nio.file.Files.exists(rewardsFile)) {
            try (java.io.InputStream in = plugin.getResource("rewards.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, rewardsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if rewards.conf cannot be extracted
            }
        }

        RewardInboxConfiguration rewardConfig;
        if (java.nio.file.Files.exists(rewardsFile)) {
            try {
                CommentedConfigurationNode rewardRoot = HoconConfigurationLoader.builder()
                        .path(rewardsFile)
                        .build()
                        .load();
                rewardConfig = RewardInboxConfiguration.load(rewardRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load rewards configuration from: " + rewardsFile, e);
            }
        } else {
            rewardConfig = RewardInboxConfiguration.defaultConfiguration();
        }

        Path warpsFile = dataDir.resolve("warps.conf");
        if (!java.nio.file.Files.exists(warpsFile)) {
            try (java.io.InputStream in = plugin.getResource("warps.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, warpsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if warps.conf cannot be extracted
            }
        }

        WarpConfiguration warpConfig;
        if (java.nio.file.Files.exists(warpsFile)) {
            try {
                CommentedConfigurationNode warpRoot = HoconConfigurationLoader.builder()
                        .path(warpsFile)
                        .build()
                        .load();
                warpConfig = WarpConfiguration.load(warpRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load warps configuration from: " + warpsFile, e);
            }
        } else {
            warpConfig = WarpConfiguration.defaultConfiguration();
        }

        Path vaultFile = dataDir.resolve("vault.conf");
        if (!java.nio.file.Files.exists(vaultFile)) {
            try (java.io.InputStream in = plugin.getResource("vault.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, vaultFile);
                }
            } catch (Exception expected) {
                // Ignore failure if vault.conf cannot be extracted
            }
        }

        VaultConfiguration vaultConfig;
        if (java.nio.file.Files.exists(vaultFile)) {
            try {
                CommentedConfigurationNode vaultRoot = HoconConfigurationLoader.builder()
                        .path(vaultFile)
                        .build()
                        .load();
                vaultConfig = VaultConfiguration.load(vaultRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load vault configuration from: " + vaultFile, e);
            }
        } else {
            vaultConfig = VaultConfiguration.defaultConfiguration();
        }

        Path chatFile = dataDir.resolve("chat.conf");
        if (!java.nio.file.Files.exists(chatFile)) {
            try (java.io.InputStream in = plugin.getResource("chat.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, chatFile);
                }
            } catch (Exception expected) {
                // Ignore failure if chat.conf cannot be extracted
            }
        }

        ChatConfiguration chatConfig;
        if (java.nio.file.Files.exists(chatFile)) {
            try {
                CommentedConfigurationNode chatRoot = HoconConfigurationLoader.builder()
                        .path(chatFile)
                        .build()
                        .load();
                chatConfig = ChatConfiguration.load(chatRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load chat configuration from: " + chatFile, e);
            }
        } else {
            chatConfig = ChatConfiguration.defaultConfiguration();
        }

        Path inactivityFile = dataDir.resolve("inactivity.conf");
        if (!java.nio.file.Files.exists(inactivityFile)) {
            try (java.io.InputStream in = plugin.getResource("inactivity.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, inactivityFile);
                }
            } catch (Exception expected) {
                // Ignore failure if inactivity.conf cannot be extracted
            }
        }

        InactivityConfiguration inactivityConfig;
        if (java.nio.file.Files.exists(inactivityFile)) {
            try {
                CommentedConfigurationNode inactivityRoot = HoconConfigurationLoader.builder()
                        .path(inactivityFile)
                        .build()
                        .load();
                inactivityConfig = InactivityConfiguration.load(inactivityRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load inactivity configuration from: " + inactivityFile, e);
            }
        } else {
            inactivityConfig = InactivityConfiguration.defaultConfiguration();
        }

        Path missionsFile = dataDir.resolve("missions.conf");
        if (!java.nio.file.Files.exists(missionsFile)) {
            try (java.io.InputStream in = plugin.getResource("missions.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, missionsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if missions.conf cannot be extracted
            }
        }

        MissionConfiguration missionConfig;
        if (java.nio.file.Files.exists(missionsFile)) {
            try {
                CommentedConfigurationNode missionRoot = HoconConfigurationLoader.builder()
                        .path(missionsFile)
                        .build()
                        .load();
                missionConfig = MissionConfiguration.load(missionRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load missions configuration from: " + missionsFile, e);
            }
        } else {
            missionConfig = MissionConfiguration.defaultConfiguration();
        }

        Path levelsFile = dataDir.resolve("levels.conf");
        if (!java.nio.file.Files.exists(levelsFile)) {
            try (java.io.InputStream in = plugin.getResource("levels.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, levelsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if levels.conf cannot be extracted
            }
        }

        LevelConfiguration levelConfig;
        if (java.nio.file.Files.exists(levelsFile)) {
            try {
                CommentedConfigurationNode levelRoot = HoconConfigurationLoader.builder()
                        .path(levelsFile)
                        .build()
                        .load();
                levelConfig = LevelConfiguration.load(levelRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load levels configuration from: " + levelsFile, e);
            }
        } else {
            levelConfig = LevelConfiguration.defaultConfiguration();
        }

        Path dimensionsFile = dataDir.resolve("dimensions.conf");
        if (!java.nio.file.Files.exists(dimensionsFile)) {
            try (java.io.InputStream in = plugin.getResource("dimensions.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, dimensionsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if dimensions.conf cannot be extracted
            }
        }

        DimensionConfiguration dimensionConfig;
        if (java.nio.file.Files.exists(dimensionsFile)) {
            try {
                CommentedConfigurationNode dimRoot = HoconConfigurationLoader.builder()
                        .path(dimensionsFile)
                        .build()
                        .load();
                dimensionConfig = DimensionConfiguration.load(dimRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load dimensions configuration from: " + dimensionsFile, e);
            }
        } else {
            dimensionConfig = DimensionConfiguration.defaultConfiguration();
        }

        Path limitsFile = dataDir.resolve("limits.conf");
        if (!java.nio.file.Files.exists(limitsFile)) {
            try (java.io.InputStream in = plugin.getResource("limits.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, limitsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if limits.conf cannot be extracted
            }
        }

        LimitConfiguration limitConfig;
        if (java.nio.file.Files.exists(limitsFile)) {
            try {
                CommentedConfigurationNode limitRoot = HoconConfigurationLoader.builder()
                        .path(limitsFile)
                        .build()
                        .load();
                limitConfig = LimitConfiguration.load(limitRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load limits configuration from: " + limitsFile, e);
            }
        } else {
            limitConfig = LimitConfiguration.defaultConfiguration();
        }

        Path antiAbuseFile = dataDir.resolve("anti_abuse.conf");
        if (!java.nio.file.Files.exists(antiAbuseFile)) {
            try (java.io.InputStream in = plugin.getResource("anti_abuse.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, antiAbuseFile);
                }
            } catch (Exception expected) {
                // Ignore failure if anti_abuse.conf cannot be extracted
            }
        }

        AntiAbuseConfiguration antiAbuseConfig;
        if (java.nio.file.Files.exists(antiAbuseFile)) {
            try {
                CommentedConfigurationNode antiAbuseRoot = HoconConfigurationLoader.builder()
                        .path(antiAbuseFile)
                        .build()
                        .load();
                antiAbuseConfig = AntiAbuseConfiguration.load(antiAbuseRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load anti abuse configuration from: " + antiAbuseFile, e);
            }
        } else {
            antiAbuseConfig = AntiAbuseConfiguration.defaultConfiguration();
        }

        Path boostersFile = dataDir.resolve("boosters.conf");
        if (!java.nio.file.Files.exists(boostersFile)) {
            try (java.io.InputStream in = plugin.getResource("boosters.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, boostersFile);
                }
            } catch (Exception expected) {
                // Ignore failure if boosters.conf cannot be extracted
            }
        }

        BoosterConfiguration boosterConfig;
        if (java.nio.file.Files.exists(boostersFile)) {
            try {
                CommentedConfigurationNode boosterRoot = HoconConfigurationLoader.builder()
                        .path(boostersFile)
                        .build()
                        .load();
                boosterConfig = BoosterConfiguration.load(boosterRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load boosters configuration from: " + boostersFile, e);
            }
        } else {
            boosterConfig = BoosterConfiguration.defaultConfiguration();
        }

        Path bankFile = dataDir.resolve("bank.conf");
        if (!java.nio.file.Files.exists(bankFile)) {
            try (java.io.InputStream in = plugin.getResource("bank.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, bankFile);
                }
            } catch (Exception expected) {
                // Ignore failure if bank.conf cannot be extracted
            }
        }

        BankConfiguration bankConfig;
        if (java.nio.file.Files.exists(bankFile)) {
            try {
                CommentedConfigurationNode bankRoot = HoconConfigurationLoader.builder()
                        .path(bankFile)
                        .build()
                        .load();
                bankConfig = BankConfiguration.load(bankRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load bank configuration from: " + bankFile, e);
            }
        } else {
            bankConfig = BankConfiguration.defaultConfiguration();
        }

        Path settingsFile = dataDir.resolve("settings.conf");
        if (!java.nio.file.Files.exists(settingsFile)) {
            try (java.io.InputStream in = plugin.getResource("settings.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, settingsFile);
                }
            } catch (Exception expected) {
                // Ignore failure if settings.conf cannot be extracted
            }
        }

        SettingsConfiguration settingsConfig;
        if (java.nio.file.Files.exists(settingsFile)) {
            try {
                CommentedConfigurationNode settingsRoot = HoconConfigurationLoader.builder()
                        .path(settingsFile)
                        .build()
                        .load();
                settingsConfig = SettingsConfiguration.load(settingsRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load settings configuration from: " + settingsFile, e);
            }
        } else {
            settingsConfig = SettingsConfiguration.defaultConfiguration();
        }

        Path protectionFile = dataDir.resolve("protection.conf");
        if (!java.nio.file.Files.exists(protectionFile)) {
            try (java.io.InputStream in = plugin.getResource("protection.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, protectionFile);
                }
            } catch (Exception expected) {
                // Ignore failure if protection.conf cannot be extracted
            }
        }

        ProtectionConfiguration protectionConfig;
        if (java.nio.file.Files.exists(protectionFile)) {
            try {
                CommentedConfigurationNode protectionRoot = HoconConfigurationLoader.builder()
                        .path(protectionFile)
                        .build()
                        .load();
                protectionConfig = ProtectionConfiguration.load(protectionRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load protection configuration from: " + protectionFile, e);
            }
        } else {
            protectionConfig = ProtectionConfiguration.defaultConfiguration();
        }

        Path performanceFile = dataDir.resolve("performance.conf");
        if (!java.nio.file.Files.exists(performanceFile)) {
            try (java.io.InputStream in = plugin.getResource("performance.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, performanceFile);
                }
            } catch (Exception expected) {
                // Ignore failure if performance.conf cannot be extracted
            }
        }

        PerformanceConfiguration performanceConfig;
        if (java.nio.file.Files.exists(performanceFile)) {
            try {
                CommentedConfigurationNode performanceRoot = HoconConfigurationLoader.builder()
                        .path(performanceFile)
                        .build()
                        .load();
                performanceConfig = PerformanceConfiguration.load(performanceRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load performance configuration from: " + performanceFile, e);
            }
        } else {
            performanceConfig = PerformanceConfiguration.defaultConfiguration();
        }

        Path interactablesFile = dataDir.resolve("interactables.conf");
        if (!java.nio.file.Files.exists(interactablesFile)) {
            try (java.io.InputStream in = plugin.getResource("interactables.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, interactablesFile);
                }
            } catch (Exception expected) {
                // Ignore failure if interactables.conf cannot be extracted
            }
        }

        InteractablesConfiguration interactablesConfig;
        if (java.nio.file.Files.exists(interactablesFile)) {
            try {
                CommentedConfigurationNode interactablesRoot = HoconConfigurationLoader.builder()
                        .path(interactablesFile)
                        .build()
                        .load();
                interactablesConfig = InteractablesConfiguration.load(interactablesRoot);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load interactables configuration from: " + interactablesFile, e);
            }
        } else {
            interactablesConfig = InteractablesConfiguration.defaultConfiguration();
        }

        Path worldConfigFile = dataDir.resolve("world.conf");
        if (!java.nio.file.Files.exists(worldConfigFile)) {
            try (java.io.InputStream in = plugin.getResource("world.conf")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, worldConfigFile);
                }
            } catch (Exception expected) {
                // Ignore failure if world.conf cannot be extracted
            }
        }

        WorldConfiguration worldConfig;
        if (java.nio.file.Files.exists(worldConfigFile)) {
            try {
                CommentedConfigurationNode worldRoot = HoconConfigurationLoader.builder()
                        .path(worldConfigFile)
                        .build()
                        .load();
                worldConfig = WorldConfiguration.load(worldRoot);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load world configuration from: " + worldConfigFile, e);
            }
        } else {
            worldConfig = WorldConfiguration.defaultConfiguration();
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
                shopConfig,
                temporaryAccessConfig,
                rewardConfig,
                warpConfig,
                vaultConfig,
                chatConfig,
                inactivityConfig,
                missionConfig,
                levelConfig,
                dimensionConfig,
                limitConfig,
                antiAbuseConfig,
                boosterConfig,
                bankConfig,
                settingsConfig,
                protectionConfig,
                performanceConfig,
                interactablesConfig,
                worldConfig);
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
        pm.registerEvents(chatListener, plugin);
        pm.registerEvents(missionListener, plugin);
        pm.registerEvents(boundaryListener, plugin);
        pm.registerEvents(worthListener, plugin);
        pm.registerEvents(dimensionListener, plugin);
        pm.registerEvents(limitListener, plugin);
        pm.registerEvents(antiAbuseListener, plugin);
        pm.registerEvents(boosterListener, plugin);
        pm.registerEvents(bankruptcyListener, plugin);
        pm.registerEvents(obsidianRecoveryListener, plugin);
        pm.registerEvents(voidProtectionListener, plugin);
        pm.registerEvents(categoricalInteractablesListener, plugin);
        pm.registerEvents(kineticWardListener, plugin);
        pm.registerEvents(redstoneOptimizationListener, plugin);
        pm.registerEvents(structureSuppressionListener, plugin);

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

    public TemporaryAccessService temporaryAccessService() {
        return temporaryAccessService;
    }

    public TemporaryAccessConfiguration temporaryAccessConfiguration() {
        return temporaryAccessConfig;
    }

    public RewardInboxService rewardInboxService() {
        return rewardInboxService;
    }

    public RewardInboxConfiguration rewardInboxConfiguration() {
        return rewardConfig;
    }

    public IslandWarpService warpService() {
        return warpService;
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return safeTeleportEngine;
    }

    public WarpConfiguration warpConfiguration() {
        return warpConfig;
    }

    public IslandVaultService vaultService() {
        return vaultService;
    }

    public VaultConfiguration vaultConfiguration() {
        return vaultConfig;
    }

    public IslandChatService chatService() {
        return chatService;
    }

    public ChatConfiguration chatConfiguration() {
        return chatConfig;
    }

    public IslandChatListener chatListener() {
        return chatListener;
    }

    public IslandInactivityService inactivityService() {
        return inactivityService;
    }

    public InactivityConfiguration inactivityConfiguration() {
        return inactivityConfig;
    }

    public IslandAdminFreezeService freezeService() {
        return freezeService;
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return visitorEvictionAdapter;
    }

    public CurrentNodeProcessIdentity nodeProcessIdentity() {
        return nodeProcessIdentity;
    }

    public MissionConfiguration missionConfiguration() {
        return missionConfig;
    }

    public IslandMissionService missionService() {
        return missionService;
    }

    public IslandMissionsMenu missionsMenu() {
        return missionsMenu;
    }

    public IslandMissionListener missionListener() {
        return missionListener;
    }

    public IslandBoundaryService boundaryService() {
        return boundaryService;
    }

    public IslandBoundaryListener boundaryListener() {
        return boundaryListener;
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return worldBorderAdapter;
    }

    public IslandRecycleService recycleService() {
        return recycleService;
    }

    public IslandResetConfirmationMenu resetConfirmationMenu() {
        return resetConfirmationMenu;
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return voidingAdapter;
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return islandBackupAdapter;
    }

    public LevelConfiguration levelConfiguration() {
        return levelConfig;
    }

    public IslandWorthService worthService() {
        return worthService;
    }

    public FoliaIslandChunkScanner chunkScanner() {
        return chunkScanner;
    }

    public IslandWorthListener worthListener() {
        return worthListener;
    }

    public DimensionConfiguration dimensionConfiguration() {
        return dimensionConfig;
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }

    public IslandDimensionListener dimensionListener() {
        return dimensionListener;
    }

    public LimitConfiguration limitConfiguration() {
        return limitConfig;
    }

    public IslandLimitService limitService() {
        return limitService;
    }

    public IslandLimitListener limitListener() {
        return limitListener;
    }

    public AntiAbuseConfiguration antiAbuseConfiguration() {
        return antiAbuseConfig;
    }

    public IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
    }

    public IslandAntiAbuseListener antiAbuseListener() {
        return antiAbuseListener;
    }

    public BoosterConfiguration boosterConfiguration() {
        return boosterConfig;
    }

    public IslandBoosterService boosterService() {
        return boosterService;
    }

    public IslandBoosterListener boosterListener() {
        return boosterListener;
    }

    public IslandBoosterMenu boosterMenu() {
        return boosterMenu;
    }

    public BankConfiguration bankConfiguration() {
        return bankConfig;
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public IslandBankruptcyListener bankruptcyListener() {
        return bankruptcyListener;
    }

    public BankUpkeepFeatureModule bankUpkeepFeatureModule() {
        return bankUpkeepFeatureModule;
    }

    public SettingsConfiguration settingsConfiguration() {
        return settingsConfig;
    }

    public ProtectionConfiguration protectionConfiguration() {
        return protectionConfig;
    }

    public PerformanceConfiguration performanceConfiguration() {
        return performanceConfig;
    }

    public InteractablesConfiguration interactablesConfiguration() {
        return interactablesConfig;
    }

    public WorldConfiguration worldConfiguration() {
        return worldConfig;
    }

    public IslandNameService islandNameService() {
        return islandNameService;
    }

    public AdaptiveBackpressureController backpressureController() {
        return backpressureController;
    }

    public KineticWardService kineticWardService() {
        return kineticWardService;
    }

    public IslandWebMapService islandWebMapService() {
        return islandWebMapService;
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return obsidianRecoveryListener;
    }

    public VoidProtectionListener voidProtectionListener() {
        return voidProtectionListener;
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return categoricalInteractablesListener;
    }

    public KineticWardListener kineticWardListener() {
        return kineticWardListener;
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return redstoneOptimizationListener;
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return structureSuppressionListener;
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
