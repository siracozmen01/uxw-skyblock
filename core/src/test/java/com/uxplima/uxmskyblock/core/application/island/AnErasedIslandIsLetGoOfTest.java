package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort;
import com.uxplima.uxmskyblock.core.application.antiabuse.AntiAbuseStoragePort;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyStoragePort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three more services that hold something for every island anybody has walked on.
 *
 * <p>Each of them answers a question on the movement or interaction path, and each remembers the
 * answer so the path is not a database query. The answer for a healthy island is remembered exactly
 * like the answer for an unhealthy one, so the map holds an entry for every island a player has been
 * near. An island id is a fresh uuid every time, so an erased island's entry is never read again and
 * nothing was letting go of it.
 */
class AnErasedIslandIsLetGoOfTest {

    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final CurrentNodeProcessIdentity NODE = new CurrentNodeProcessIdentity("node-alpha", "gen-1");

    @Test
    @DisplayName("The temporary access grants kept for an island are let go when it is erased")
    void theGrantsAreLetGo() {
        TemporaryAccessStoragePort storage = mock(TemporaryAccessStoragePort.class);
        when(storage.findActiveByRoot(any(), any())).thenReturn(List.of());
        TemporaryAccessService service = new TemporaryAccessService(storage, Duration.ofHours(1));

        IslandId islandId = IslandId.of(UUID.randomUUID());
        service.hasAccess(
                SocialSubjectRef.ISLAND_TYPE,
                islandId.value().toString(),
                ProfileId.of(UUID.randomUUID()),
                StandardPermissions.BLOCK_BREAK,
                NOW,
                NODE,
                null,
                ProfileType.CLASSIC);
        assertThat(service.rootsHeldInMemory())
                .describedAs("one visit to a healthy island is one entry")
                .isEqualTo(1);

        service.forgetIsland(islandId);

        assertThat(service.rootsHeldInMemory()).isZero();
    }

    @Test
    @DisplayName("The quarantine answer kept for an island is let go when it is erased")
    void theQuarantineAnswerIsLetGo() {
        AntiAbuseStoragePort storage = mock(AntiAbuseStoragePort.class);
        when(storage.loadActiveQuarantines(any())).thenReturn(java.util.Map.of());
        when(storage.findQuarantine(any())).thenReturn(Optional.empty());
        IslandAntiAbuseService service = new IslandAntiAbuseService(
                storage,
                false,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_DURATION,
                IslandAntiAbuseService.DEFAULT_RESET_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_MAX_RESETS_PER_DAY,
                IslandAntiAbuseService.DEFAULT_RESET_WINDOW_DURATION,
                IslandAntiAbuseService.DEFAULT_COOP_JOIN_COOLDOWN,
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                Clock.fixed(NOW, ZoneOffset.UTC));

        IslandId islandId = IslandId.of(UUID.randomUUID());
        assertThat(service.isIslandQuarantined(islandId, NOW)).isFalse();
        assertThat(service.islandsHeldInMemory())
                .describedAs("one walk on a healthy island is one entry")
                .isEqualTo(1);

        service.forgetIsland(islandId);

        assertThat(service.islandsHeldInMemory()).isZero();
    }

    @Test
    @DisplayName("The bankruptcy record kept for an island is let go when it is erased")
    void theBankruptcyRecordIsLetGo() {
        IslandBankruptcyStoragePort storage = mock(IslandBankruptcyStoragePort.class);
        when(storage.findByIslandId(any())).thenReturn(Optional.empty());
        IslandBankruptcyService service = new IslandBankruptcyService(
                storage,
                mock(IslandBankPort.class),
                mock(IslandAuthorityPort.class),
                () -> com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy.defaultPolicy());

        IslandId islandId = IslandId.of(UUID.randomUUID());
        assertThat(service.isIslandLocked(islandId, NOW)).isFalse();
        assertThat(service.islandsHeldInMemory())
                .describedAs("one walk on a healthy island is one entry")
                .isEqualTo(1);

        service.forgetIsland(islandId);

        assertThat(service.islandsHeldInMemory()).isZero();
    }
}
