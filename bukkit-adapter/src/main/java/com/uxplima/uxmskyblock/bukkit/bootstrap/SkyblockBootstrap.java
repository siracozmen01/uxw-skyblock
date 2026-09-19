package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.api.BukkitSkyblockApiBridge;
import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.command.IslandCommandTree;
import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LevelConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LimitConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.MissionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ModuleSettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PerformanceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SeasonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ShopConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SocialConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WarpConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.WorldConfiguration;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.integration.placeholder.SkyblockPlaceholderExpansion;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitReconciler;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.module.BukkitModuleContext;
import com.uxplima.uxmskyblock.bukkit.module.builtin.BankUpkeepFeatureModule;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.webmap.WebMapAdapter;
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
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.discord.IslandDiscordWebhookService;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.TransactionalOutboxDispatcher;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.module.ModuleRegistry;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.VelocityBridgePort;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.application.webmap.IslandWebMapService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

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
                plugin, persistenceWiring.bootstrap().islandStoragePort(), scheduler);
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
                protectionListener);

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
                economyBridgeRef::get);

        this.integrationWiring = new IntegrationWiring(
                plugin, configWiring, persistenceWiring.bootstrap(), authorityWiring, gameplayWiring);
        economyBridgeRef.set(this.integrationWiring.economyBridge());

        this.featureModuleWiring =
                new FeatureModuleWiring(configWiring, persistenceWiring.bootstrap(), gameplayWiring, integrationWiring);
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
            WorldConfiguration worldConfig,
            UpgradesConfiguration upgradesConfig,
            GeneratorsConfiguration generatorsConfig) {
        this(
                plugin,
                new PersistenceWiring(persistenceBootstrap),
                ConfigurationWiring.of(
                        plugin.getDataFolder().toPath(),
                        null,
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
                        settingsConfig,
                        protectionConfig,
                        performanceConfig,
                        interactablesConfig,
                        worldConfig,
                        upgradesConfig,
                        generatorsConfig));
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
                settingsConfig,
                protectionConfig,
                performanceConfig,
                interactablesConfig,
                worldConfig,
                UpgradesConfiguration.defaultConfiguration(),
                GeneratorsConfiguration.defaultConfiguration());
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

    public static SkyblockBootstrap createDefault(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        ConfigurationWiring configWiring = ConfigurationWiring.loadAndValidate(plugin);
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

        PluginManager pm = Bukkit.getPluginManager();
        CatalogPermissions.registerAll(pm);
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("core")) {
            pm.registerEvents(gameplayWiring.protectionListener(), plugin);
            pm.registerEvents(authorityWiring.sessionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("chat")) {
            pm.registerEvents(gameplayWiring.chatListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("missions")) {
            pm.registerEvents(gameplayWiring.missionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boundary")) {
            pm.registerEvents(gameplayWiring.boundaryListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("worth")) {
            pm.registerEvents(gameplayWiring.worthListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("dimensions")) {
            pm.registerEvents(gameplayWiring.dimensionListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("limits")) {
            pm.registerEvents(gameplayWiring.limitListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("anti-abuse")) {
            pm.registerEvents(gameplayWiring.antiAbuseListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("boosters")) {
            pm.registerEvents(gameplayWiring.boosterListener(), plugin);
        }
        if (featureModuleWiring.moduleRegistry().isModuleEnabled("bank-upkeep")) {
            pm.registerEvents(gameplayWiring.bankruptcyListener(), plugin);
        }
        pm.registerEvents(gameplayWiring.obsidianRecoveryListener(), plugin);
        pm.registerEvents(gameplayWiring.voidProtectionListener(), plugin);
        pm.registerEvents(gameplayWiring.categoricalInteractablesListener(), plugin);
        pm.registerEvents(gameplayWiring.kineticWardListener(), plugin);
        pm.registerEvents(gameplayWiring.redstoneOptimizationListener(), plugin);
        pm.registerEvents(gameplayWiring.structureSuppressionListener(), plugin);
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

    public SchedulerPort scheduler() {
        return gameplayWiring.scheduler();
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

    public ModuleSettingsConfiguration moduleSettings() {
        return configWiring.moduleSettings();
    }

    public ModuleRegistry moduleRegistry() {
        return featureModuleWiring.moduleRegistry();
    }

    public BukkitModuleContext moduleContext() {
        return featureModuleWiring.moduleContext();
    }

    public PlayerSessionCoordinator sessionCoordinator() {
        return authorityWiring.sessionCoordinator();
    }

    public SwitchProfileUseCase switchProfileUseCase() {
        return authorityWiring.switchProfileUseCase();
    }

    public BukkitSkyblockApiBridge apiBridge() {
        return integrationWiring.apiBridge();
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

    public SeasonConfiguration seasonConfiguration() {
        return configWiring.seasonConfig();
    }

    public IslandSocialService socialService() {
        return gameplayWiring.socialService();
    }

    public SocialConfiguration socialConfiguration() {
        return configWiring.socialConfig();
    }

    public IslandDiscordWebhookService discordService() {
        return integrationWiring.discordService();
    }

    public DiscordConfiguration discordConfiguration() {
        return configWiring.discordConfig();
    }

    public IslandAllianceService allianceService() {
        return gameplayWiring.allianceService();
    }

    public AllianceConfiguration allianceConfiguration() {
        return configWiring.allianceConfig();
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return gameplayWiring.dynamicPricingEngine();
    }

    public ShopConfiguration shopConfiguration() {
        return configWiring.shopConfig();
    }

    public TemporaryAccessService temporaryAccessService() {
        return gameplayWiring.temporaryAccessService();
    }

    public TemporaryAccessConfiguration temporaryAccessConfiguration() {
        return configWiring.temporaryAccessConfig();
    }

    public RewardInboxService rewardInboxService() {
        return gameplayWiring.rewardInboxService();
    }

    public RewardInboxConfiguration rewardInboxConfiguration() {
        return configWiring.rewardConfig();
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

    public IslandChatListener chatListener() {
        return gameplayWiring.chatListener();
    }

    public IslandInactivityService inactivityService() {
        return gameplayWiring.inactivityService();
    }

    public InactivityConfiguration inactivityConfiguration() {
        return configWiring.inactivityConfig();
    }

    public IslandAdminFreezeService freezeService() {
        return gameplayWiring.freezeService();
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return gameplayWiring.visitorEvictionAdapter();
    }

    public CurrentNodeProcessIdentity nodeProcessIdentity() {
        return authorityWiring.nodeProcessIdentity();
    }

    public MissionConfiguration missionConfiguration() {
        return configWiring.missionConfig();
    }

    public IslandMissionService missionService() {
        return gameplayWiring.missionService();
    }

    public IslandMissionsMenu missionsMenu() {
        return gameplayWiring.missionsMenu();
    }

    public IslandMissionListener missionListener() {
        return gameplayWiring.missionListener();
    }

    public IslandBoundaryService boundaryService() {
        return gameplayWiring.boundaryService();
    }

    public IslandBoundaryListener boundaryListener() {
        return gameplayWiring.boundaryListener();
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return gameplayWiring.worldBorderAdapter();
    }

    public IslandRecycleService recycleService() {
        return gameplayWiring.recycleService();
    }

    public IslandResetConfirmationMenu resetConfirmationMenu() {
        return gameplayWiring.resetConfirmationMenu();
    }

    public BedrockFormService bedrockFormService() {
        return integrationWiring.bedrockFormService();
    }

    public WorldDimensionSnapshotAdapter worldDimensionSnapshotAdapter() {
        return integrationWiring.worldDimensionSnapshotAdapter();
    }

    public WebMapAdapter webMapAdapter() {
        return integrationWiring.webMapAdapter();
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return gameplayWiring.voidingAdapter();
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return gameplayWiring.islandBackupAdapter();
    }

    public LevelConfiguration levelConfiguration() {
        return configWiring.levelConfig();
    }

    public IslandWorthService worthService() {
        return gameplayWiring.worthService();
    }

    public FoliaIslandChunkScanner chunkScanner() {
        return gameplayWiring.chunkScanner();
    }

    public IslandWorthListener worthListener() {
        return gameplayWiring.worthListener();
    }

    public DimensionConfiguration dimensionConfiguration() {
        return configWiring.dimensionConfig();
    }

    public IslandDimensionService dimensionService() {
        return gameplayWiring.dimensionService();
    }

    public IslandDimensionListener dimensionListener() {
        return gameplayWiring.dimensionListener();
    }

    public LimitConfiguration limitConfiguration() {
        return configWiring.limitConfig();
    }

    public IslandLimitService limitService() {
        return gameplayWiring.limitService();
    }

    public IslandLimitListener limitListener() {
        return gameplayWiring.limitListener();
    }

    public IslandLimitReconciler limitReconciler() {
        return gameplayWiring.limitReconciler();
    }

    public AntiAbuseConfiguration antiAbuseConfiguration() {
        return configWiring.antiAbuseConfig();
    }

    public IslandAntiAbuseService antiAbuseService() {
        return gameplayWiring.antiAbuseService();
    }

    public IslandAntiAbuseListener antiAbuseListener() {
        return gameplayWiring.antiAbuseListener();
    }

    public BoosterConfiguration boosterConfiguration() {
        return configWiring.boosterConfig();
    }

    public IslandBoosterService boosterService() {
        return gameplayWiring.boosterService();
    }

    public IslandBoosterListener boosterListener() {
        return gameplayWiring.boosterListener();
    }

    public IslandBoosterMenu boosterMenu() {
        return gameplayWiring.boosterMenu();
    }

    public BankConfiguration bankConfiguration() {
        return configWiring.bankConfig();
    }

    public IslandBankService bankService() {
        return gameplayWiring.bankService();
    }

    public IslandBankruptcyService bankruptcyService() {
        return gameplayWiring.bankruptcyService();
    }

    public IslandBankruptcyListener bankruptcyListener() {
        return gameplayWiring.bankruptcyListener();
    }

    public BankUpkeepFeatureModule bankUpkeepFeatureModule() {
        return featureModuleWiring.bankUpkeepFeatureModule();
    }

    public SettingsConfiguration settingsConfiguration() {
        return configWiring.settingsConfig();
    }

    public ProtectionConfiguration protectionConfiguration() {
        return configWiring.protectionConfig();
    }

    public PerformanceConfiguration performanceConfiguration() {
        return configWiring.performanceConfig();
    }

    public InteractablesConfiguration interactablesConfiguration() {
        return configWiring.interactablesConfig();
    }

    public WorldConfiguration worldConfiguration() {
        return configWiring.worldConfig();
    }

    public UpgradesConfiguration upgradesConfiguration() {
        return configWiring.upgradesConfig();
    }

    public GeneratorsConfiguration generatorsConfiguration() {
        return configWiring.generatorsConfig();
    }

    public IslandUpgradeService upgradeService() {
        return gameplayWiring.upgradeService();
    }

    public OreGeneratorListener oreGeneratorListener() {
        return gameplayWiring.oreGeneratorListener();
    }

    public UpgradesModule upgradesModule() {
        return gameplayWiring.upgradesModule();
    }

    public IslandNameService islandNameService() {
        return gameplayWiring.islandNameService();
    }

    public AdaptiveBackpressureController backpressureController() {
        return gameplayWiring.backpressureController();
    }

    public KineticWardService kineticWardService() {
        return gameplayWiring.kineticWardService();
    }

    public IslandWebMapService islandWebMapService() {
        return integrationWiring.islandWebMapService();
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return gameplayWiring.obsidianRecoveryListener();
    }

    public VoidProtectionListener voidProtectionListener() {
        return gameplayWiring.voidProtectionListener();
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return gameplayWiring.categoricalInteractablesListener();
    }

    public KineticWardListener kineticWardListener() {
        return gameplayWiring.kineticWardListener();
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return gameplayWiring.redstoneOptimizationListener();
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return gameplayWiring.structureSuppressionListener();
    }

    public DurableEventTransportPort eventTransport() {
        return integrationWiring.eventTransport();
    }

    public VelocityBridgePort velocityBridge() {
        return integrationWiring.velocityBridge();
    }

    public IslandNetworkRouter networkRouter() {
        return integrationWiring.networkRouter();
    }

    public MessageProvider messageProvider() {
        return integrationWiring.messageProvider();
    }

    @Override
    public void close() {
        featureModuleWiring.close();
        gameplayWiring.missionService().flushDirtyProgress();
        integrationWiring.close();
        authorityWiring.close();
        persistenceWiring.close();
    }
}
