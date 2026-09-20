package com.uxplima.uxmskyblock.core.application.season;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.reward.RewardDraftComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;

/**
 * Application specification for season tier placement rewards.
 *
 * @param rank target competitive leaderboard placement (1-indexed)
 * @param components list of typed reward draft components to issue
 */
public record SeasonRewardDefinition(int rank, List<RewardDraftComponent> components) {

    public SeasonRewardDefinition {
        if (rank <= 0) {
            throw new IllegalArgumentException("rank must be positive: " + rank);
        }
        Objects.requireNonNull(components, "components must not be null");
        components = List.copyOf(components);
    }

    public static SeasonRewardDefinition of(int rank, List<RewardDraftComponent> components) {
        return new SeasonRewardDefinition(rank, components);
    }

    public static SeasonRewardDefinition sqlCurrency(int rank, String currency, long amount) {
        return new SeasonRewardDefinition(
                rank,
                List.of(new RewardDraftComponent(
                        RewardComponentType.SQL_CURRENCY,
                        "uxm:currency_deposit",
                        1,
                        "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}")));
    }

    public static SeasonRewardDefinition externalVault(int rank, double amount) {
        return new SeasonRewardDefinition(
                rank,
                List.of(new RewardDraftComponent(
                        RewardComponentType.EXTERNAL_VAULT, "uxm:vault_deposit", 1, "{\"amount\":" + amount + "}")));
    }

    public static SeasonRewardDefinition item(int rank, String item, int amount) {
        return new SeasonRewardDefinition(
                rank,
                List.of(new RewardDraftComponent(
                        RewardComponentType.ITEM,
                        "uxm:item_bundle",
                        1,
                        "{\"item\":\"" + item + "\",\"amount\":" + amount + "}")));
    }

    public static SeasonRewardDefinition permission(int rank, String permission) {
        return new SeasonRewardDefinition(
                rank,
                List.of(new RewardDraftComponent(
                        RewardComponentType.PERMISSION,
                        "uxm:permission",
                        1,
                        "{\"permission\":\"" + permission + "\"}")));
    }

    public static SeasonRewardDefinition cosmetic(int rank, String cosmeticId) {
        return new SeasonRewardDefinition(
                rank,
                List.of(new RewardDraftComponent(
                        RewardComponentType.COSMETIC, "uxm:cosmetic", 1, "{\"cosmeticId\":\"" + cosmeticId + "\"}")));
    }
}
