package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.inactivity.AbandonmentAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.FormerOwnerAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Platform configuration holder for leader inactivity thresholds, succession hierarchy,
 * and team abandonment lifecycle.
 */
public record InactivityConfiguration(
        boolean enabled,
        Duration checkInterval,
        Duration ownerInactivityDuration,
        Duration allMembersInactivityDuration,
        List<IslandRole> successionHierarchy,
        FormerOwnerAction formerOwnerAction,
        AbandonmentAction abandonmentAction) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofDays(1);
    public static final Duration DEFAULT_OWNER_INACTIVITY = Duration.ofDays(30);
    public static final Duration DEFAULT_ALL_MEMBERS_INACTIVITY = Duration.ofDays(60);
    public static final List<IslandRole> DEFAULT_SUCCESSION_HIERARCHY =
            List.of(IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER);
    public static final FormerOwnerAction DEFAULT_FORMER_OWNER_ACTION = FormerOwnerAction.DEMOTE_TO_CO_OWNER;
    public static final AbandonmentAction DEFAULT_ABANDONMENT_ACTION = AbandonmentAction.ARCHIVE;

    public InactivityConfiguration {
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        Objects.requireNonNull(ownerInactivityDuration, "ownerInactivityDuration must not be null");
        Objects.requireNonNull(allMembersInactivityDuration, "allMembersInactivityDuration must not be null");
        Objects.requireNonNull(successionHierarchy, "successionHierarchy must not be null");
        Objects.requireNonNull(formerOwnerAction, "formerOwnerAction must not be null");
        Objects.requireNonNull(abandonmentAction, "abandonmentAction must not be null");

        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be strictly positive");
        }
        if (ownerInactivityDuration.isNegative() || ownerInactivityDuration.isZero()) {
            throw new IllegalArgumentException("ownerInactivityDuration must be strictly positive");
        }
        if (allMembersInactivityDuration.isNegative() || allMembersInactivityDuration.isZero()) {
            throw new IllegalArgumentException("allMembersInactivityDuration must be strictly positive");
        }
        if (allMembersInactivityDuration.compareTo(ownerInactivityDuration) < 0) {
            throw new IllegalArgumentException(
                    "allMembersInactivityDuration cannot be shorter than ownerInactivityDuration");
        }

        successionHierarchy = Collections.unmodifiableList(List.copyOf(successionHierarchy));
    }

    public InactivityPolicy toPolicy() {
        return new InactivityPolicy(
                enabled,
                ownerInactivityDuration,
                allMembersInactivityDuration,
                successionHierarchy,
                formerOwnerAction,
                abandonmentAction);
    }

    public static InactivityConfiguration defaultConfiguration() {
        return new InactivityConfiguration(
                DEFAULT_ENABLED,
                DEFAULT_CHECK_INTERVAL,
                DEFAULT_OWNER_INACTIVITY,
                DEFAULT_ALL_MEMBERS_INACTIVITY,
                DEFAULT_SUCCESSION_HIERARCHY,
                DEFAULT_FORMER_OWNER_ACTION,
                DEFAULT_ABANDONMENT_ACTION);
    }

    public static InactivityConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("inactivity");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = node.node("enabled").getBoolean(DEFAULT_ENABLED);

        Duration checkInterval = parseDuration(node.node("check-interval-seconds"), DEFAULT_CHECK_INTERVAL);
        Duration ownerInactivity = parseDuration(node.node("owner-inactivity-duration"), DEFAULT_OWNER_INACTIVITY);
        Duration allMembersInactivity =
                parseDuration(node.node("all-members-inactivity-duration"), DEFAULT_ALL_MEMBERS_INACTIVITY);

        List<IslandRole> hierarchy = new ArrayList<>();
        ConfigurationNode hierarchyNode = node.node("succession-hierarchy");
        if (hierarchyNode.isList()) {
            for (ConfigurationNode child : hierarchyNode.childrenList()) {
                String roleName = child.getString();
                if (roleName != null) {
                    IslandRole.byId(roleName).ifPresent(hierarchy::add);
                }
            }
        }
        if (hierarchy.isEmpty()) {
            hierarchy = DEFAULT_SUCCESSION_HIERARCHY;
        }

        String formerOwnerRaw = node.node("former-owner-action").getString("DEMOTE_TO_CO_OWNER");
        FormerOwnerAction formerOwnerAction;
        try {
            formerOwnerAction = FormerOwnerAction.valueOf(formerOwnerRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            formerOwnerAction = DEFAULT_FORMER_OWNER_ACTION;
        }

        String abandonmentRaw = node.node("abandonment-action").getString("ARCHIVE");
        AbandonmentAction abandonmentAction;
        try {
            abandonmentAction = AbandonmentAction.valueOf(abandonmentRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            abandonmentAction = DEFAULT_ABANDONMENT_ACTION;
        }

        return new InactivityConfiguration(
                enabled,
                checkInterval,
                ownerInactivity,
                allMembersInactivity,
                hierarchy,
                formerOwnerAction,
                abandonmentAction);
    }

    private static Duration parseDuration(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        if (raw.matches("^\\d+$")) {
            return Duration.ofSeconds(Long.parseLong(raw));
        }
        try {
            return Durations.parse(raw);
        } catch (Exception e) {
            return fallback;
        }
    }
}
