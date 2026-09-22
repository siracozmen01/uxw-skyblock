package com.uxplima.uxmskyblock.core.application.profile;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;

/**
 * Which ruleset a profile plays under, answered without going to the database every time.
 *
 * <p>Every block a player who is not a member touches asks this, so a query per click is the defect
 * this plugin's own standards name first. A profile's ruleset is decided when the profile is made
 * and never changes afterwards, so the answer is kept for as long as the server runs rather than
 * for a window: there is nothing to go stale.
 *
 * <p>What is kept is one enum per profile the server has seen, which is bounded by the players who
 * have joined it.
 */
public final class ProfileTypes {

    private final ProfileTypePort port;
    private final ConcurrentMap<ProfileId, ProfileType> known = new ConcurrentHashMap<>();

    public ProfileTypes(ProfileTypePort port) {
        this.port = Objects.requireNonNull(port, "port must not be null");
    }

    /** The ruleset this profile plays under, read once and remembered. */
    public ProfileType of(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return known.computeIfAbsent(profileId, port::typeOf);
    }

    /** Forgets one profile, for a profile that has been deleted. */
    public void forget(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        known.remove(profileId);
    }

    /** How many profiles are held, for a test that wants to see the read happen once. */
    public int held() {
        return known.size();
    }
}
