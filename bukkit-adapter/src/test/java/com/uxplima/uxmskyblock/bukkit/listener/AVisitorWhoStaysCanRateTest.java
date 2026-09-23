package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.social.DwellTimeNotMetException;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.social.PlayerIslandSocialAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player who visits an island for the dwell time may rate it.
 *
 * <p>A rating needs a recorded visit at least the dwell time old, and nothing recorded visits: the
 * storage could hold one and no listener wrote it, so with the shipped thirty seconds nobody could
 * rate any island. This runs the recorder, the social service and the SQLite store together.
 */
class AVisitorWhoStaysCanRateTest extends MockBukkitHarness {

    private static final Duration DWELL = Duration.ofSeconds(30);
    private static final Instant ARRIVAL = Instant.parse("2026-09-23T12:00:00Z");

    private Database database;
    private World world;
    private Island island;
    private PlayerMock visitor;
    private final ProfileId visitorProfile = ProfileId.of(UUID.randomUUID());
    private final ProfileId ownerProfile = ProfileId.of(UUID.randomUUID());
    private IslandSocialService social;
    private IslandVisitRecorder recorder;
    private PlayerIslandSocialAdapter store;
    private PlayerMock owner;

    @BeforeEach
    void setUp() {
        database = Database.builder().sqliteInMemory().build();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        world = server.addSimpleWorld("skyblock_world");
        visitor = server.addPlayer();
        owner = server.addPlayer();
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(owner.getUniqueId()),
                ownerProfile,
                ARRIVAL.minusSeconds(3600));
        store = new PlayerIslandSocialAdapter(database);
        social = new IslandSocialService(
                store, RatingPolicy.standardFiveStar(), mock(IslandStoragePort.class), DWELL, 10, 3.0, 3, 200);
        recorder = new IslandVisitRecorder(
                at -> Math.abs(at.getBlockX()) <= 50 && Math.abs(at.getBlockZ()) <= 50
                        ? Optional.of(island)
                        : Optional.empty(),
                uuid -> Optional.of(uuid.equals(visitor.getUniqueId()) ? visitorProfile : ownerProfile),
                social,
                inline(),
                Clock.fixed(ARRIVAL, ZoneOffset.UTC));
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("A visitor who arrives by teleport and stays the dwell time may rate the island")
    void aVisitorWhoStaysMayRate() {
        arriveByTeleport(visitor);

        SocialSubjectRef subject = SocialSubjectRef.island(island.id());
        assertThatThrownBy(() -> social.rate(subject, visitorProfile, 5, ARRIVAL.plusSeconds(10)))
                .isInstanceOf(DwellTimeNotMetException.class);
        social.rate(subject, visitorProfile, 5, ARRIVAL.plus(DWELL).plusSeconds(1));

        assertThat(social.getRatingSummary(subject).totalRatings()).isEqualTo(1);
    }

    @Test
    @DisplayName("Walking about an island is one arrival, and coming back after leaving is another")
    void oneVisitPerArrival() {
        arriveByTeleport(visitor);
        walk(visitor, 5, 5, 6, 5);
        walk(visitor, 6, 5, 7, 5);
        assertThat(visitsBy(visitorProfile)).describedAs("after walking about").isEqualTo(1);

        walk(visitor, 50, 0, 60, 0);
        walk(visitor, 60, 0, 49, 0);
        assertThat(visitsBy(visitorProfile))
                .describedAs("after leaving and coming back")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("The owner arriving on their own island is not a visit")
    void theOwnerIsNotAVisitor() {
        arriveByTeleport(owner);

        assertThat(visitsBy(ownerProfile)).isZero();
    }

    private int visitsBy(ProfileId profile) {
        return store.findVisit(SocialSubjectRef.island(island.id()), profile)
                .map(com.uxplima.uxmskyblock.core.domain.social.SubjectVisit::visitCount)
                .orElse(0);
    }

    private void arriveByTeleport(PlayerMock player) {
        recorder.onTeleport(
                new PlayerTeleportEvent(player, new Location(world, 500, 64, 500), new Location(world, 1, 64, 1)));
    }

    private void walk(PlayerMock player, int fromX, int fromZ, int toX, int toZ) {
        recorder.onMove(
                new PlayerMoveEvent(player, new Location(world, fromX, 64, fromZ), new Location(world, toX, 64, toZ)));
    }

    private static SchedulerPort inline() {
        return new SchedulerPort() {
            @Override
            public void onGlobal(Runnable task) {
                task.run();
            }

            @Override
            public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
                task.run();
            }

            @Override
            public void onEntity(PlayerUuid playerUuid, Runnable task) {
                task.run();
            }

            @Override
            public void async(Runnable task) {
                task.run();
            }

            @Override
            public void asyncAfter(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public void laterGlobal(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }

            @Override
            public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }
        };
    }
}
