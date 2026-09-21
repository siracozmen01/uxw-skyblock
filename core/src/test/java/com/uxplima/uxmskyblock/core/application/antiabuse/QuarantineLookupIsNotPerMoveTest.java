package com.uxplima.uxmskyblock.core.application.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A movement check must not be a database query.
 *
 * <p>{@code isIslandQuarantined} is called from {@code onPlayerMove}, and a player sends many
 * movement packets a second. An island with no quarantine row is not in the in-memory map, so every
 * one of those calls went to {@code findQuarantine}: the common case was the uncached case, and a
 * player walking on a healthy island ran a query per step.
 */
class QuarantineLookupIsNotPerMoveTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final Instant START = Instant.parse("2026-09-21T12:00:00Z");

    private static IslandAntiAbuseService serviceAt(AntiAbuseStoragePort port, Clock clock, Duration ttl) {
        return new IslandAntiAbuseService(
                port,
                true,
                Duration.ofMinutes(15),
                Duration.ofHours(12),
                3,
                Duration.ofHours(24),
                Duration.ofHours(24),
                ttl,
                clock);
    }

    @Test
    @DisplayName("A thousand movement checks on a clean island cost one query")
    void aCleanIslandIsLookedUpOnce() {
        AntiAbuseStoragePort port = mock(AntiAbuseStoragePort.class);
        when(port.loadActiveQuarantines(any())).thenReturn(Map.of());
        when(port.findQuarantine(ISLAND)).thenReturn(Optional.empty());
        IslandAntiAbuseService service = serviceAt(port, Clock.fixed(START, ZoneOffset.UTC), Duration.ofSeconds(30));

        for (int i = 0; i < 1000; i++) {
            assertThat(service.isIslandQuarantined(ISLAND, START)).isFalse();
        }

        verify(port, times(1)).findQuarantine(ISLAND);
    }

    @Test
    @DisplayName("The answer is looked up again once it has gone stale, so another node's quarantine lands")
    void aStaleAnswerIsLookedUpAgain() {
        AntiAbuseStoragePort port = mock(AntiAbuseStoragePort.class);
        when(port.loadActiveQuarantines(any())).thenReturn(Map.of());
        when(port.findQuarantine(ISLAND)).thenReturn(Optional.empty());
        IslandAntiAbuseService service = serviceAt(port, Clock.fixed(START, ZoneOffset.UTC), Duration.ofSeconds(30));

        service.isIslandQuarantined(ISLAND, START);
        service.isIslandQuarantined(ISLAND, START.plusSeconds(10));
        service.isIslandQuarantined(ISLAND, START.plusSeconds(31));

        verify(port, times(2)).findQuarantine(ISLAND);
    }

    @Test
    @DisplayName("A quarantine this node sets is seen at once, without waiting out the stale window")
    void aLocalQuarantineIsNotDelayed() {
        AntiAbuseStoragePort port = mock(AntiAbuseStoragePort.class);
        when(port.loadActiveQuarantines(any())).thenReturn(Map.of());
        when(port.findQuarantine(ISLAND)).thenReturn(Optional.empty());
        IslandAntiAbuseService service = serviceAt(port, Clock.fixed(START, ZoneOffset.UTC), Duration.ofSeconds(30));
        assertThat(service.isIslandQuarantined(ISLAND, START)).isFalse();

        service.quarantineNewIsland(ISLAND, START);

        assertThat(service.isIslandQuarantined(ISLAND, START.plusSeconds(1)))
                .describedAs("this node set the quarantine, so it must not read its own stale answer")
                .isTrue();
    }
}
