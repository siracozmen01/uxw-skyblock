package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

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
import com.uxplima.uxmskyblock.bukkit.config.PlayerStateConfigurationAdapter;
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
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Reads the configuration off disk and hands back a validated {@link ConfigurationWiring}.
 *
 * <p>Two jobs lived in one class: finding files, unpacking the shipped defaults and parsing HOCON,
 * and then holding the thirty-one records that come out of it. They change for different reasons.
 * This class is the first job, and nothing else in the plugin touches a configuration file.
 *
 * <p>Directory topology:
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
public final class ConfigurationLoader {

    private static final Logger LOGGER = Logger.getLogger(ConfigurationLoader.class.getName());

    /**
     * The menu files this plugin ships, written next to the server once and never over an edit.
     *
     * <p>Adding a menu is a file here and a line in this list. A menu the operator writes themselves
     * needs neither: the engine reads every {@code menus/*.conf} it finds, shipped or not.
     */
    private static final List<String> SHIPPED_MENUS = List.of(
            "island-main.conf",
            "island-bank.conf",
            "island-upgrades.conf",
            "island-members.conf",
            "island-settings.conf",
            "island-warps.conf",
            "island-vault.conf",
            "island-missions.conf",
            "island-boosters.conf",
            "island-biome.conf",
            "island-homes.conf",
            "island-social.conf",
            "island-top.conf");

    private ConfigurationLoader() {
        throw new UnsupportedOperationException("ConfigurationLoader is a set of loading steps, not a thing to hold.");
    }

    /**
     * Unpacks default assets into the canonical directory topology, loads all HOCON configurations,
     * and performs strict fail-closed validation.
     */
    public static ConfigurationWiring loadAndValidate(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Path dataDir = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(dataDir.resolve("modules"));
            Files.createDirectories(dataDir.resolve("menus"));
            Files.createDirectories(dataDir.resolve("messages"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize plugin directories at: " + dataDir, e);
        }

        // 1. Unpack messages catalog & menus templates
        unpackResource(plugin, "messages/messages_en.conf", dataDir.resolve("messages/messages_en.conf"));
        unpackResource(plugin, "messages/messages_tr.conf", dataDir.resolve("messages/messages_tr.conf"));
        for (String menu : SHIPPED_MENUS) {
            unpackResource(plugin, "menus/" + menu, dataDir.resolve("menus/" + menu));
        }

        // 2. Load root config.conf
        Path configFile = dataDir.resolve("config.conf");
        unpackResource(plugin, "config.conf", configFile);
        CommentedConfigurationNode root = loadHocon(configFile);

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

        // 3. Load modules.conf
        Path modulesFile = dataDir.resolve("modules.conf");
        unpackResource(plugin, "modules.conf", modulesFile);
        CommentedConfigurationNode modulesRoot = loadHocon(modulesFile);
        ModuleSettingsConfiguration moduleSettings = modulesRoot != null
                ? ModuleSettingsConfiguration.load(modulesRoot)
                : ModuleSettingsConfiguration.empty();

        // 4. Load modular subsystem configurations
        SeasonConfiguration seasonConfig = loadConfig(
                plugin, dataDir, "seasons.conf", SeasonConfiguration::load, SeasonConfiguration.defaultConfiguration());
        SocialConfiguration socialConfig = loadConfig(
                plugin, dataDir, "social.conf", SocialConfiguration::load, SocialConfiguration.defaultConfiguration());
        DiscordConfiguration discordConfig = loadConfig(
                plugin,
                dataDir,
                "discord.conf",
                DiscordConfiguration::load,
                DiscordConfiguration.defaultConfiguration());
        AllianceConfiguration allianceConfig = loadConfig(
                plugin,
                dataDir,
                "alliances.conf",
                AllianceConfiguration::load,
                AllianceConfiguration.defaultConfiguration());
        ShopConfiguration shopConfig = loadConfig(
                plugin, dataDir, "shop.conf", ShopConfiguration::load, ShopConfiguration.defaultConfiguration());
        TemporaryAccessConfiguration temporaryAccessConfig = loadConfig(
                plugin,
                dataDir,
                "temporary-access.conf",
                TemporaryAccessConfiguration::load,
                TemporaryAccessConfiguration.defaultConfiguration());
        RewardInboxConfiguration rewardConfig = loadConfig(
                plugin,
                dataDir,
                "rewards.conf",
                RewardInboxConfiguration::load,
                RewardInboxConfiguration.defaultConfiguration());
        WarpConfiguration warpConfig = loadConfig(
                plugin, dataDir, "warps.conf", WarpConfiguration::load, WarpConfiguration.defaultConfiguration());
        VaultConfiguration vaultConfig = loadConfig(
                plugin, dataDir, "vault.conf", VaultConfiguration::load, VaultConfiguration.defaultConfiguration());
        ChatConfiguration chatConfig = loadConfig(
                plugin, dataDir, "chat.conf", ChatConfiguration::load, ChatConfiguration.defaultConfiguration());
        InactivityConfiguration inactivityConfig = loadConfig(
                plugin,
                dataDir,
                "inactivity.conf",
                InactivityConfiguration::load,
                InactivityConfiguration.defaultConfiguration());
        MissionConfiguration missionConfig = loadConfig(
                plugin,
                dataDir,
                "missions.conf",
                MissionConfiguration::load,
                MissionConfiguration.defaultConfiguration());
        LevelConfiguration levelConfig = loadConfig(
                plugin, dataDir, "levels.conf", LevelConfiguration::load, LevelConfiguration.defaultConfiguration());
        DimensionConfiguration dimensionConfig = loadConfig(
                plugin,
                dataDir,
                "dimensions.conf",
                DimensionConfiguration::load,
                DimensionConfiguration.defaultConfiguration());
        LimitConfiguration limitConfig = loadConfig(
                plugin, dataDir, "limits.conf", LimitConfiguration::load, LimitConfiguration.defaultConfiguration());
        AntiAbuseConfiguration antiAbuseConfig = loadConfig(
                plugin,
                dataDir,
                "anti_abuse.conf",
                AntiAbuseConfiguration::load,
                AntiAbuseConfiguration.defaultConfiguration());
        BoosterConfiguration boosterConfig = loadConfig(
                plugin,
                dataDir,
                "boosters.conf",
                BoosterConfiguration::load,
                BoosterConfiguration.defaultConfiguration());
        BankConfiguration bankConfig = loadConfig(
                plugin, dataDir, "bank.conf", BankConfiguration::load, BankConfiguration.defaultConfiguration());
        SettingsConfiguration settingsConfig = loadConfig(
                plugin,
                dataDir,
                "settings.conf",
                SettingsConfiguration::load,
                SettingsConfiguration.defaultConfiguration());
        ProtectionConfiguration protectionConfig = loadConfig(
                plugin,
                dataDir,
                "protection.conf",
                ProtectionConfiguration::load,
                ProtectionConfiguration.defaultConfiguration());
        PerformanceConfiguration performanceConfig = loadConfig(
                plugin,
                dataDir,
                "performance.conf",
                PerformanceConfiguration::load,
                PerformanceConfiguration.defaultConfiguration());
        InteractablesConfiguration interactablesConfig = loadConfig(
                plugin,
                dataDir,
                "interactables.conf",
                InteractablesConfiguration::load,
                InteractablesConfiguration.defaultConfiguration());
        WorldConfiguration worldConfig = loadConfig(
                plugin, dataDir, "world.conf", WorldConfiguration::load, WorldConfiguration.defaultConfiguration());
        UpgradesConfiguration upgradesConfig = loadConfig(
                plugin,
                dataDir,
                "upgrades.conf",
                UpgradesConfiguration::load,
                UpgradesConfiguration.defaultConfiguration());
        GeneratorsConfiguration generatorsConfig = loadConfig(
                plugin,
                dataDir,
                "generators.conf",
                GeneratorsConfiguration::load,
                GeneratorsConfiguration.defaultConfiguration());

        ConfigurationWiring wiring = new ConfigurationWiring(
                dataDir,
                root,
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

    @FunctionalInterface
    public interface ConfigParser<T> {
        T parse(CommentedConfigurationNode node);
    }

    private static <T> T loadConfig(
            JavaPlugin plugin, Path dataDir, String configName, ConfigParser<T> parser, T defaultVal) {
        Path moduleFile = dataDir.resolve("modules").resolve(configName);
        Path rootFile = dataDir.resolve(configName);

        Path targetFile;
        if (Files.exists(moduleFile)) {
            targetFile = moduleFile;
        } else if (Files.exists(rootFile)) {
            targetFile = rootFile;
        } else {
            unpackResource(plugin, "modules/" + configName, moduleFile);
            if (!Files.exists(moduleFile)) {
                unpackResource(plugin, configName, moduleFile);
            }
            targetFile = moduleFile;
        }

        if (Files.exists(targetFile)) {
            try {
                CommentedConfigurationNode node = HoconConfigurationLoader.builder()
                        .path(targetFile)
                        .build()
                        .load();
                return parser.parse(node);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse configuration from " + targetFile, e);
            }
        }
        return defaultVal;
    }

    /**
     * Writes a shipped default next to the server, once, and never over an operator's edit.
     *
     * <p>A failure here is not fatal: the subsystem falls back to its own defaults and the server
     * starts. It is not silent either. An operator whose disk was full or whose folder was read only
     * would otherwise edit a file the plugin never wrote and wonder why nothing changed.
     */
    private static void unpackResource(JavaPlugin plugin, String resourcePath, Path targetPath) {
        if (Files.exists(targetPath)) {
            return;
        }
        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in != null) {
                    Files.copy(in, targetPath);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Could not write the shipped default " + resourcePath + " to " + targetPath + ": "
                    + e.getMessage() + ". The subsystem will use its built in defaults instead.");
        }
    }

    private static @Nullable CommentedConfigurationNode loadHocon(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return HoconConfigurationLoader.builder().path(file).build().load();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration file: " + file, e);
        }
    }
}
