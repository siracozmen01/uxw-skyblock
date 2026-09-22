package com.uxplima.uxmskyblock.core.application.profile;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;

/**
 * Which ruleset a profile plays under.
 *
 * <p>The column has been written since the first migration and nothing ever read it back, so every
 * check that asks a profile's type was told CLASSIC. The Ironman barrier, which the documents call
 * an invariant, was therefore inert: it could not refuse anything because it never saw a profile
 * that was not classic.
 */
public interface ProfileTypePort {

    /**
     * The ruleset this profile plays under.
     *
     * <p>A profile nobody has heard of is classic. A profile whose stored type is a word this build
     * does not know is classic too: refusing to let a player move because a newer build wrote a
     * ruleset name into their row is worse than treating them as the ordinary case.
     */
    ProfileType typeOf(ProfileId profileId);
}
