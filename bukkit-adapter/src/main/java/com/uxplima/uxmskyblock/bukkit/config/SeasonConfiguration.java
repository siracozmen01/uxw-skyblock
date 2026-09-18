package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Configuration holder for automated seasons, cycle duration, evaluation intervals,
 * and competitive tier payouts.
 */
public record SeasonConfiguration(
        int seasonNumber,
        String seasonName,
        Duration duration,
        Duration checkInterval,
        Map<Integer, List<String>> tierRewards) {

    public static final int DEFAULT_SEASON_NUMBER = 1;
    public static final String DEFAULT_SEASON_NAME = "Season 1 - Genesis";
    public static final Duration DEFAULT_DURATION = Duration.ofDays(30);
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(60);

    public SeasonConfiguration {
        Objects.requireNonNull(seasonName, "seasonName must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        Objects.requireNonNull(tierRewards, "tierRewards must not be null");
        if (seasonNumber <= 0) {
            throw new IllegalArgumentException("seasonNumber must be positive: " + seasonNumber);
        }
        tierRewards = Map.copyOf(tierRewards);
    }

    public static SeasonConfiguration defaultConfiguration() {
        return new SeasonConfiguration(
                DEFAULT_SEASON_NUMBER,
                DEFAULT_SEASON_NAME,
                DEFAULT_DURATION,
                DEFAULT_CHECK_INTERVAL,
                Map.of(
                        1, List.of("eco give %player% 1000000"),
                        2, List.of("eco give %player% 500000"),
                        3, List.of("eco give %player% 250000")));
    }

    public static SeasonConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("seasons");
        if (node.virtual() || node.empty()) {
            return defaultConfiguration();
        }

        int number = node.node("season-number").getInt(DEFAULT_SEASON_NUMBER);
        String name = node.node("season-name").getString(DEFAULT_SEASON_NAME);

        String durRaw = node.node("duration").getString();
        Duration duration = durRaw != null && !durRaw.isBlank() ? Durations.parse(durRaw) : DEFAULT_DURATION;

        String intervalRaw = node.node("check-interval").getString();
        Duration checkInterval =
                intervalRaw != null && !intervalRaw.isBlank() ? Durations.parse(intervalRaw) : DEFAULT_CHECK_INTERVAL;

        Map<Integer, List<String>> rewards = new HashMap<>();
        ConfigurationNode rewardsNode = node.node("rewards");
        if (!rewardsNode.virtual() && rewardsNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    rewardsNode.childrenMap().entrySet()) {
                try {
                    int rank = Integer.parseInt(String.valueOf(entry.getKey()).trim());
                    List<String> actions = new ArrayList<>();
                    for (ConfigurationNode child : entry.getValue().childrenList()) {
                        String action = child.getString();
                        if (action != null && !action.isBlank()) {
                            actions.add(action);
                        }
                    }
                    if (!actions.isEmpty()) {
                        rewards.put(rank, List.copyOf(actions));
                    }
                } catch (NumberFormatException expected) {
                    // Ignore non-numeric rank keys
                }
            }
        }

        if (rewards.isEmpty()) {
            rewards.put(1, List.of("eco give %player% 1000000"));
            rewards.put(2, List.of("eco give %player% 500000"));
            rewards.put(3, List.of("eco give %player% 250000"));
        }

        return new SeasonConfiguration(number, name, duration, checkInterval, rewards);
    }
}
