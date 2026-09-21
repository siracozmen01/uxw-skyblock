package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How long a reset code lasts is the operator's number, not ours.
 *
 * <p>It was sixty seconds written in the code: how long a player has to read a four digit code and
 * type it back before the island they are about to erase stops listening. Too short and a slow
 * reader loses the code; too long and somebody else can walk up to the keyboard. Neither answer is
 * ours to pick for every server.
 */
class ResetChallengeWindowIsConfigurableTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private static IslandRecycleService serviceWith(Duration window) {
        return new IslandRecycleService(
                mock(IslandStoragePort.class),
                mock(WorldGridAllocationPort.class),
                mock(SpiralSlotPoolPort.class),
                null,
                null,
                mock(OutboxPort.class),
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                window);
    }

    @Test
    @DisplayName("The code expires when the operator's window says it does")
    void theWindowIsTheOneConfigured() {
        ResetChallenge challenge = serviceWith(Duration.ofSeconds(10))
                .generateResetChallenge(new ProfileId(UUID.randomUUID()), IslandId.of(UUID.randomUUID()));

        assertThat(challenge.expiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    @DisplayName("An operator who names a longer window gets one")
    void aLongerWindowIsHonoured() {
        ResetChallenge challenge = serviceWith(Duration.ofMinutes(5))
                .generateResetChallenge(new ProfileId(UUID.randomUUID()), IslandId.of(UUID.randomUUID()));

        assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("A caller who names no window gets the shipped sixty seconds")
    void theDefaultIsStillSixtySeconds() {
        IslandRecycleService service = new IslandRecycleService(
                mock(IslandStoragePort.class),
                mock(WorldGridAllocationPort.class),
                mock(SpiralSlotPoolPort.class),
                null,
                null,
                mock(OutboxPort.class),
                null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ResetChallenge challenge =
                service.generateResetChallenge(new ProfileId(UUID.randomUUID()), IslandId.of(UUID.randomUUID()));

        assertThat(challenge.expiresAt()).isEqualTo(NOW.plus(IslandRecycleService.DEFAULT_CHALLENGE_TTL));
    }
}
