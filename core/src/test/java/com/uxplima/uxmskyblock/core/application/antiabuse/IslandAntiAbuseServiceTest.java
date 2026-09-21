package com.uxplima.uxmskyblock.core.application.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult;
import com.uxplima.uxmskyblock.core.domain.antiabuse.IslandQuarantineRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.PlayerAntiAbuseRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandAntiAbuseServiceTest {

    private static class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this.now = initial;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static class InMemoryAntiAbuseStoragePort implements AntiAbuseStoragePort {
        final Map<PlayerUuid, PlayerAntiAbuseRecord> records = new HashMap<>();
        final Map<IslandId, IslandQuarantineRecord> quarantines = new HashMap<>();

        @Override
        public Optional<PlayerAntiAbuseRecord> findRecord(PlayerUuid playerUuid) {
            return Optional.ofNullable(records.get(playerUuid));
        }

        @Override
        public void saveRecord(PlayerAntiAbuseRecord record) {
            records.put(record.playerUuid(), record);
        }

        @Override
        public Optional<IslandQuarantineRecord> findQuarantine(IslandId islandId) {
            return Optional.ofNullable(quarantines.get(islandId));
        }

        @Override
        public void saveQuarantine(IslandQuarantineRecord record) {
            quarantines.put(record.islandId(), record);
        }

        @Override
        public void deleteQuarantine(IslandId islandId) {
            quarantines.remove(islandId);
        }

        @Override
        public Map<IslandId, IslandQuarantineRecord> loadActiveQuarantines(Instant now) {
            Map<IslandId, IslandQuarantineRecord> active = new HashMap<>();
            for (var entry : quarantines.entrySet()) {
                if (entry.getValue().isQuarantined(now)) {
                    active.put(entry.getKey(), entry.getValue());
                }
            }
            return active;
        }
    }

    private MutableClock clock;
    private InMemoryAntiAbuseStoragePort storage;
    private IslandAntiAbuseService service;
    private PlayerUuid playerUuid;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("UTC"));
        storage = new InMemoryAntiAbuseStoragePort();
        service = new IslandAntiAbuseService(
                storage,
                true,
                Duration.ofMinutes(15),
                Duration.ofHours(12),
                3,
                Duration.ofHours(24),
                Duration.ofHours(24),
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                clock);
        playerUuid = new PlayerUuid(UUID.randomUUID());
        islandId = new IslandId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Initial state permits reset with full daily quota")
    void initialStatePermitsReset() {
        ResetCheckResult result = service.checkResetAllowed(playerUuid, false);
        assertThat(result).isInstanceOf(ResetCheckResult.Allowed.class);
        ResetCheckResult.Allowed allowed = (ResetCheckResult.Allowed) result;
        assertThat(allowed.remainingResetsToday()).isEqualTo(3);
    }

    @Test
    @DisplayName("Recorded reset activates cooldown and blocks immediate reset")
    void recordedResetActivatesCooldown() {
        service.recordReset(playerUuid, clock.instant());

        // Immediately check again
        ResetCheckResult result = service.checkResetAllowed(playerUuid, false);
        assertThat(result).isInstanceOf(ResetCheckResult.CooldownActive.class);
        ResetCheckResult.CooldownActive cd = (ResetCheckResult.CooldownActive) result;
        assertThat(cd.remaining()).isEqualTo(Duration.ofHours(12));

        // Advance 6 hours - still on cooldown
        clock.advance(Duration.ofHours(6));
        ResetCheckResult resultMid = service.checkResetAllowed(playerUuid, false);
        assertThat(resultMid).isInstanceOf(ResetCheckResult.CooldownActive.class);
        assertThat(((ResetCheckResult.CooldownActive) resultMid).remaining()).isEqualTo(Duration.ofHours(6));

        // Advance 6 more hours (total 12h) - cooldown elapsed, 2 resets remaining
        clock.advance(Duration.ofHours(6));
        ResetCheckResult resultElapsed = service.checkResetAllowed(playerUuid, false);
        assertThat(resultElapsed).isInstanceOf(ResetCheckResult.Allowed.class);
        assertThat(((ResetCheckResult.Allowed) resultElapsed).remainingResetsToday())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Daily reset quota is enforced after max resets consumed")
    void dailyResetQuotaIsEnforced() {
        IslandAntiAbuseService customService = new IslandAntiAbuseService(
                storage,
                true,
                Duration.ofMinutes(15),
                Duration.ofHours(6),
                3,
                Duration.ofHours(24),
                Duration.ofHours(24),
                IslandAntiAbuseService.DEFAULT_QUARANTINE_LOOKUP_TTL,
                clock);

        // Reset 1 at 0h
        customService.recordReset(playerUuid, clock.instant());

        // Advance 6h and Reset 2 at 6h
        clock.advance(Duration.ofHours(6));
        customService.recordReset(playerUuid, clock.instant());

        // Advance 6h and Reset 3 at 12h (3 resets consumed within 24h window)
        clock.advance(Duration.ofHours(6));
        customService.recordReset(playerUuid, clock.instant());

        // Advance 6h to 18h: reset cooldown (6h) has elapsed, but daily limit (3) is exceeded
        clock.advance(Duration.ofHours(6));
        ResetCheckResult result = customService.checkResetAllowed(playerUuid, false);
        assertThat(result).isInstanceOf(ResetCheckResult.DailyLimitExceeded.class);
        ResetCheckResult.DailyLimitExceeded limit = (ResetCheckResult.DailyLimitExceeded) result;
        assertThat(limit.maxDailyResets()).isEqualTo(3);
        assertThat(limit.remaining()).isEqualTo(Duration.ofHours(6));

        // Advance 6 more hours (total 24h): daily window rolls over, resets allowed again
        clock.advance(Duration.ofHours(6));
        ResetCheckResult rolledOver = customService.checkResetAllowed(playerUuid, false);
        assertThat(rolledOver).isInstanceOf(ResetCheckResult.Allowed.class);
        assertThat(((ResetCheckResult.Allowed) rolledOver).remainingResetsToday())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Bypass flag overrides both cooldown and daily quota")
    void bypassOverridesCooldownAndDailyLimit() {
        service.recordReset(playerUuid, clock.instant());

        ResetCheckResult result = service.checkResetAllowed(playerUuid, true);
        assertThat(result).isInstanceOf(ResetCheckResult.Bypassed.class);
    }

    @Test
    @DisplayName("Co-op join is allowed by default and restricted after departure")
    void coopJoinRestrictedAfterDeparture() {
        // Default allowed
        CoopJoinCheckResult r1 = service.checkCoopJoinAllowed(playerUuid, false);
        assertThat(r1).isInstanceOf(CoopJoinCheckResult.Allowed.class);

        // Departure recorded
        service.recordCoopDeparture(playerUuid, clock.instant());

        // Attempting to join triggers 24h cooldown
        CoopJoinCheckResult r2 = service.checkCoopJoinAllowed(playerUuid, false);
        assertThat(r2).isInstanceOf(CoopJoinCheckResult.CooldownActive.class);
        assertThat(((CoopJoinCheckResult.CooldownActive) r2).remaining()).isEqualTo(Duration.ofHours(24));

        // Bypass flag overrides cooldown
        CoopJoinCheckResult rBypass = service.checkCoopJoinAllowed(playerUuid, true);
        assertThat(rBypass).isInstanceOf(CoopJoinCheckResult.Bypassed.class);

        // Advance 24h -> allowed again
        clock.advance(Duration.ofHours(24));
        CoopJoinCheckResult r3 = service.checkCoopJoinAllowed(playerUuid, false);
        assertThat(r3).isInstanceOf(CoopJoinCheckResult.Allowed.class);
    }

    @Test
    @DisplayName("Island quarantine lifecycle enforces 15m window and manual lift")
    void islandQuarantineLifecycle() {
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isFalse();
        assertThat(service.getQuarantineRemaining(islandId, clock.instant())).isEmpty();

        // Quarantine newly created island
        service.quarantineNewIsland(islandId, clock.instant());
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isTrue();
        assertThat(service.getQuarantineRemaining(islandId, clock.instant())).contains(Duration.ofMinutes(15));

        // Advance 10 minutes -> still 5m left
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isTrue();
        assertThat(service.getQuarantineRemaining(islandId, clock.instant())).contains(Duration.ofMinutes(5));

        // Advance 5 minutes (total 15m) -> expired
        clock.advance(Duration.ofMinutes(5));
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isFalse();
        assertThat(service.getQuarantineRemaining(islandId, clock.instant())).isEmpty();

        // Re-quarantine and manually lift
        service.quarantineNewIsland(islandId, clock.instant());
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isTrue();
        service.liftQuarantine(islandId);
        assertThat(service.isIslandQuarantined(islandId, clock.instant())).isFalse();
    }
}
