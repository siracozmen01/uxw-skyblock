package com.uxplima.uxmskyblock.core.application.inactivity;

import java.time.Instant;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Port providing the last known activity or login timestamp for a player profile.
 */
public interface PlayerActivityProvider {

    /**
     * Retrieves the last recorded active timestamp for the specified player.
     *
     * @param playerUuid player account UUID
     * @param profileId player profile ID
     * @return optional containing the last active timestamp if known
     */
    Optional<Instant> getLastActive(PlayerUuid playerUuid, ProfileId profileId);
}
