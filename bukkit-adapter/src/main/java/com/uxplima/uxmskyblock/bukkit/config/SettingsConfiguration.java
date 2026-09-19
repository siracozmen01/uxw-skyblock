package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Operational gameplay settings and feature toggles (Section 2.41).
 */
public record SettingsConfiguration(
        boolean obsidianToLava,
        boolean voidTeleportMembers,
        boolean voidTeleportVisitors,
        String defaultCommandAction,
        boolean islandNamesEnabled,
        int islandNamesMinLength,
        int islandNamesMaxLength,
        Duration teleportWarmup,
        boolean teleportOnPvpEnable,
        boolean immuneToPvpWhenTeleport,
        Duration pvpTeleportInvulnerability,
        boolean stopBorderCrossing,
        boolean disableRedstoneOffline,
        boolean afkDisableSpawning,
        boolean afkDisableRedstone,
        boolean netherRoof,
        String syncWorthWithShop,
        boolean negativeLevelAllowed,
        boolean endDragonFightEnabled) {

    public static final boolean DEFAULT_OBSIDIAN_TO_LAVA = true;
    public static final boolean DEFAULT_VOID_MEMBERS = true;
    public static final boolean DEFAULT_VOID_VISITORS = true;
    public static final String DEFAULT_CMD_ACTION = "auto";
    public static final boolean DEFAULT_NAMES_ENABLED = true;
    public static final int DEFAULT_NAMES_MIN = 3;
    public static final int DEFAULT_NAMES_MAX = 16;
    public static final Duration DEFAULT_WARMUP = Duration.ofSeconds(3);
    public static final boolean DEFAULT_TELEPORT_ON_PVP = true;
    public static final boolean DEFAULT_IMMUNE_PVP_TELEPORT = true;
    public static final Duration DEFAULT_PVP_INVULN = Duration.ofSeconds(10);
    public static final boolean DEFAULT_STOP_BORDER_CROSSING = true;
    public static final boolean DEFAULT_DISABLE_REDSTONE_OFFLINE = true;
    public static final boolean DEFAULT_AFK_SPAWNING = true;
    public static final boolean DEFAULT_AFK_REDSTONE = true;
    public static final boolean DEFAULT_NETHER_ROOF = false;
    public static final String DEFAULT_SYNC_WORTH = "BUY";
    public static final boolean DEFAULT_NEGATIVE_LEVEL = false;
    public static final boolean DEFAULT_DRAGON_FIGHT = true;

    public static SettingsConfiguration defaultConfiguration() {
        return new SettingsConfiguration(
                DEFAULT_OBSIDIAN_TO_LAVA,
                DEFAULT_VOID_MEMBERS,
                DEFAULT_VOID_VISITORS,
                DEFAULT_CMD_ACTION,
                DEFAULT_NAMES_ENABLED,
                DEFAULT_NAMES_MIN,
                DEFAULT_NAMES_MAX,
                DEFAULT_WARMUP,
                DEFAULT_TELEPORT_ON_PVP,
                DEFAULT_IMMUNE_PVP_TELEPORT,
                DEFAULT_PVP_INVULN,
                DEFAULT_STOP_BORDER_CROSSING,
                DEFAULT_DISABLE_REDSTONE_OFFLINE,
                DEFAULT_AFK_SPAWNING,
                DEFAULT_AFK_REDSTONE,
                DEFAULT_NETHER_ROOF,
                DEFAULT_SYNC_WORTH,
                DEFAULT_NEGATIVE_LEVEL,
                DEFAULT_DRAGON_FIGHT);
    }

    public static SettingsConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        if (rootNode.virtual() || rootNode.empty()) {
            return defaultConfiguration();
        }

        ConfigurationNode qolNode = rootNode.hasChild("quality-of-life") ? rootNode.node("quality-of-life") : rootNode;
        boolean obsidianToLava = qolNode.node("obsidian-to-lava").getBoolean(DEFAULT_OBSIDIAN_TO_LAVA);
        boolean voidMembers = qolNode.hasChild("void-teleport")
                ? qolNode.node("void-teleport", "members").getBoolean(DEFAULT_VOID_MEMBERS)
                : qolNode.node("void-teleport-members").getBoolean(DEFAULT_VOID_MEMBERS);
        boolean voidVisitors = qolNode.hasChild("void-teleport")
                ? qolNode.node("void-teleport", "visitors").getBoolean(DEFAULT_VOID_VISITORS)
                : qolNode.node("void-teleport-visitors").getBoolean(DEFAULT_VOID_VISITORS);
        String defaultCmdAction = qolNode.node("default-command-action").getString(DEFAULT_CMD_ACTION);

        ConfigurationNode namesNode = qolNode.hasChild("island-names") ? qolNode.node("island-names") : qolNode;
        boolean namesEnabled = namesNode
                .node("enabled")
                .getBoolean(namesNode.node("island-names-enabled").getBoolean(DEFAULT_NAMES_ENABLED));
        int namesMin = namesNode.node("min-length").getInt(DEFAULT_NAMES_MIN);
        int namesMax = namesNode.node("max-length").getInt(DEFAULT_NAMES_MAX);
        int warmupSec = 3;
        ConfigurationNode warmupNode = qolNode.hasChild("teleport-warmup-seconds")
                ? qolNode.node("teleport-warmup-seconds")
                : qolNode.node("teleport-warmup");
        if (!warmupNode.virtual() && warmupNode.raw() != null) {
            String raw = warmupNode.getString("3").replace("s", "").trim();
            try {
                warmupSec = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                warmupSec = 3;
            }
        }

        ConfigurationNode combatNode =
                rootNode.hasChild("combat-protection") ? rootNode.node("combat-protection") : rootNode;
        boolean teleportOnPvp = combatNode.node("teleport-on-pvp-enable").getBoolean(DEFAULT_TELEPORT_ON_PVP);
        boolean immunePvpTeleport =
                combatNode.node("immune-to-pvp-when-teleport").getBoolean(DEFAULT_IMMUNE_PVP_TELEPORT);
        int pvpInvulnSec = 10;
        ConfigurationNode invulnNode = combatNode.hasChild("pvp-teleport-invulnerability-seconds")
                ? combatNode.node("pvp-teleport-invulnerability-seconds")
                : combatNode.node("pvp-teleport-invulnerability");
        if (!invulnNode.virtual() && invulnNode.raw() != null) {
            String raw = invulnNode.getString("10").replace("s", "").trim();
            try {
                pvpInvulnSec = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                pvpInvulnSec = 10;
            }
        }
        boolean stopBorder = combatNode.node("stop-border-crossing").getBoolean(DEFAULT_STOP_BORDER_CROSSING);

        ConfigurationNode perfNode =
                rootNode.hasChild("performance-optimization") ? rootNode.node("performance-optimization") : rootNode;
        boolean disableRedstoneOffline =
                perfNode.node("disable-redstone-offline").getBoolean(DEFAULT_DISABLE_REDSTONE_OFFLINE);
        boolean afkSpawning =
                perfNode.node("afk-integrations", "disable-spawning").getBoolean(DEFAULT_AFK_SPAWNING);
        boolean afkRedstone =
                perfNode.node("afk-integrations", "disable-redstone").getBoolean(DEFAULT_AFK_REDSTONE);
        boolean netherRoof = perfNode.node("nether-roof").getBoolean(DEFAULT_NETHER_ROOF);

        ConfigurationNode econNode =
                rootNode.hasChild("economy-mechanics") ? rootNode.node("economy-mechanics") : rootNode;
        String syncWorth = econNode.node("sync-worth-with-shop").getString(DEFAULT_SYNC_WORTH);
        boolean negativeLevel = econNode.node("negative-level-allowed").getBoolean(DEFAULT_NEGATIVE_LEVEL);
        boolean dragonFight = econNode.node("end-dragon-fight", "enabled").getBoolean(DEFAULT_DRAGON_FIGHT);

        return new SettingsConfiguration(
                obsidianToLava,
                voidMembers,
                voidVisitors,
                defaultCmdAction,
                namesEnabled,
                namesMin,
                namesMax,
                Duration.ofSeconds(warmupSec),
                teleportOnPvp,
                immunePvpTeleport,
                Duration.ofSeconds(pvpInvulnSec),
                stopBorder,
                disableRedstoneOffline,
                afkSpawning,
                afkRedstone,
                netherRoof,
                syncWorth,
                negativeLevel,
                dragonFight);
    }
}
