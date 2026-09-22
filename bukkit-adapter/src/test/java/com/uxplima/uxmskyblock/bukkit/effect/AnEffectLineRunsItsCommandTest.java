package com.uxplima.uxmskyblock.bukkit.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A {@code [console]} or {@code [player]} line runs its command.
 *
 * <p>The effects context wired no command sink, so the library's default ran nothing and an
 * operator's command line did nothing at all.
 */
class AnEffectLineRunsItsCommandTest extends MockBukkitHarness {

    private final List<CommandSender> heardFrom = new ArrayList<>();
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        player = createPlayer("Builder");
        server.getCommandMap().register("probe", new Command("probe") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                heardFrom.add(sender);
                return true;
            }
        });
    }

    @Test
    @DisplayName("A console line runs its command as the console")
    void aConsoleLineRunsAsTheConsole() {
        new InteractionEffectPlayer().fire(effects("[console] probe"), "island-create", player);

        assertThat(heardFrom).singleElement().isInstanceOf(ConsoleCommandSender.class);
    }

    @Test
    @DisplayName("A player line runs its command as the player")
    void aPlayerLineRunsAsThePlayer() {
        new InteractionEffectPlayer().fire(effects("[player] probe"), "island-create", player);

        assertThat(heardFrom).containsExactly(player);
    }

    @Test
    @DisplayName("A console line goes through the global region where there is a scheduler")
    void aConsoleLineGoesThroughTheGlobalRegion() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any());

        new InteractionEffectPlayer(scheduler).fire(effects("[console] probe"), "island-create", player);

        verify(scheduler).onGlobal(any());
        assertThat(heardFrom).singleElement().isInstanceOf(ConsoleCommandSender.class);
    }

    private static InteractionEffects effects(String line) {
        return new InteractionEffects(Map.of("island-create", ActionList.parse(List.of(line))));
    }
}
