package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandRecycleServiceTest {

    private IslandStoragePort islandStoragePort;
    private WorldGridAllocationPort worldGridAllocationPort;
    private SpiralSlotPoolPort spiralSlotPoolPort;
    private IslandVoidingPort voidingPort;
    private IslandBackupPort backupPort;
    private OutboxPort outboxPort;
    private MutableClock clock;
    private IslandRecycleService service;

    private IslandId islandId;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;
    private ProfileId visitorProfileId;
    private Island island;
    private IslandLocation location;
    private WorldGridAllocation allocation;

    @BeforeEach
    void setUp() {
        islandStoragePort = mock(IslandStoragePort.class);
        worldGridAllocationPort = mock(WorldGridAllocationPort.class);
        spiralSlotPoolPort = mock(SpiralSlotPoolPort.class);
        voidingPort = mock(IslandVoidingPort.class);
        backupPort = mock(IslandBackupPort.class);
        outboxPort = mock(OutboxPort.class);
        clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"));

        service = new IslandRecycleService(
                islandStoragePort,
                worldGridAllocationPort,
                spiralSlotPoolPort,
                voidingPort,
                backupPort,
                outboxPort,
                clock);

        islandId = IslandId.of(UUID.randomUUID());
        ownerUuid = new PlayerUuid(UUID.randomUUID());
        ownerProfileId = new ProfileId(ownerUuid.value());
        visitorProfileId = new ProfileId(UUID.randomUUID());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(100, 200, 50);
        island = Island.create(islandId, bounds, ownerUuid, ownerProfileId, clock.instant());
        location = IslandLocation.fromCenterAndRadius(islandId, "skyblock_world", 100, 200, 50);
        allocation = new WorldGridAllocation(
                42L, "skyblock_world", 100, 200, Optional.of(islandId), ServerNodeId.of("node-1"), clock.instant());

        when(islandStoragePort.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(islandStoragePort.findLocationByIslandId(islandId)).thenReturn(Optional.of(location));
        when(worldGridAllocationPort.findByIslandId(islandId)).thenReturn(Optional.of(allocation));
    }

    @Test
    @DisplayName("generateResetChallenge produces valid 4-digit code with 60-second TTL")
    void generateResetChallengeProducesValidCode() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        assertThat(challenge.code()).matches("\\d{4}");
        assertThat(challenge.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(60)));
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isTrue();
    }

    @Test
    @DisplayName("verifyResetChallenge returns false for incorrect code or expired challenge")
    void verifyResetChallengeFailsOnMismatchOrExpiry() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        assertThat(service.verifyResetChallenge(ownerProfileId, "99999")).isFalse();
        assertThat(service.verifyResetChallenge(ownerProfileId, "wrong")).isFalse();
        assertThat(service.verifyResetChallenge(visitorProfileId, challenge.code()))
                .isFalse();

        // Advance past expiry
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("cancelResetChallenge clears active challenge")
    void cancelResetChallengeClearsPending() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);
        service.cancelResetChallenge(ownerProfileId);

        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("executeReset fails if island does not exist")
    void executeResetFailsWhenIslandNotFound() {
        IslandId nonExistent = IslandId.of(UUID.randomUUID());
        when(islandStoragePort.findIslandById(nonExistent)).thenReturn(Optional.empty());

        RecycleResult result = service.executeReset(ownerProfileId, nonExistent, "1234", false);
        assertThat(result).isInstanceOf(RecycleResult.IslandNotFound.class);
    }

    @Test
    @DisplayName("executeReset fails if requester is not island owner without admin bypass")
    void executeResetFailsWhenNotOwner() {
        RecycleResult result = service.executeReset(visitorProfileId, islandId, "1234", false);
        assertThat(result).isInstanceOf(RecycleResult.NotOwner.class);
    }

    @Test
    @DisplayName("executeReset fails if challenge code is missing or invalid")
    void executeResetFailsWhenInvalidChallenge() {
        RecycleResult resultNull = service.executeReset(ownerProfileId, islandId, null, false);
        assertThat(resultNull).isInstanceOf(RecycleResult.InvalidChallenge.class);

        RecycleResult resultWrong = service.executeReset(ownerProfileId, islandId, "0000", false);
        assertThat(resultWrong).isInstanceOf(RecycleResult.InvalidChallenge.class);
    }

    @Test
    @DisplayName("executeReset succeeds with valid challenge code, triggers backup, voiding, recycling, and deletion")
    void executeResetSucceedsWithValidChallenge() {
        ResetChallenge challenge = service.generateResetChallenge(ownerProfileId, islandId);

        RecycleResult result = service.executeReset(ownerProfileId, islandId, challenge.code(), false);
        assertThat(result).isInstanceOf(RecycleResult.Success.class);

        RecycleResult.Success success = (RecycleResult.Success) result;
        assertThat(success.islandId()).isEqualTo(islandId);
        assertThat(success.slotIndex()).isEqualTo(42L);
        assertThat(success.worldName()).isEqualTo("skyblock_world");
        assertThat(success.gridX()).isEqualTo(100);
        assertThat(success.gridZ()).isEqualTo(200);

        verify(backupPort).createPreDeletionBackup(eq(island), eq(location));
        verify(spiralSlotPoolPort).releaseSlot(eq(42L), eq("skyblock_world"), eq(100), eq(200));
        verify(voidingPort).voidIslandChunks(eq(islandId), eq("skyblock_world"), eq(island.bounds()));
        verify(islandStoragePort).deleteIsland(
                eq(islandId),
                org.mockito.ArgumentMatchers.argThat(event -> event != null && "ISLAND_RECYCLED".equals(event.eventType())));

        // Challenge should be consumed
        assertThat(service.verifyResetChallenge(ownerProfileId, challenge.code()))
                .isFalse();
    }

    @Test
    @DisplayName("executeReset with admin bypass succeeds without challenge or owner identity")
    void executeResetWithAdminBypassSucceeds() {
        ProfileId staffProfile = new ProfileId(UUID.randomUUID());

        RecycleResult result = service.executeReset(staffProfile, islandId, null, true);
        assertThat(result).isInstanceOf(RecycleResult.Success.class);

        verify(backupPort).createPreDeletionBackup(eq(island), eq(location));
        verify(spiralSlotPoolPort).releaseSlot(eq(42L), eq("skyblock_world"), eq(100), eq(200));
        verify(voidingPort).voidIslandChunks(eq(islandId), eq("skyblock_world"), eq(island.bounds()));
        verify(islandStoragePort).deleteIsland(eq(islandId), any());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
