package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService.SpawnUpdate;
import com.uxplima.uxmskyblock.core.application.network.IslandNetworkRouter;
import com.uxplima.uxmskyblock.core.application.network.RouteOutcome;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** What the navigation commands tell a player, read off the catalogue that ships. */
class WhatTheNavigationCommandsTellAPlayerTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private static final IslandLocation HOME = new IslandLocation(
            IslandId.of(UUID.randomUUID()),
            "skyblock_world",
            IslandBounds.fromCenterAndRadius(0, 0, 50),
            8.5,
            65.0,
            8.5,
            0.0f,
            0.0f);

    private PlayerMock player;
    private IslandNetworkRouter router;
    private IslandLocationService locations;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld("skyblock_world");
        player = createRegionThreadedPlayer("Walker");
        locations = mock(IslandLocationService.class);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        router = mock(IslandNetworkRouter.class);
        IslandNavigationCommands commands = new IslandNavigationCommands(
                locations,
                sessions,
                inlineScheduler(),
                "skyblock_world",
                () -> null,
                () -> router,
                () -> null,
                () -> null,
                Messages.bundled());
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildSetSpawn());
        dispatcher.register(commands.buildHome());
        dispatcher.register(commands.buildVisit());
    }

    @Test
    @DisplayName("Home welcomes the player once they have arrived")
    void homeWelcomesOnArrival() throws Exception {
        when(locations.resolveHome(PROFILE)).thenReturn(Optional.of(HOME));

        assertThat(run("home")).singleElement().asString().contains("Welcome");
        assertThat(player.getLocation().getBlockX()).isEqualTo(8);
    }

    @Test
    @DisplayName("A teleport another plugin cancelled is not a welcome")
    void aCancelledTeleportIsNotAWelcome() throws Exception {
        when(locations.resolveHome(PROFILE)).thenReturn(Optional.of(HOME));
        server.getPluginManager()
                .registerEvent(
                        PlayerTeleportEvent.class,
                        new Listener() {},
                        EventPriority.NORMAL,
                        (listener, event) -> ((PlayerTeleportEvent) event).setCancelled(true),
                        MockBukkit.createMockPlugin());

        assertThat(run("home"))
                .singleElement()
                .asString()
                .contains("could not be moved")
                .doesNotContain("Welcome");
    }

    @Test
    @DisplayName("A network that cannot reach the island says so in words, not in a catalogue key")
    void anUnreachableIslandIsSaidInWords() throws Exception {
        IslandId target = IslandId.of(UUID.randomUUID());
        when(locations.findIsland(target)).thenReturn(Optional.of(openIsland(target)));
        when(router.routeVisit(any(), eq(target)))
                .thenReturn(CompletableFuture.completedFuture(
                        new RouteOutcome.Unavailable(IslandNetworkRouter.ERROR_CLUSTER_UNAVAILABLE)));

        assertThat(run("visit " + target.value()))
                .singleElement()
                .asString()
                .contains("cannot be reached")
                .doesNotContain("error.network");
    }

    @Test
    @DisplayName("An island the network cannot find reads as an island that is not there")
    void aMissingIslandReadsAsMissing() throws Exception {
        IslandId target = IslandId.of(UUID.randomUUID());
        when(locations.findIsland(target)).thenReturn(Optional.of(openIsland(target)));
        when(router.routeVisit(any(), eq(target)))
                .thenReturn(CompletableFuture.completedFuture(
                        new RouteOutcome.Unavailable(IslandNetworkRouter.ERROR_ISLAND_NOT_FOUND)));

        assertThat(run("visit " + target.value()))
                .singleElement()
                .asString()
                .doesNotContain("error.network")
                .contains(target.value().toString());
    }

    private static Island openIsland(IslandId id) {
        return Island.create(
                id,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }

    @ParameterizedTest
    @CsvSource({
        "UPDATED, spawn",
        "OUTSIDE_THE_ISLAND, Stand on your own island",
        "NOT_ALLOWED, does not allow moving its spawn",
        "NO_ISLAND, island"
    })
    @DisplayName("Each answer to /is setspawn reads its own line")
    void eachSpawnAnswerReadsItsOwnLine(SpawnUpdate answer, String expected) throws Exception {
        when(locations.updateSpawn(any(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat()))
                .thenReturn(answer);

        assertThat(run("setspawn")).singleElement().asString().contains(expected);
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }
}
