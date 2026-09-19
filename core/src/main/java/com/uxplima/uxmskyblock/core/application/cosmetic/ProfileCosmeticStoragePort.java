package com.uxplima.uxmskyblock.core.application.cosmetic;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Outbound application port for persistent profile cosmetic entitlements.
 *
 * <p>Enforces durable storage of cosmetic unlocks according to GAMEMODE_ARCHITECTURE.md
 * Section 11.3, ensuring cosmetic grants persist across server restarts.
 */
public interface ProfileCosmeticStoragePort {

    /**
     * Durably records an unlocked cosmetic for the specified profile.
     *
     * @param profileId the recipient profile ID
     * @param cosmeticId the unique cosmetic identifier
     * @param grantedBy optional source or grantor descriptor
     */
    void grantCosmetic(ProfileId profileId, String cosmeticId, @Nullable String grantedBy);

    /**
     * Checks if the profile has unlocked the specified cosmetic.
     *
     * @param profileId the profile ID
     * @param cosmeticId the cosmetic identifier
     * @return true if unlocked, false otherwise
     */
    boolean hasCosmetic(ProfileId profileId, String cosmeticId);

    /**
     * Retrieves all cosmetics unlocked by the specified profile.
     *
     * @param profileId the profile ID
     * @return set of unlocked cosmetic IDs
     */
    Set<String> getCosmetics(ProfileId profileId);
}
