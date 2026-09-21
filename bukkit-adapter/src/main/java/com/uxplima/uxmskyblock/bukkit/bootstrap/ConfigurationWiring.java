package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.AllianceConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.ChatConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.DiscordConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.GeneratorsConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InactivityConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
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
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * Manages the canonical configuration hierarchy and fail-closed schema validation.
 *
 * <p>Directory Topology:
 * <pre>
 * plugins/uxmSkyblock/
 * ├── config.conf            # Core platform, database, grid parameters
 * ├── modules.conf           # Master feature toggles
 * ├── modules/               # Modular subsystem configurations
 * │   ├── alliances.conf
 * │   ├── bank.conf
 * │   ├── limits.conf
 * │   └── ...
 * └── messages/              # Multi-locale catalog files
 *     ├── messages_en.conf
 *     └── messages_tr.conf
 * </pre>
 */
public final class ConfigurationWiring {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationWiring.class.getName());

    private final Path dataDir;
    private final @Nullable CommentedConfigurationNode rootNode;
    private final ServerNodeConfiguration nodeConfig;
    private final PlayerStateDurabilityConfig playerStateConfig;
    private final ModuleSettingsConfiguration moduleSettings;
    private final SeasonConfiguration seasonConfig;
    private final SocialConfiguration socialConfig;
    private final DiscordConfiguration discordConfig;
    private final AllianceConfiguration allianceConfig;
    private final ShopConfiguration shopConfig;
    private final TemporaryAccessConfiguration temporaryAccessConfig;
    private final RewardInboxConfiguration rewardConfig;
    private final WarpConfiguration warpConfig;
    private final VaultConfiguration vaultConfig;
    private final ChatConfiguration chatConfig;
    private final InactivityConfiguration inactivityConfig;
    private final MissionConfiguration missionConfig;
    private final LevelConfiguration levelConfig;
    private final DimensionConfiguration dimensionConfig;
    private final LimitConfiguration limitConfig;
    private final AntiAbuseConfiguration antiAbuseConfig;
    private final BoosterConfiguration boosterConfig;
    private final BankConfiguration bankConfig;
    private final SettingsConfiguration settingsConfig;
    private final ProtectionConfiguration protectionConfig;
    private final PerformanceConfiguration performanceConfig;
    private final InteractablesConfiguration interactablesConfig;
    private final WorldConfiguration worldConfig;
    private final UpgradesConfiguration upgradesConfig;
    private final GeneratorsConfiguration generatorsConfig;
    private final Messages messages;
    private final HomeConfiguration homeConfig;

    /**
     * The catalog is configuration, so it is built here rather than in a later wiring step. The
     * listeners are constructed before the integration layer exists, and they answer a player too.
     */
    private static Messages buildMessages(@Nullable CommentedConfigurationNode rootNode, Path dataDir) {
        LanguageConfiguration language = LanguageConfiguration.load(rootNode);
        MessageProvider provider = new MessageProvider(language.defaultLanguage());
        provider.loadBundledDefaults(ConfigurationWiring.class.getClassLoader());

        Path messagesDir = dataDir.resolve("messages");
        if (Files.isDirectory(messagesDir)) {
            try (java.util.stream.Stream<Path> files = Files.list(messagesDir)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.startsWith("messages_") || !name.endsWith(".conf")) {
                        continue;
                    }
                    String locale = name.substring("messages_".length(), name.length() - ".conf".length());
                    try {
                        provider.loadFromFile(locale, file);
                    } catch (IOException e) {
                        LOGGER.warning("Failed loading the message catalog " + file + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warning("Failed listing the messages folder " + messagesDir + ": " + e.getMessage());
            }
        }
        return Messages.of(provider, language);
    }

    ConfigurationWiring(
            Path dataDir,
            @Nullable CommentedConfigurationNode rootNode,
            ServerNodeConfiguration nodeConfig,
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
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir must not be null");
        this.rootNode = rootNode;
        this.nodeConfig = Objects.requireNonNull(nodeConfig, "nodeConfig must not be null");
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
        this.upgradesConfig = Objects.requireNonNull(upgradesConfig, "upgradesConfig must not be null");
        this.generatorsConfig = Objects.requireNonNull(generatorsConfig, "generatorsConfig must not be null");
        this.messages = buildMessages(rootNode, dataDir);
        this.homeConfig = HomeConfiguration.load(rootNode);
    }

    /**
     * Reusable factory for programmatically supplied configuration records.
     */
    public static ConfigurationWiring of(
            Path dataDir,
            @Nullable CommentedConfigurationNode rootNode,
            ServerNodeConfiguration nodeConfig,
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
        ConfigurationWiring wiring = new ConfigurationWiring(
                dataDir,
                rootNode,
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
                worldConfig,
                upgradesConfig,
                generatorsConfig);
        wiring.validate();
        return wiring;
    }

    /**
     * Fail-closed schema and constraint validation.
     */
    public void validate() {
        // 1. Server node validation
        if (nodeConfig.nodeId() == null || nodeConfig.nodeId().value().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration nodeId must not be blank.");
        }
        if (nodeConfig.worldName() == null || nodeConfig.worldName().isBlank()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: ServerNodeConfiguration worldName must not be blank.");
        }

        // 2. Durability checkpoint interval validation
        if (playerStateConfig.ambientCheckpointInterval().isNegative()
                || playerStateConfig.ambientCheckpointInterval().isZero()) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: PlayerStateDurabilityConfig ambientCheckpointInterval must be strictly positive: "
                            + playerStateConfig.ambientCheckpointInterval());
        }

        // 3. Hardware limit bounds validation
        for (com.uxplima.uxmskyblock.core.domain.limit.LimitQuota quota :
                limitConfig.quotas().values()) {
            if (quota.baseLimit() < 0 || quota.perTierBonus() < 0) {
                throw new IllegalStateException(
                        "Fail-closed configuration error: Limit quota cannot have negative base or tier bonus: "
                                + quota);
            }
        }

        // 4. Performance throttle validation
        if (performanceConfig.normalBlocksPerTick() <= 0 || performanceConfig.normalChunksPerSec() <= 0) {
            throw new IllegalStateException(
                    "Fail-closed configuration error: Performance block/chunk quotas must be strictly positive.");
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    /** How many named homes a player may keep, and which permission buys more. */
    public HomeConfiguration homeConfig() {
        return homeConfig;
    }

    /** The words this server answers in, in every language it ships. */
    public Messages messages() {
        return messages;
    }

    public @Nullable CommentedConfigurationNode rootNode() {
        return rootNode;
    }

    public ServerNodeConfiguration nodeConfig() {
        return nodeConfig;
    }

    public PlayerStateDurabilityConfig playerStateConfig() {
        return playerStateConfig;
    }

    public ModuleSettingsConfiguration moduleSettings() {
        return moduleSettings;
    }

    public SeasonConfiguration seasonConfig() {
        return seasonConfig;
    }

    public SocialConfiguration socialConfig() {
        return socialConfig;
    }

    public DiscordConfiguration discordConfig() {
        return discordConfig;
    }

    public AllianceConfiguration allianceConfig() {
        return allianceConfig;
    }

    public ShopConfiguration shopConfig() {
        return shopConfig;
    }

    public TemporaryAccessConfiguration temporaryAccessConfig() {
        return temporaryAccessConfig;
    }

    public RewardInboxConfiguration rewardConfig() {
        return rewardConfig;
    }

    public WarpConfiguration warpConfig() {
        return warpConfig;
    }

    public VaultConfiguration vaultConfig() {
        return vaultConfig;
    }

    public ChatConfiguration chatConfig() {
        return chatConfig;
    }

    public InactivityConfiguration inactivityConfig() {
        return inactivityConfig;
    }

    public MissionConfiguration missionConfig() {
        return missionConfig;
    }

    public LevelConfiguration levelConfig() {
        return levelConfig;
    }

    public DimensionConfiguration dimensionConfig() {
        return dimensionConfig;
    }

    public LimitConfiguration limitConfig() {
        return limitConfig;
    }

    public AntiAbuseConfiguration antiAbuseConfig() {
        return antiAbuseConfig;
    }

    public BoosterConfiguration boosterConfig() {
        return boosterConfig;
    }

    public BankConfiguration bankConfig() {
        return bankConfig;
    }

    public SettingsConfiguration settingsConfig() {
        return settingsConfig;
    }

    public ProtectionConfiguration protectionConfig() {
        return protectionConfig;
    }

    public PerformanceConfiguration performanceConfig() {
        return performanceConfig;
    }

    public InteractablesConfiguration interactablesConfig() {
        return interactablesConfig;
    }

    public WorldConfiguration worldConfig() {
        return worldConfig;
    }

    public UpgradesConfiguration upgradesConfig() {
        return upgradesConfig;
    }

    public GeneratorsConfiguration generatorsConfig() {
        return generatorsConfig;
    }
}
