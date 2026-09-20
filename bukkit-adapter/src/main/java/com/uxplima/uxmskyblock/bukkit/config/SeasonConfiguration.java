package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import org.jspecify.annotations.Nullable;
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
        Map<Integer, List<String>> tierRewards,
        Map<Integer, List<RewardDraftComponent>> typedTierRewards) {

    public static final int DEFAULT_SEASON_NUMBER = 1;
    public static final String DEFAULT_SEASON_NAME = "Season 1 - Genesis";
    public static final Duration DEFAULT_DURATION = Duration.ofDays(30);
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(60);

    public SeasonConfiguration(
            int seasonNumber,
            String seasonName,
            Duration duration,
            Duration checkInterval,
            Map<Integer, List<String>> tierRewards) {
        this(seasonNumber, seasonName, duration, checkInterval, tierRewards, parseTierRewardsToDrafts(tierRewards));
    }

    public SeasonConfiguration {
        Objects.requireNonNull(seasonName, "seasonName must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        Objects.requireNonNull(tierRewards, "tierRewards must not be null");
        Objects.requireNonNull(typedTierRewards, "typedTierRewards must not be null");
        if (seasonNumber <= 0) {
            throw new IllegalArgumentException("seasonNumber must be positive: " + seasonNumber);
        }
        tierRewards = Map.copyOf(tierRewards);
        typedTierRewards = Map.copyOf(typedTierRewards);
    }

    public static SeasonConfiguration defaultConfiguration() {
        Map<Integer, List<String>> defaultRewards = Map.of(
                1, List.of("eco give %player% 1000000"),
                2, List.of("eco give %player% 500000"),
                3, List.of("eco give %player% 250000"));
        return new SeasonConfiguration(
                DEFAULT_SEASON_NUMBER,
                DEFAULT_SEASON_NAME,
                DEFAULT_DURATION,
                DEFAULT_CHECK_INTERVAL,
                defaultRewards,
                parseTierRewardsToDrafts(defaultRewards));
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
        Map<Integer, List<RewardDraftComponent>> typedRewards = new HashMap<>();
        ConfigurationNode rewardsNode = node.node("rewards");
        if (!rewardsNode.virtual() && rewardsNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                    rewardsNode.childrenMap().entrySet()) {
                try {
                    int rank = Integer.parseInt(String.valueOf(entry.getKey()).trim());
                    List<String> actions = new ArrayList<>();
                    List<RewardDraftComponent> drafts = new ArrayList<>();

                    for (ConfigurationNode child : entry.getValue().childrenList()) {
                        if (child.isMap()) {
                            RewardDraftComponent draft = parseTypedNode(child);
                            if (draft != null) {
                                drafts.add(draft);
                                actions.add("reward:" + draft.componentType().name() + ":" + draft.payloadData());
                            }
                        } else {
                            String action = child.getString();
                            if (action != null && !action.isBlank()) {
                                actions.add(action);
                                RewardDraftComponent draft = parseRewardAction(action);
                                if (draft != null) {
                                    drafts.add(draft);
                                }
                            }
                        }
                    }

                    if (!actions.isEmpty()) {
                        rewards.put(rank, List.copyOf(actions));
                    }
                    if (!drafts.isEmpty()) {
                        typedRewards.put(rank, List.copyOf(drafts));
                    }
                } catch (NumberFormatException expected) {
                    // Ignore non-numeric rank keys
                }
            }
        }

        if (rewards.isEmpty()) {
            return defaultConfiguration();
        }

        return new SeasonConfiguration(number, name, duration, checkInterval, rewards, typedRewards);
    }

    private static @Nullable RewardDraftComponent parseTypedNode(ConfigurationNode node) {
        String typeStr = node.node("type").getString();
        if (typeStr == null || typeStr.isBlank()) {
            return null;
        }
        try {
            RewardComponentType type =
                    RewardComponentType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
            return switch (type) {
                case SQL_CURRENCY -> {
                    long amount = node.node("amount").getLong(0L);
                    String currency = node.node("currency").getString("PRIMARY");
                    yield new RewardDraftComponent(
                            RewardComponentType.SQL_CURRENCY,
                            "uxm:currency_deposit",
                            1,
                            "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}");
                }
                case EXTERNAL_VAULT -> {
                    double amount = node.node("amount").getDouble(0.0);
                    yield new RewardDraftComponent(
                            RewardComponentType.EXTERNAL_VAULT, "uxm:vault_deposit", 1, "{\"amount\":" + amount + "}");
                }
                case ITEM -> {
                    String item = node.node("item").getString("DIAMOND");
                    int amount = node.node("amount").getInt(1);
                    yield new RewardDraftComponent(
                            RewardComponentType.ITEM,
                            "uxm:item_bundle",
                            1,
                            "{\"item\":\"" + item + "\",\"amount\":" + amount + "}");
                }
                case PERMISSION -> {
                    String perm = node.node("permission").getString();
                    yield perm != null
                            ? new RewardDraftComponent(
                                    RewardComponentType.PERMISSION,
                                    "uxm:permission",
                                    1,
                                    "{\"permission\":\"" + perm + "\"}")
                            : null;
                }
                case COSMETIC -> {
                    String cosmeticId = node.node("cosmetic-id").getString();
                    if (cosmeticId == null) {
                        cosmeticId = node.node("cosmeticId").getString();
                    }
                    yield cosmeticId != null
                            ? new RewardDraftComponent(
                                    RewardComponentType.COSMETIC,
                                    "uxm:cosmetic",
                                    1,
                                    "{\"cosmeticId\":\"" + cosmeticId + "\"}")
                            : null;
                }
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<Integer, List<RewardDraftComponent>> parseTierRewardsToDrafts(
            Map<Integer, List<String>> tierRewards) {
        Map<Integer, List<RewardDraftComponent>> result = new HashMap<>();
        for (Map.Entry<Integer, List<String>> entry : tierRewards.entrySet()) {
            List<RewardDraftComponent> drafts = new ArrayList<>();
            for (String action : entry.getValue()) {
                RewardDraftComponent draft = parseRewardAction(action);
                if (draft != null) {
                    drafts.add(draft);
                }
            }
            if (!drafts.isEmpty()) {
                result.put(entry.getKey(), List.copyOf(drafts));
            }
        }
        return Map.copyOf(result);
    }

    private static @Nullable RewardDraftComponent parseRewardAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        String trimmed = action.trim();
        if (trimmed.startsWith("eco give ")) {
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 8) {
                String amountStr = trimmed.substring(lastSpace + 1).trim();
                try {
                    long amount = Long.parseLong(amountStr);
                    return new RewardDraftComponent(
                            RewardComponentType.EXTERNAL_VAULT, "currency.vault", 1, "{\"amount\":" + amount + "}");
                } catch (NumberFormatException expected) {
                    return null;
                }
            }
        }
        return null;
    }
}
