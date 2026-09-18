package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island private chat styling, spy formatting, and rate limiting.
 */
public record ChatConfiguration(boolean enabled, String format, String spyFormat, int rateLimitMessagesPerSecond) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_FORMAT =
            "<dark_gray>[<aqua>Island Chat<dark_gray>] <gray>[<green><role><gray>] <yellow><player><white>: <message>";
    public static final String DEFAULT_SPY_FORMAT =
            "<dark_gray>[<red>SPY<dark_gray>] <dark_gray>[<aqua><island_name><dark_gray>] <gray>[<green><role><gray>] <yellow><player><white>: <message>";
    public static final int DEFAULT_RATE_LIMIT = 5;

    public ChatConfiguration {
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(spyFormat, "spyFormat must not be null");
        if (format.isBlank()) {
            throw new IllegalArgumentException("format cannot be blank");
        }
        if (spyFormat.isBlank()) {
            throw new IllegalArgumentException("spyFormat cannot be blank");
        }
        if (rateLimitMessagesPerSecond < 0) {
            throw new IllegalArgumentException("rateLimitMessagesPerSecond cannot be negative");
        }
    }

    public static ChatConfiguration defaultConfiguration() {
        return new ChatConfiguration(DEFAULT_ENABLED, DEFAULT_FORMAT, DEFAULT_SPY_FORMAT, DEFAULT_RATE_LIMIT);
    }

    public static ChatConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("chat");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        String format = node.node("format").getString(DEFAULT_FORMAT);
        String spyFormat = node.node("spy-format").getString(DEFAULT_SPY_FORMAT);
        int rateLimit = node.node("rate-limit-messages-per-second").getInt(DEFAULT_RATE_LIMIT);

        return new ChatConfiguration(enabled, format, spyFormat, rateLimit);
    }
}
