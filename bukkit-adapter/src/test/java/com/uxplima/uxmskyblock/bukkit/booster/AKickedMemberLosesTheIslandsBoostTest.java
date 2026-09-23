package com.uxplima.uxmskyblock.bukkit.booster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A member kicked from an island stops killing mobs for its experience booster.
 *
 * <p>The listener read which island a player's kills belong to once and kept it until the player
 * left the server. A member kicked mid-session went on dropping the island's boosted experience at
 * their own farm until they logged out. The island is read again once it is as old as the operator's
 * {@code boosters.multiplier-cache-ttl}.
 */
class AKickedMemberLosesTheIslandsBoostTest extends MockBukkitHarness {

    private final MovingClock clock = new MovingClock(Instant.parse("2026-09-23T12:00:00Z"));
    private final IslandStoragePort storage = mock(IslandStoragePort.class);
    private final IslandBoosterService boosters = mock(IslandBoosterService.class);
    private final PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private IslandBoosterListener listener;
    private PlayerMock member;
    private ProfileId profile;

    @BeforeEach
    void setUp() {
        member = createPlayer("Farmer");
        profile = ProfileId.of(UUID.randomUUID());
        when(sessions.activeProfile(member.getUniqueId())).thenReturn(Optional.of(profile));
        when(storage.findIslandIdByProfileId(profile)).thenReturn(Optional.of(islandId));
        when(boosters.getEffectiveMultiplier(eq(islandId), eq(BoosterCategory.MOB_EXP), any()))
                .thenReturn(2.0);
        listener = new IslandBoosterListener(
                storage,
                boosters,
                BoosterConfiguration.defaultConfiguration(),
                sessions,
                new InlineSchedulerPort(),
                clock);
    }

    @Test
    @DisplayName("Once the island is read again, a kicked member's kills drop plain experience")
    void aKickedMemberLosesTheBoost() {
        assertThat(kill())
                .describedAs("the first kill, while the island is read")
                .isEqualTo(10);
        assertThat(kill()).describedAs("a member's kill").isEqualTo(20);

        when(storage.findIslandIdByProfileId(profile)).thenReturn(Optional.empty());
        clock.advance(BoosterConfiguration.DEFAULT_MULTIPLIER_CACHE_TTL.plusSeconds(1));
        kill();

        assertThat(kill()).describedAs("a kill after the island was read again").isEqualTo(10);
    }

    @Test
    @DisplayName("A member who stays keeps the boost across every read")
    void aMemberWhoStaysKeepsIt() {
        kill();
        for (int i = 0; i < 3; i++) {
            clock.advance(BoosterConfiguration.DEFAULT_MULTIPLIER_CACHE_TTL.plusSeconds(1));
            assertThat(kill()).isEqualTo(20);
        }
    }

    private int kill() {
        World world = server.addSimpleWorld("skyblock_world");
        LivingEntity mob = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(member)
                .withDirectEntity(member)
                .build();
        EntityDeathEvent death = new EntityDeathEvent(mob, source, new ArrayList<>(), 10);
        listener.onEntityDeath(death);
        return death.getDroppedExp();
    }

    private static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant start) {
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
