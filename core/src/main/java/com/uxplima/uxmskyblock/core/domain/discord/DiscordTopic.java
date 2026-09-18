package com.uxplima.uxmskyblock.core.domain.discord;

import java.util.Objects;

/**
 * Standard categorized notification topics for Discord webhooks.
 */
public enum DiscordTopic {
    MILESTONES("milestones"),
    LEADERBOARDS("leaderboards"),
    ALLIANCES("alliances"),
    ADMIN_AUDIT("admin_audit");

    private final String key;

    DiscordTopic(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static DiscordTopic fromKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        for (DiscordTopic topic : values()) {
            if (topic.key.equalsIgnoreCase(key) || topic.name().equalsIgnoreCase(key)) {
                return topic;
            }
        }
        throw new IllegalArgumentException("Unknown DiscordTopic: " + key);
    }
}
