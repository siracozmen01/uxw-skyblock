package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;

/**
 * Production reward delivery handler for cosmetics.
 *
 * <p>Tracks unlocked cosmetic tags and features for player profiles.
 */
public final class CosmeticRewardDeliveryHandler implements RewardDeliveryHandler {

    private final ConcurrentHashMap<ProfileId, Set<String>> unlockedCosmetics = new ConcurrentHashMap<>();

    @Override
    public RewardComponentType supportedType() {
        return RewardComponentType.COSMETIC;
    }

    @Override
    public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");

        String cosmeticId = parseCosmeticId(component.payloadData());
        if (cosmeticId == null || cosmeticId.isBlank()) {
            return DeliveryResult.failure("Invalid cosmetic payload: " + component.payloadData());
        }

        unlockedCosmetics
                .computeIfAbsent(recipient, k -> ConcurrentHashMap.newKeySet())
                .add(cosmeticId);

        return DeliveryResult.success(component.componentOperationId().value());
    }

    public boolean hasCosmetic(ProfileId profileId, String cosmeticId) {
        Set<String> cosmetics = unlockedCosmetics.get(profileId);
        return cosmetics != null && cosmetics.contains(cosmeticId);
    }

    private String parseCosmeticId(String payload) {
        if (payload == null) return null;
        String clean =
                payload.replace("{", "").replace("}", "").replace("\"", "").trim();
        int idx = clean.indexOf("cosmeticId:");
        if (idx < 0) {
            idx = clean.indexOf("cosmetic:");
        }
        if (idx < 0) return clean.isEmpty() ? null : clean;
        int prefixLen = clean.startsWith("cosmeticId:", idx) ? 11 : 9;
        int end = clean.indexOf(",", idx);
        if (end < 0) end = clean.length();
        return clean.substring(idx + prefixLen, end).trim();
    }
}
