package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.uxmlib.health.HealthCheck;
import com.uxplima.uxmlib.health.HealthResult;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /is doctor} through Brigadier: a line per check, a verdict, and nothing for a player without the node. */
class IslandDoctorCommandsTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    @DisplayName("Every check is said with its status, and a failure makes the verdict call for an operator")
    void everyCheckIsSaid() throws Exception {
        PlayerMock admin = createPlayer("Admin");
        admin.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_DOCTOR.node(), true);

        List<String> said = run(
                admin,
                List.of(
                        named("storage", HealthResult.ok("the database answered")),
                        named("economy", HealthResult.warn("no economy plugin answers")),
                        named("windows", HealthResult.fail("no menu file was read"))));

        assertThat(said).anyMatch(line -> line.contains("OK storage: the database answered"));
        assertThat(said).anyMatch(line -> line.contains("WARN economy: no economy plugin answers"));
        assertThat(said).anyMatch(line -> line.contains("FAIL windows: no menu file was read"));
        assertThat(said.getLast()).contains("needs an operator");
    }

    @Test
    @DisplayName("With nothing failing the verdict says nothing needs an operator")
    void aWellPluginSaysSo() throws Exception {
        PlayerMock admin = createPlayer("Admin");
        admin.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_DOCTOR.node(), true);

        assertThat(run(admin, List.of(named("storage", HealthResult.ok("fine"))))
                        .getLast())
                .contains("Nothing needs an operator");
    }

    @Test
    @DisplayName("A player without the doctor node cannot reach it")
    void aPlayerWithoutTheNodeIsRefused() {
        PlayerMock player = createPlayer("Player");

        List<String> said = new ArrayList<>();
        try {
            said.addAll(run(player, List.of(named("storage", HealthResult.ok("fine")))));
        } catch (CommandSyntaxException refused) {
            // Brigadier treats a branch the sender may not see as an unknown command.
        }

        assertThat(said).isEmpty();
    }

    private static HealthCheck named(String name, HealthResult result) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HealthResult check() {
                return result;
            }
        };
    }

    private List<String> run(PlayerMock sender, List<HealthCheck> checks) throws CommandSyntaxException {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(
                new IslandDoctorCommands(() -> checks, inlineScheduler(), Messages.bundled()).buildDoctor());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute("doctor", source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = sender.nextComponentMessage()) != null) {
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
