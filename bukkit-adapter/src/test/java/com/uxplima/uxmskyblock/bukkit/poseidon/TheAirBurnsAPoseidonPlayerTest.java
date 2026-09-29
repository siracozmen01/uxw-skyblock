package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.hazard.PoseidonRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * On a Poseidon island water is the air. In it a player breathes and sees, and is hurt only after
 * staying still too long. Out of it dry air hurts and the sun hurts more, unless rain keeps them wet.
 * On an island of any other mode, and in creative, nothing here touches them.
 */
class TheAirBurnsAPoseidonPlayerTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final long NOON = 6_000;
    private static final long MIDNIGHT = 18_000;

    private final Set<IslandId> stored = new HashSet<>();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-29T12:00:00Z"));

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PoseidonHazard hazard;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpIslands() {
        world = server.addSimpleWorld("skyblock");
        world.setStorm(false);
        world.setTime(MIDNIGHT);
        Island poseidon = island(0);
        Island plain = island(1000);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(poseidon, "skyblock");
        islands.cacheIsland(plain, "skyblock");
        PoseidonService service = new PoseidonService(new PoseidonIslandsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stored);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stored.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stored.add(islandId);
            }
        });
        service.start(poseidon.id());
        PoseidonConfiguration config = new PoseidonConfiguration(
                true,
                PoseidonConfiguration.Ocean.SHIPPED,
                new PoseidonRules(1.0, 3.0, 2.5, Duration.ofSeconds(5), 1.0, true, Duration.ofSeconds(1)),
                List.of("water_breathing:0:4", "night_vision:0:15", "no_such_effect:0:3"));
        hazard = new PoseidonHazard(
                service,
                islands,
                mock(SchedulerPort.class),
                config,
                InteractionEffects.none(),
                new InteractionEffectPlayer(),
                clock);
        player = createPlayer("Diver");
        player.setGameMode(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("The water lets a swimmer breathe and see, and hurts nobody who keeps moving")
    void theWaterIsAir() {
        standIn(8, Material.WATER);

        assertThat(hazard.check(player)).isZero();
        PotionEffect breath = player.getPotionEffect(PotionEffectType.WATER_BREATHING);
        assertThat(breath).isNotNull();
        assertThat(breath.getDuration()).isEqualTo(80);
        assertThat(player.getPotionEffect(PotionEffectType.NIGHT_VISION)).isNotNull();

        for (int x = 9; x <= 14; x++) {
            clock.advance(Duration.ofSeconds(2));
            standIn(x * 2, Material.WATER);
            assertThat(hazard.check(player))
                    .describedAs("a swimmer who keeps moving is never still")
                    .isZero();
        }
        assertThat(player.getHealth()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("A swimmer who stays within reach past the limit is hurt, and moving starts the count again")
    void stillnessDrowns() {
        standIn(8, Material.WATER);
        hazard.check(player);

        clock.advance(Duration.ofSeconds(4));
        player.teleport(new Location(world, 8.9, Y, 8.9));
        assertThat(hazard.check(player)).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(hazard.check(player)).isEqualTo(2.5);
        assertThat(player.getHealth()).isEqualTo(17.5);

        standIn(12, Material.WATER);
        assertThat(hazard.check(player)).isZero();
        clock.advance(Duration.ofSeconds(4));
        assertThat(hazard.check(player)).isZero();

        world.getBlockAt(12, Y, 8).setType(Material.AIR);
        world.getBlockAt(12, Y + 1, 8).setType(Material.AIR);
        hazard.check(player);
        standIn(12, Material.WATER);
        clock.advance(Duration.ofSeconds(1));
        assertThat(hazard.check(player))
                .describedAs("leaving the water ends the rest, so coming back to the same spot starts it again")
                .isZero();
    }

    @Test
    @DisplayName("Dry air hurts at night, the sun adds its own by day, and a roof keeps the sun off")
    void theAirBurns() {
        standIn(8, Material.AIR);
        assertThat(hazard.check(player)).isEqualTo(1.0);

        world.setTime(NOON);
        assertThat(hazard.check(player)).isEqualTo(4.0);

        world.getBlockAt(8, Y + 10, 8).setType(Material.STONE);
        assertThat(hazard.check(player)).isEqualTo(1.0);
        assertThat(player.getPotionEffect(PotionEffectType.WATER_BREATHING))
                .describedAs("the air gives nothing")
                .isNull();
    }

    @Test
    @DisplayName("Rain keeps a player under the open sky wet, and a roof keeps the rain off")
    void rainIsWet() {
        standIn(8, Material.AIR);
        world.setStorm(true);
        world.setTime(NOON);
        assertThat(hazard.check(player)).isZero();

        world.getBlockAt(8, Y + 10, 8).setType(Material.STONE);
        assertThat(hazard.check(player)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Another mode's island, and creative, are not touched")
    void onlyPoseidonHurts() {
        standIn(1008, Material.AIR);
        world.setTime(NOON);
        assertThat(hazard.check(player)).isZero();

        standIn(8, Material.AIR);
        player.setGameMode(GameMode.CREATIVE);
        assertThat(hazard.check(player)).isZero();
        assertThat(player.getHealth()).isEqualTo(20.0);
    }

    private void standIn(int x, Material fill) {
        world.getBlockAt(x, Y, 8).setType(fill);
        world.getBlockAt(x, Y + 1, 8).setType(fill);
        player.teleport(new Location(world, x + 0.5, Y, 8.5));
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    /** A clock the test moves on by hand. */
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
