package com.uxplima.uxmskyblock.core.application.worth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One rescan of an island at a time, and not again until the operator's wait is over. */
class RecalculationGateTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-22T12:00:00Z"));
    private final RecalculationGate gate = new RecalculationGate(Duration.ofSeconds(60), clock);

    @Test
    @DisplayName("The first rescan of an island is let in")
    void theFirstIsAdmitted() {
        assertThat(gate.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.Admitted.class);
    }

    @Test
    @DisplayName("A second rescan while the first still runs is refused as running, not as cooling")
    void aSecondWhileRunningIsRefused() {
        gate.tryEnter(ISLAND);

        assertThat(gate.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.AlreadyRunning.class);
    }

    @Test
    @DisplayName("A rescan right after the last one finished waits out what is left of the cooldown")
    void aRescanTooSoonWaits() {
        gate.tryEnter(ISLAND);
        clock.advance(Duration.ofSeconds(20));
        gate.leave(ISLAND);

        assertThat(gate.tryEnter(ISLAND))
                .isEqualTo(new RecalculationGate.Admission.CoolingDown(Duration.ofSeconds(40)));
    }

    @Test
    @DisplayName("Once the cooldown has passed the island may be rescanned again")
    void afterTheCooldownItIsAdmitted() {
        gate.tryEnter(ISLAND);
        gate.leave(ISLAND);
        clock.advance(Duration.ofSeconds(60));

        assertThat(gate.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.Admitted.class);
    }

    @Test
    @DisplayName("One island's rescan never holds another island back")
    void islandsAreIndependent() {
        gate.tryEnter(ISLAND);

        assertThat(gate.tryEnter(IslandId.of(UUID.randomUUID())))
                .isInstanceOf(RecalculationGate.Admission.Admitted.class);
    }

    @Test
    @DisplayName("A cooldown of zero still lets only one rescan of an island run at a time")
    void zeroCooldownStillSerialises() {
        RecalculationGate open = new RecalculationGate(Duration.ZERO, clock);
        open.tryEnter(ISLAND);

        assertThat(open.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.AlreadyRunning.class);
        open.leave(ISLAND);
        assertThat(open.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.Admitted.class);
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
