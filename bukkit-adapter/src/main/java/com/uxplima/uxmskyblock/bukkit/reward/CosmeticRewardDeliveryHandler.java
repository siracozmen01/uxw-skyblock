package com.uxplima.uxmskyblock.bukkit.reward;

import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import org.jspecify.annotations.Nullable;

/**
 * Production reward delivery handler for cosmetics.
 *
 * <p>Enforces durable storage of cosmetic unlocks via {@link ProfileCosmeticStoragePort}.
 * Never returns durable success for ephemeral or JVM-only state.
 */
public final class CosmeticRewardDeliveryHandler implements RewardDeliveryHandler {

    private final ProfileCosmeticStoragePort cosmeticStoragePort;

    public CosmeticRewardDeliveryHandler(ProfileCosmeticStoragePort cosmeticStoragePort) {
        this.cosmeticStoragePort = Objects.requireNonNull(cosmeticStoragePort, "cosmeticStoragePort must not be null");
    }

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

        try {
            String grantor = grant.sourceType() != null ? grant.sourceType() : "REWARD_INBOX";
            cosmeticStoragePort.grantCosmetic(recipient, cosmeticId, grantor);
            return DeliveryResult.success(component.componentOperationId().value());
        } catch (Exception e) {
            return DeliveryResult.failure("Failed to durably persist cosmetic grant: " + e.getMessage());
        }
    }

    public boolean hasCosmetic(ProfileId profileId, String cosmeticId) {
        return cosmeticStoragePort.hasCosmetic(profileId, cosmeticId);
    }

    public Set<String> getCosmetics(ProfileId profileId) {
        return cosmeticStoragePort.getCosmetics(profileId);
    }

    private @Nullable String parseCosmeticId(@Nullable String payload) {
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
