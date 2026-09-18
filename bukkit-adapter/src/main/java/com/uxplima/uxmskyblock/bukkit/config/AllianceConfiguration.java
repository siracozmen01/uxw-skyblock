package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island alliances, diplomatic limits, timeouts, and privileges.
 */
public record AllianceConfiguration(
        boolean enabled,
        int maxAllies,
        Duration inviteTimeout,
        boolean friendlyFireShielding,
        boolean privilegedVisitAccess,
        boolean allianceChatEnabled) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_MAX_ALLIES = 2;
    public static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofMinutes(5);
    public static final boolean DEFAULT_FRIENDLY_FIRE_SHIELDING = true;
    public static final boolean DEFAULT_PRIVILEGED_VISIT_ACCESS = true;
    public static final boolean DEFAULT_ALLIANCE_CHAT_ENABLED = true;

    public AllianceConfiguration {
        Objects.requireNonNull(inviteTimeout, "inviteTimeout must not be null");
        if (maxAllies <= 0) {
            throw new IllegalArgumentException("maxAllies must be positive: " + maxAllies);
        }
        if (inviteTimeout.isNegative() || inviteTimeout.isZero()) {
            throw new IllegalArgumentException("inviteTimeout must be positive: " + inviteTimeout);
        }
    }

    public static AllianceConfiguration defaultConfiguration() {
        return new AllianceConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_MAX_ALLIES,
                DEFAULT_INVITE_TIMEOUT,
                DEFAULT_FRIENDLY_FIRE_SHIELDING,
                DEFAULT_PRIVILEGED_VISIT_ACCESS,
                DEFAULT_ALLIANCE_CHAT_ENABLED);
    }

    public static AllianceConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("alliances");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);
        int maxAllies = node.node("max-allies").getInt(DEFAULT_MAX_ALLIES);

        String timeoutRaw = node.node("invite-timeout").getString();
        Duration inviteTimeout = timeoutRaw != null && !timeoutRaw.isBlank()
                ? Durations.parse(timeoutRaw)
                : Duration.ofSeconds(node.node("invite-timeout-seconds").getInt(300));

        boolean friendlyFire = node.node("friendly-fire-shielding").getBoolean(DEFAULT_FRIENDLY_FIRE_SHIELDING);
        boolean privilegedVisit = node.node("privileged-visit-access").getBoolean(DEFAULT_PRIVILEGED_VISIT_ACCESS);
        boolean allianceChat = node.node("alliance-chat-enabled").getBoolean(DEFAULT_ALLIANCE_CHAT_ENABLED);

        return new AllianceConfiguration(
                enabled, maxAllies, inviteTimeout, friendlyFire, privilegedVisit, allianceChat);
    }
}
