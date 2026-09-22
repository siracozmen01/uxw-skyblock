package com.uxplima.uxmskyblock.core.application.freeze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asking whether an island is frozen is not a query, let alone two.
 *
 * <p>Every block anybody breaks or places goes through this question first, and it read the freeze
 * table every single time, on the thread the event arrived on. An island with no freeze record then
 * ran a second query for the island itself, so building on a healthy island, which is what every
 * player does all day, cost two queries per block.
 *
 * <p>The second query answered a question the one caller already holds: the protection listener has
 * the island in its hand and reads its administrative state on the same line.
 */
class TheFreezeCheckIsNotTwoQueriesPerBlockTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private IslandAdminFreezeService serviceOver(
            IslandAdminFreezePort freezePort, IslandStoragePort islandStoragePort, Duration ttl) {
        return new IslandAdminFreezeService(
                islandStoragePort,
                new com.uxplima.uxmskyblock.core.application.island.IslandMutationLock(),
                freezePort,
                null,
                null,
                ttl,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("A hundred blocks on a healthy island read the freeze table once and the island never")
    void aHundredBlocksReadOnce() {
        AtomicInteger freezeReads = new AtomicInteger();
        IslandAdminFreezePort freezePort = mock(IslandAdminFreezePort.class);
        when(freezePort.findFreezeRecord(any())).thenAnswer(invocation -> {
            freezeReads.incrementAndGet();
            return Optional.empty();
        });
        IslandStoragePort islandStoragePort = mock(IslandStoragePort.class);

        IslandAdminFreezeService service = serviceOver(freezePort, islandStoragePort, Duration.ofSeconds(30));

        for (int block = 0; block < 100; block++) {
            assertThat(service.isFrozen(ISLAND)).describedAs("block %d", block).isFalse();
        }

        assertThat(freezeReads.get())
                .describedAs("reads of the freeze table for a hundred blocks")
                .isEqualTo(1);
        verify(islandStoragePort, never()).findIslandById(any());
    }

    @Test
    @DisplayName("A frozen island is read once and then answered from memory")
    void aFrozenIslandIsAlsoRemembered() {
        AtomicInteger freezeReads = new AtomicInteger();
        IslandAdminFreezePort freezePort = mock(IslandAdminFreezePort.class);
        when(freezePort.findFreezeRecord(any())).thenAnswer(invocation -> {
            freezeReads.incrementAndGet();
            return Optional.of(new IslandFreezeRecord(
                    ISLAND,
                    com.uxplima.uxmskyblock.core.domain.island.AdministrativeState.FROZEN,
                    "for testing",
                    "an admin",
                    NOW));
        });

        IslandAdminFreezeService service =
                serviceOver(freezePort, mock(IslandStoragePort.class), Duration.ofSeconds(30));

        for (int block = 0; block < 10; block++) {
            assertThat(service.isFrozen(ISLAND)).isTrue();
        }

        assertThat(freezeReads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A window of nothing reads every time, which is what an operator writing zero asks for")
    void aZeroWindowAlwaysReads() {
        AtomicInteger freezeReads = new AtomicInteger();
        IslandAdminFreezePort freezePort = mock(IslandAdminFreezePort.class);
        when(freezePort.findFreezeRecord(any())).thenAnswer(invocation -> {
            freezeReads.incrementAndGet();
            return Optional.empty();
        });

        IslandAdminFreezeService service = serviceOver(freezePort, mock(IslandStoragePort.class), Duration.ZERO);

        for (int block = 0; block < 5; block++) {
            service.isFrozen(ISLAND);
        }

        assertThat(freezeReads.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("An island this node let go of is read again")
    void aForgottenIslandIsReadAgain() {
        AtomicInteger freezeReads = new AtomicInteger();
        IslandAdminFreezePort freezePort = mock(IslandAdminFreezePort.class);
        when(freezePort.findFreezeRecord(any())).thenAnswer(invocation -> {
            freezeReads.incrementAndGet();
            return Optional.empty();
        });

        IslandAdminFreezeService service = serviceOver(freezePort, mock(IslandStoragePort.class), Duration.ofHours(1));

        service.isFrozen(ISLAND);
        service.isFrozen(ISLAND);
        assertThat(freezeReads.get()).isEqualTo(1);
        assertThat(service.islandsHeldInMemory()).isEqualTo(1);

        service.forgetIsland(ISLAND);

        assertThat(service.islandsHeldInMemory()).isZero();
        service.isFrozen(ISLAND);
        assertThat(freezeReads.get()).isEqualTo(2);
    }
}
