package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is reload}, end to end through Brigadier.
 *
 * <p>The one command an operator types while players are on the server, and the only one of the
 * command classes that had no test. What it must not do is as important as what it does: reading
 * files is file work, so it never happens on the thread Brigadier calls a command on, and a player
 * without the administrative permission never reaches it at all.
 */
class IslandReloadCommandsTest {

    private ServerMock server;
    private PlayerMock player;
    private SkyblockReloader reloader;
    private SchedulerPort scheduler;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    /** Records what was handed to the scheduler rather than running it, so the thread can be pinned. */
    private final java.util.List<Runnable> deferred = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        player.setOp(true);

        reloader = mock(SkyblockReloader.class);
        when(reloader.reload()).thenReturn(new SkyblockReloader.ReloadReport(3, 13, List.of()));

        scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> deferred.add(invocation.getArgument(0, Runnable.class)))
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));

        IslandReloadCommands commands = new IslandReloadCommands(
                () -> reloader, scheduler, Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildReload());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute("reload", source);
    }

    @Test
    @DisplayName("Reading the files never happens on the thread the command arrives on")
    void theFilesAreReadOffTheCommandThread() throws Exception {
        run(player);

        verify(reloader, never()).reload();
        assertThat(deferred).describedAs("work handed to the scheduler").hasSize(1);

        deferred.get(0).run();
        verify(reloader).reload();
    }

    @Test
    @DisplayName("The operator is told it started and then what was read")
    void theOperatorIsToldBothTimes() throws Exception {
        run(player);
        assertThat(player.nextMessage())
                .describedAs("the line that says it started")
                .isNotNull();

        deferred.get(0).run();
        assertThat(player.nextMessage())
                .describedAs("the line that says what was read")
                .isNotNull();
        assertThat(player.nextMessage())
                .describedAs("nothing else when nothing failed")
                .isNull();
    }

    @Test
    @DisplayName("Every file that would not read is named, one line each")
    void everyFailureIsNamed() throws Exception {
        when(reloader.reload())
                .thenReturn(new SkyblockReloader.ReloadReport(
                        2, 12, List.of("messages_tr.conf is not valid HOCON", "island-main.conf names no rows")));

        run(player);
        assertThat(player.nextMessage()).isNotNull();
        deferred.get(0).run();

        assertThat(player.nextMessage()).describedAs("what was read").isNotNull();
        assertThat(player.nextMessage()).describedAs("the first failure").isNotNull();
        assertThat(player.nextMessage()).describedAs("the second failure").isNotNull();
        assertThat(player.nextMessage())
                .describedAs("nothing after the last one")
                .isNull();
    }

    @Test
    @DisplayName("A server with no reloader says so rather than saying nothing")
    void noReloaderIsAnAnswer() throws Exception {
        IslandReloadCommands commands = new IslandReloadCommands(
                () -> null, scheduler, Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));
        CommandDispatcher<CommandSourceStack> bare = new CommandDispatcher<>();
        bare.register(commands.buildReload());

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        bare.execute("reload", source);

        assertThat(player.nextMessage())
                .describedAs("the player is told why nothing happened")
                .isNotNull();
        assertThat(deferred).describedAs("nothing was scheduled").isEmpty();
    }

    @Test
    @DisplayName("A player who is not an operator cannot run it at all")
    void aPlayerWithoutThePermissionCannotRunIt() {
        PlayerMock ordinary = server.addPlayer();
        ordinary.setOp(false);

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(ordinary);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dispatcher.execute("reload", source))
                .describedAs("the branch is not even there for them")
                .isInstanceOf(CommandSyntaxException.class);
        verify(reloader, never()).reload();
    }
}
