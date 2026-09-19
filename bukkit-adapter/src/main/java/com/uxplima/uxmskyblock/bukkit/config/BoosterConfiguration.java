package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterCalculation;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterDurationPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterStackMode;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration record for island boosters and multipliers subsystem (Section 2.34).
 */
public record BoosterConfiguration(
        boolean pauseWhenEmpty, Duration cleanInterval, Map<BoosterCategory, CategoryBoosterPolicy> policies) {

    public static final boolean DEFAULT_PAUSE_WHEN_EMPTY = true;
    public static final Duration DEFAULT_CLEAN_INTERVAL = Duration.ofSeconds(60);

    public BoosterConfiguration {
        Objects.requireNonNull(cleanInterval, "cleanInterval must not be null");
        Objects.requireNonNull(policies, "policies must not be null");
        policies = Collections.unmodifiableMap(new EnumMap<>(policies));
    }

    public CategoryBoosterPolicy policy(BoosterCategory category) {
        Objects.requireNonNull(category, "category must not be null");
        CategoryBoosterPolicy policy = policies.get(category);
        return policy != null ? policy : CategoryBoosterPolicy.defaultFor(category);
    }

    public static BoosterConfiguration defaultConfiguration() {
        Map<BoosterCategory, CategoryBoosterPolicy> map = new EnumMap<>(BoosterCategory.class);
        for (BoosterCategory cat : BoosterCategory.values()) {
            map.put(cat, CategoryBoosterPolicy.defaultFor(cat));
        }
        return new BoosterConfiguration(DEFAULT_PAUSE_WHEN_EMPTY, DEFAULT_CLEAN_INTERVAL, map);
    }

    public static BoosterConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("boosters");
        if (node.virtual()) {
            return defaultConfiguration();
        }

        boolean pauseWhenEmpty = node.node("pause-when-empty").getBoolean(DEFAULT_PAUSE_WHEN_EMPTY);
        Duration cleanInterval = parseDuration(node.node("clean-interval"), DEFAULT_CLEAN_INTERVAL);

        ConfigurationNode categoriesNode = node.node("categories");
        Map<BoosterCategory, CategoryBoosterPolicy> map = new EnumMap<>(BoosterCategory.class);

        for (BoosterCategory category : BoosterCategory.values()) {
            ConfigurationNode catNode = categoriesNode.node(category.key());
            if (catNode.virtual()) {
                catNode = categoriesNode.node(category.name());
            }
            if (catNode.virtual()) {
                catNode = categoriesNode.node(category.key().replace('_', '-'));
            }
            if (catNode.virtual()) {
                map.put(category, CategoryBoosterPolicy.defaultFor(category));
                continue;
            }

            boolean enabled = catNode.node("enabled").getBoolean(true);
            BoosterStackMode stackMode = BoosterStackMode.parse(
                            catNode.node("stack-mode").getString("DURATION"))
                    .orElse(BoosterStackMode.DURATION);
            BoosterDurationPolicy durationPolicy = BoosterDurationPolicy.parse(
                            catNode.node("duration-policy").getString("INDEPENDENT"))
                    .orElse(BoosterDurationPolicy.INDEPENDENT);
            BoosterCalculation calculation = BoosterCalculation.parse(
                            catNode.node("calculation").getString("ADDITIVE"))
                    .orElse(BoosterCalculation.ADDITIVE);
            double maxMultiplier = catNode.node("max-multiplier").getDouble(5.0);
            Duration maxDuration = parseDuration(catNode.node("max-duration"), Duration.ofDays(7));
            double defaultMultiplier = catNode.node("default-multiplier").getDouble(1.5);
            Duration defaultDuration = parseDuration(catNode.node("default-duration"), Duration.ofHours(1));

            map.put(
                    category,
                    new CategoryBoosterPolicy(
                            category,
                            enabled,
                            stackMode,
                            durationPolicy,
                            calculation,
                            maxMultiplier,
                            maxDuration,
                            defaultMultiplier,
                            defaultDuration));
        }

        return new BoosterConfiguration(pauseWhenEmpty, cleanInterval, map);
    }

    private static Duration parseDuration(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        raw = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (raw.endsWith("d")) {
                long days = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofDays(days);
            } else if (raw.endsWith("h")) {
                long hours = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofHours(hours);
            } else if (raw.endsWith("m")) {
                long minutes = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofMinutes(minutes);
            } else if (raw.endsWith("s")) {
                long seconds = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return Duration.ofSeconds(seconds);
            }
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (Exception e) {
            return fallback;
        }
    }
}
