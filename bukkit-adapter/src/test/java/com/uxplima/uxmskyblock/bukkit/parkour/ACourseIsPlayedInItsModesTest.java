package com.uxplima.uxmskyblock.bukkit.parkour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerQuitEvent;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourPort;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A course's team builds it in the build mode, and everybody running it, and everybody else on it,
 * plays in the play mode. Off the course, or off the server, a player has the mode they came with.
 */
class ACourseIsPlayedInItsModesTest extends MockBukkitHarness {

    private final Set<IslandId> courses = new HashSet<>();
    private final Set<UUID> runners = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private ParkourModes modes;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock builder;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock visitor;

    @BeforeEach
    void setUpCourse() {
        world = server.addSimpleWorld("skyblock");
        builder = createPlayer("Builder");
        visitor = createPlayer("Visitor");
        Island course = island(0, builder.getUniqueId());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(course, "skyblock");
        islands.cacheIsland(island(1000, UUID.randomUUID()), "skyblock");
        ParkourService service = new ParkourService(new ParkourPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(courses);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return courses.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                courses.add(islandId);
            }

            @Override
            public OptionalLong best(IslandId islandId, PlayerUuid runner) {
                return OptionalLong.empty();
            }

            @Override
            public void finish(IslandId islandId, PlayerUuid runner, long millis) {}

            @Override
            public List<ParkourPort.Runs> mostRun(int limit) {

                return List.of();
            }

            @Override
            public List<ParkourPort.Best> top(IslandId islandId, int limit) {
                return List.of();
            }
        });
        service.start(course.id());
        modes = new ParkourModes(
                service,
                islands,
                mock(SchedulerPort.class),
                ParkourConfiguration.Modes.SHIPPED,
                player -> runners.contains(player.getUniqueId()));
        builder.setGameMode(GameMode.SURVIVAL);
        visitor.setGameMode(GameMode.ADVENTURE);
    }

    @Test
    @DisplayName("The team builds in creative, and runs in survival like everybody else")
    void theTeamBuildsAndRuns() {
        at(builder, 8);
        assertThat(modes.check(builder)).isEqualTo(GameMode.CREATIVE);
        assertThat(builder.getGameMode()).isEqualTo(GameMode.CREATIVE);

        runners.add(builder.getUniqueId());
        assertThat(modes.check(builder)).isEqualTo(GameMode.SURVIVAL);
        assertThat(builder.getGameMode()).isEqualTo(GameMode.SURVIVAL);

        runners.clear();
        at(builder, 1008);
        modes.check(builder);
        assertThat(builder.getGameMode())
                .describedAs("off the course the builder has the mode they came with")
                .isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("A visitor plays the course in survival and has their own mode back off it, or off the server")
    void aVisitorPlays() {
        at(visitor, 8);
        assertThat(modes.check(visitor)).isEqualTo(GameMode.SURVIVAL);
        at(visitor, 1008);
        modes.check(visitor);
        assertThat(visitor.getGameMode()).isEqualTo(GameMode.ADVENTURE);

        at(visitor, 8);
        modes.check(visitor);
        assertThat(visitor.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        modes.onQuit(new PlayerQuitEvent(
                visitor, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertThat(visitor.getGameMode()).isEqualTo(GameMode.ADVENTURE);
    }

    @Test
    @DisplayName("A spectator and a player with the keep permission keep their mode")
    void someKeepTheirMode() {
        visitor.setGameMode(GameMode.SPECTATOR);
        at(visitor, 8);
        assertThat(modes.check(visitor)).isEqualTo(GameMode.SPECTATOR);

        builder.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(),
                ParkourConfiguration.Modes.SHIPPED.keepPermission(),
                true);
        at(builder, 8);
        assertThat(modes.check(builder)).isEqualTo(GameMode.SURVIVAL);
    }

    @Test
    @DisplayName("A run is played in survival or adventure, and a play mode of creative falls back")
    void thePlayModeIsBounded() throws Exception {
        ParkourConfiguration odd = ParkourConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("modes { play = \"CREATIVE\" }"));
        ParkourConfiguration adventure = ParkourConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("modes { play = \"adventure\", keep-permission = \"\" }"));

        assertThat(odd.modes()).isEqualTo(ParkourConfiguration.Modes.SHIPPED);
        assertThat(adventure.modes().play()).isEqualTo(GameMode.ADVENTURE);
        assertThat(adventure.modes().keepPermission()).isEmpty();
    }

    private void at(PlayerMock player, int x) {
        player.teleport(new Location(world, x + 0.5, 101, 8.5));
    }

    private static Island island(int centreX, UUID owner) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(owner),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
