package com.uxplima.uxmskyblock.bukkit.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

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
        Map<DiscordTopic, String> webhookUrls) {

    public static final String DEFAULT_BOT_USERNAME = "UXPLIMA Skyblock";
    public static final double DEFAULT_RATE_LIMIT = 2.0;

    public DiscordConfiguration {
        Objects.requireNonNull(botUsername, "botUsername must not be null");
        Objects.requireNonNull(webhookUrls, "webhookUrls must not be null");
        webhookUrls = Map.copyOf(webhookUrls);
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

        return new DiscordConfiguration(enabled, botUsername, avatarUrl, rateLimit, urls);
    }

    private static void putUrlIfPresent(Map<DiscordTopic, String> map, DiscordTopic topic, @Nullable String url) {
        if (url != null && !url.isBlank()) {
            map.put(topic, url.trim());
        }
    }
}
