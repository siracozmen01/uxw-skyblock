package com.uxplima.uxmskyblock.bukkit.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.discord.DiscordEmbedTexts;
import com.uxplima.uxmskyblock.core.domain.discord.DiscordTopic;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for standalone Discord webhook notifications,
 * topic endpoints, and rate-limiting parameters.
 */
public record DiscordConfiguration(
        boolean enabled,
        String botUsername,
        @Nullable String avatarUrl,
        double rateLimitPerSecond,
        Map<DiscordTopic, String> webhookUrls,
        DiscordEmbedTexts embeds) {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(DiscordConfiguration.class.getName());

    public static final String DEFAULT_BOT_USERNAME = "UXPLIMA Skyblock";
    public static final double DEFAULT_RATE_LIMIT = 2.0;

    public DiscordConfiguration {
        Objects.requireNonNull(botUsername, "botUsername must not be null");
        Objects.requireNonNull(webhookUrls, "webhookUrls must not be null");
        webhookUrls = Map.copyOf(webhookUrls);
        Objects.requireNonNull(embeds, "embeds must not be null");
    }

    /** A configuration whose embeds say what the plugin shipped with. */
    public DiscordConfiguration(
            boolean enabled,
            String botUsername,
            @Nullable String avatarUrl,
            double rateLimitPerSecond,
            Map<DiscordTopic, String> webhookUrls) {
        this(enabled, botUsername, avatarUrl, rateLimitPerSecond, webhookUrls, DiscordEmbedTexts.english());
    }

    public static DiscordConfiguration defaultConfiguration() {
        return new DiscordConfiguration(false, DEFAULT_BOT_USERNAME, null, DEFAULT_RATE_LIMIT, Map.of());
    }

    public static DiscordConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("discord");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(false);
        String botUsername = node.node("bot-username").getString(DEFAULT_BOT_USERNAME);
        String avatarUrl = node.node("avatar-url").getString();
        if (avatarUrl != null && avatarUrl.isBlank()) {
            avatarUrl = null;
        }

        double rateLimit = node.node("rate-limit-per-second").getDouble(DEFAULT_RATE_LIMIT);

        Map<DiscordTopic, String> urls = new EnumMap<>(DiscordTopic.class);
        ConfigurationNode webhooksNode = node.node("webhooks");
        if (!webhooksNode.virtual() && !webhooksNode.empty()) {
            putUrlIfPresent(
                    urls,
                    DiscordTopic.MILESTONES,
                    webhooksNode.node("milestones").getString());
            putUrlIfPresent(
                    urls,
                    DiscordTopic.LEADERBOARDS,
                    webhooksNode.node("leaderboards").getString());
            putUrlIfPresent(
                    urls, DiscordTopic.ALLIANCES, webhooksNode.node("alliances").getString());
            putUrlIfPresent(
                    urls,
                    DiscordTopic.ADMIN_AUDIT,
                    webhooksNode.node("admin-audit").getString());
        }

        return new DiscordConfiguration(
                enabled, botUsername, avatarUrl, rateLimit, urls, loadEmbeds(node.node("embeds")));
    }

    /**
     * What each embed says. A key the file leaves out keeps the text the plugin shipped with, so an
     * operator who only wants to change one title writes one line.
     */
    static DiscordEmbedTexts loadEmbeds(ConfigurationNode node) {
        DiscordEmbedTexts shipped = DiscordEmbedTexts.english();
        DiscordEmbedTexts.Milestone m = shipped.milestone();
        ConfigurationNode mn = node.node("milestone");
        DiscordEmbedTexts.Leaderboard l = shipped.leaderboard();
        ConfigurationNode ln = node.node("leaderboard");
        DiscordEmbedTexts.Alliance a = shipped.alliance();
        ConfigurationNode an = node.node("alliance");
        DiscordEmbedTexts.Audit u = shipped.audit();
        ConfigurationNode un = node.node("audit");
        return new DiscordEmbedTexts(
                new DiscordEmbedTexts.Milestone(
                        text(mn, "title", m.title()),
                        text(mn, "description", m.description()),
                        text(mn, "leader-field", m.leaderField()),
                        text(mn, "level-field", m.levelField()),
                        text(mn, "members-field", m.membersField()),
                        text(mn, "footer", m.footer()),
                        color(mn, "color", m.color())),
                new DiscordEmbedTexts.Leaderboard(
                        text(ln, "title", l.title()),
                        text(ln, "line", l.line()),
                        text(ln, "first-place", l.firstPlace()),
                        text(ln, "second-place", l.secondPlace()),
                        text(ln, "third-place", l.thirdPlace()),
                        text(ln, "other-place", l.otherPlace()),
                        text(ln, "empty", l.empty()),
                        text(ln, "footer", l.footer()),
                        color(ln, "color", l.color())),
                new DiscordEmbedTexts.Alliance(
                        text(an, "title", a.title()),
                        text(an, "description", a.description()),
                        text(an, "alliance-field", a.allianceField()),
                        text(an, "actor-field", a.actorField()),
                        text(an, "target-field", a.targetField()),
                        text(an, "footer", a.footer()),
                        color(an, "color", a.color())),
                new DiscordEmbedTexts.Audit(
                        text(un, "title", u.title()),
                        text(un, "footer", u.footer()),
                        color(un, "high-color", u.highColor()),
                        color(un, "medium-color", u.mediumColor()),
                        color(un, "low-color", u.lowColor())));
    }

    private static String text(ConfigurationNode parent, String key, String shipped) {
        String written = parent.node(key).getString();
        return written != null ? written : shipped;
    }

    /** A colour written as {@code #RRGGBB}. Anything else keeps the shipped colour and says so. */
    private static int color(ConfigurationNode parent, String key, int shipped) {
        String written = parent.node(key).getString();
        if (written == null) {
            return shipped;
        }
        String hex = written.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() == 6) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                // Falls through to the warning below.
            }
        }
        LOGGER.warning(() -> "discord.embeds: '" + written + "' at " + key
                + " is not a #RRGGBB colour, so the shipped colour is used.");
        return shipped;
    }

    private static void putUrlIfPresent(Map<DiscordTopic, String> map, DiscordTopic topic, @Nullable String url) {
        if (url != null && !url.isBlank()) {
            map.put(topic, url.trim());
        }
    }
}
