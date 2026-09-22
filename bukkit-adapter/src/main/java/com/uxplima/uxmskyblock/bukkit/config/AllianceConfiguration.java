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
        boolean allianceChatEnabled,
        Duration expiredInviteSweepInterval) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_MAX_ALLIES = 2;
    public static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofMinutes(5);
    public static final boolean DEFAULT_FRIENDLY_FIRE_SHIELDING = true;
    public static final boolean DEFAULT_PRIVILEGED_VISIT_ACCESS = true;
    public static final boolean DEFAULT_ALLIANCE_CHAT_ENABLED = true;

    /** How often the invites nobody answered are deleted, when the operator names no number. */
    public static final Duration DEFAULT_EXPIRED_INVITE_SWEEP_INTERVAL = Duration.ofMinutes(5);

    public AllianceConfiguration {
        Objects.requireNonNull(inviteTimeout, "inviteTimeout must not be null");
        Objects.requireNonNull(expiredInviteSweepInterval, "expiredInviteSweepInterval must not be null");
        if (expiredInviteSweepInterval.isNegative() || expiredInviteSweepInterval.isZero()) {
            throw new IllegalArgumentException(
                    "expiredInviteSweepInterval must be positive: " + expiredInviteSweepInterval);
        }
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
                DEFAULT_ALLIANCE_CHAT_ENABLED,
                DEFAULT_EXPIRED_INVITE_SWEEP_INTERVAL);
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

        String sweepRaw = node.node("expired-invite-sweep-interval").getString();
        Duration sweepInterval = sweepRaw != null && !sweepRaw.isBlank()
                ? Durations.parse(sweepRaw)
                : DEFAULT_EXPIRED_INVITE_SWEEP_INTERVAL;

        return new AllianceConfiguration(
                enabled, maxAllies, inviteTimeout, friendlyFire, privilegedVisit, allianceChat, sweepInterval);
    }
}
