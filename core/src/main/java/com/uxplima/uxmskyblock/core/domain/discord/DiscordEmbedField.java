package com.uxplima.uxmskyblock.core.domain.discord;

import java.util.Objects;

/**
 * Immutable domain record representing an individual key-value field inside a Discord embed.
 */
public record DiscordEmbedField(String name, String value, boolean inline) {

    public DiscordEmbedField {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }

    public static DiscordEmbedField inline(String name, String value) {
        return new DiscordEmbedField(name, value, true);
    }

    public static DiscordEmbedField block(String name, String value) {
        return new DiscordEmbedField(name, value, false);
    }
}
