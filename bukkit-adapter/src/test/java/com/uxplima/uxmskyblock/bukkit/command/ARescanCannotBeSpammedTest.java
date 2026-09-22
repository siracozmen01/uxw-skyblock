package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.application.worth.RecalculationGate;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is level recalculate} reads every block the island covers at every height. Nothing held
 * a player back, so the command could be sent as fast as they typed and keep their region busy.
 */
class ARescanCannotBeSpammedTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private PlayerMock player;
    private IslandWorthService worth;
    private final List<Consumer<IslandScoreBreakdown>> unfinished = new ArrayList<>();
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        player = createPlayer("Counter");
        worth = mock(IslandWorthService.class);
        doAnswer(call -> {
                    unfinished.add(call.getArgument(5));
                    return null;
                })
                .when(worth)
                .triggerAsyncRecalculation(any(), any(), any(), anyInt(), anyLong(), any());

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(new IslandLocation(
                        ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100, 0.5, 0, 0)));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandProgressionCommands commands = new IslandProgressionCommands(
                locations,
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                sessions,
                inlineScheduler(),
                () -> worth,
                () -> null,
                Messages.bundled());
        commands.useRecalculationGate(new RecalculationGate(
                Duration.ofSeconds(60), Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC)));
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildLevel());
        dispatcher.register(commands.buildRecalc());
    }

    @Test
    @DisplayName("A second rescan while the first runs never reaches the scanner, and the player is told why")
    void aSecondRescanWhileRunningIsRefused() throws Exception {
        run("recalc");
        List<String> second = run("level recalculate");

        verify(worth, times(1)).triggerAsyncRecalculation(any(), any(), any(), anyInt(), anyLong(), any());
        assertThat(second).singleElement().asString().contains("already");
    }

    @Test
    @DisplayName("A rescan right after the last one finished waits out the operator's cooldown")
    void aRescanTooSoonWaits() throws Exception {
        run("recalc");
        unfinished.removeFirst().accept(IslandScoreBreakdown.zero());
        while (player.nextComponentMessage() != null) {
            // The finished rescan's own lines.
        }
        List<String> again = run("recalc");

        verify(worth, times(1)).triggerAsyncRecalculation(any(), any(), any(), anyInt(), anyLong(), any());
        assertThat(again).singleElement().asString().contains("60 seconds");
    }

    @Test
    @DisplayName("A rescan that throws before it starts does not hold the island's place for ever")
    void aRescanThatThrowsLetsGo() throws Exception {
        RecalculationGate gate = new RecalculationGate(Duration.ZERO, Clock.systemUTC());
        doAnswer(call -> {
                    throw new IllegalStateException("scanner gone");
                })
                .when(worth)
                .triggerAsyncRecalculation(any(), any(), any(), anyInt(), anyLong(), any());
        IslandProgressionCommands commands = commandsWith(gate);

        try {
            run("recalc");
        } catch (RuntimeException expected) {
            // The inline scheduler hands the failure back; a real one logs it.
        }

        assertThat(gate.tryEnter(ISLAND)).isInstanceOf(RecalculationGate.Admission.Admitted.class);
        assertThat(commands).isNotNull();
    }

    private IslandProgressionCommands commandsWith(RecalculationGate gate) {
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(new IslandLocation(
                        ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100, 0.5, 0, 0)));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        IslandProgressionCommands commands = new IslandProgressionCommands(
                locations,
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                sessions,
                inlineScheduler(),
                () -> worth,
                () -> null,
                Messages.bundled());
        commands.useRecalculationGate(gate);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildRecalc());
        return commands;
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            String text = PLAIN.serialize(next);
            if (!text.contains("Rescanning")) {
                lines.add(text);
            }
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
