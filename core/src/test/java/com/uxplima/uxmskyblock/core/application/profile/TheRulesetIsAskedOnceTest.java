package com.uxplima.uxmskyblock.core.application.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A profile's ruleset is a query, and it is asked once.
 *
 * <p>Every block a player who is not a member touches asks which ruleset they play under, so a
 * query per click is the defect this plugin's own standards name first. A ruleset is decided when
 * the profile is made and never changes, so the answer is kept for as long as the server runs
 * rather than for a window: there is nothing to go stale.
 */
class TheRulesetIsAskedOnceTest {

    private static final ProfileId IRON = ProfileId.of(UUID.randomUUID());
    private static final ProfileId CLASSIC = ProfileId.of(UUID.randomUUID());

    /** Counts how often it was asked, which is the whole point. */
    private static final class CountingPort implements ProfileTypePort {
        final AtomicInteger asked = new AtomicInteger();
        final Map<ProfileId, ProfileType> stored = new HashMap<>();

        @Override
        public ProfileType typeOf(ProfileId profileId) {
            asked.incrementAndGet();
            return stored.getOrDefault(profileId, ProfileType.CLASSIC);
        }
    }

    @Test
    @DisplayName("A hundred asks about one profile are one query")
    void ahundredAsksAreOneQuery() {
        CountingPort port = new CountingPort();
        port.stored.put(IRON, ProfileType.IRONMAN);
        ProfileTypes types = new ProfileTypes(port);

        for (int i = 0; i < 100; i++) {
            assertThat(types.of(IRON)).isEqualTo(ProfileType.IRONMAN);
        }

        assertThat(port.asked.get())
                .describedAs("a query per block a visitor touches is the defect this avoids")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Two profiles are two answers, not one shared one")
    void twoProfilesAreTwoAnswers() {
        CountingPort port = new CountingPort();
        port.stored.put(IRON, ProfileType.IRONMAN);
        ProfileTypes types = new ProfileTypes(port);

        assertThat(types.of(IRON)).isEqualTo(ProfileType.IRONMAN);
        assertThat(types.of(CLASSIC)).isEqualTo(ProfileType.CLASSIC);
        assertThat(port.asked.get()).isEqualTo(2);
        assertThat(types.held()).isEqualTo(2);
    }

    @Test
    @DisplayName("A profile that has been forgotten is asked about again")
    void aforgottenProfileIsAskedAgain() {
        CountingPort port = new CountingPort();
        ProfileTypes types = new ProfileTypes(port);

        types.of(CLASSIC);
        types.forget(CLASSIC);
        types.of(CLASSIC);

        assertThat(port.asked.get()).isEqualTo(2);
    }
}
