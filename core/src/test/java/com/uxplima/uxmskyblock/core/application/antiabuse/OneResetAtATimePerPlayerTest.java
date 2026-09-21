package com.uxplima.uxmskyblock.core.application.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island is erased once per confirmation, not once per click.
 *
 * <p>A reset is checked against the operator's daily limit when the command arrives and recorded
 * when the world has finished being erased, which is seconds later. A player who confirmed twice, or
 * whose client sent the packet twice, passed the same check both times because neither reset had
 * been recorded yet. Two erasures ran at once and the daily limit was one short of what it said.
 */
class OneResetAtATimePerPlayerTest {

    private static final PlayerUuid PLAYER = new PlayerUuid(UUID.randomUUID());

    private IslandAntiAbuseService service;

    @BeforeEach
    void setUp() {
        service = new IslandAntiAbuseService(
                org.mockito.Mockito.mock(AntiAbuseStoragePort.class),
                false,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_DURATION,
                IslandAntiAbuseService.DEFAULT_RESET_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_MAX_RESETS_PER_DAY,
                IslandAntiAbuseService.DEFAULT_RESET_WINDOW_DURATION,
                IslandAntiAbuseService.DEFAULT_COOP_JOIN_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Eight confirmations at once start one reset")
    void eightConfirmationsStartOneReset() throws Exception {
        int clicks = 8;
        AtomicInteger started = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < clicks; i++) {
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (service.beginReset(PLAYER)) {
                        started.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(started.get()).describedAs("erasures started").isEqualTo(1);
        assertThat(service.resetsRunning()).describedAs("resets still running").isEqualTo(1);
    }

    @Test
    @DisplayName("A reset that finished lets the next one start")
    void thePlayerIsNotLockedOutForEver() {
        assertThat(service.beginReset(PLAYER)).isTrue();
        assertThat(service.beginReset(PLAYER))
                .describedAs("while the first is running")
                .isFalse();

        service.endReset(PLAYER);

        assertThat(service.beginReset(PLAYER))
                .describedAs("once the first has finished")
                .isTrue();
    }

    @Test
    @DisplayName("Giving back a reset nobody holds is not an error")
    void releasingTwiceIsHarmless() {
        service.beginReset(PLAYER);
        service.endReset(PLAYER);
        service.endReset(PLAYER);

        assertThat(service.resetsRunning()).isZero();
    }

    @Test
    @DisplayName("One player's reset does not hold up another's")
    void twoPlayersAreIndependent() {
        PlayerUuid other = new PlayerUuid(UUID.randomUUID());

        assertThat(service.beginReset(PLAYER)).isTrue();
        assertThat(service.beginReset(other)).isTrue();
        assertThat(service.resetsRunning()).isEqualTo(2);
    }
}
