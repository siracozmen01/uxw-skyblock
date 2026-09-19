package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for island bank and upkeep failure policy (Section 2.39).
 */
public record BankConfiguration(IslandUpkeepPolicy upkeepPolicy) {

    public static final boolean DEFAULT_ENABLED = false;
    public static final Duration DEFAULT_INTERVAL = Duration.ofHours(24);
    public static final long DEFAULT_BASE_FEE_MINOR = 50_000L;
    public static final long DEFAULT_PER_MEMBER_FEE_MINOR = 10_000L;
    public static final Duration DEFAULT_GRACE_DURATION = Duration.ofDays(3);
    public static final boolean DEFAULT_AUTO_REMEDIATE = true;

    public BankConfiguration {
        Objects.requireNonNull(upkeepPolicy, "upkeepPolicy must not be null");
    }

    public static BankConfiguration defaultConfiguration() {
        return new BankConfiguration(IslandUpkeepPolicy.defaultPolicy());
    }

    public static BankConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode upkeepNode = rootNode.node("bank", "upkeep");
        if (upkeepNode.virtual() || upkeepNode.empty()) {
            return defaultConfiguration();
        }

        boolean enabled = upkeepNode.node("enabled").getBoolean(DEFAULT_ENABLED);

        String intervalRaw = upkeepNode.node("interval").getString();
        Duration interval =
                intervalRaw != null && !intervalRaw.isBlank() ? Durations.parse(intervalRaw) : DEFAULT_INTERVAL;

        double baseFee = upkeepNode.node("base-fee").getDouble(500.00);
        long baseFeeMinor = Math.round(baseFee * 100.0);

        double perMemberFee = upkeepNode.node("per-member-fee").getDouble(100.00);
        long perMemberFeeMinor = Math.round(perMemberFee * 100.0);

        String graceRaw = upkeepNode.node("grace-duration").getString();
        Duration graceDuration =
                graceRaw != null && !graceRaw.isBlank() ? Durations.parse(graceRaw) : DEFAULT_GRACE_DURATION;

        boolean autoRemediate = upkeepNode.node("auto-remediate-on-deposit").getBoolean(DEFAULT_AUTO_REMEDIATE);

        IslandUpkeepPolicy policy = new IslandUpkeepPolicy(
                enabled, interval, baseFeeMinor, perMemberFeeMinor, graceDuration, autoRemediate);

        return new BankConfiguration(policy);
    }
}
