package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is admin}, end to end through Brigadier.
 *
 * <p>These are the verbs that freeze an island and erase one, so the permission gate is the part
 * worth pinning. A {@code requires} clause is invisible to a compile: a branch that lost its gate
 * still parses, still runs, and reports nothing.
 */
class IslandAdminCommandsTest {

    private static final String WORLD = "skyblock_world";
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock admin;
    private PlayerMock ordinary;
    private IslandAdminFreezeService freeze;
    private IslandInactivityService inactivity;
    private IslandProtectionListener protection;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        admin = server.addPlayer("Admin");
        ordinary = server.addPlayer("Ordinary");
        var plugin = MockBukkit.createMockPlugin();
        admin.addAttachment(plugin, CatalogPermissions.ADMIN_MANAGE.node(), true);
        admin.addAttachment(plugin, CatalogPermissions.ADMIN_FREEZE.node(), true);
        admin.addAttachment(plugin, CatalogPermissions.ADMIN_INSPECT.node(), true);

        freeze = mock(IslandAdminFreezeService.class);
        when(freeze.freezeIsland(any(), anyString(), anyString())).thenReturn(true);
        when(freeze.unfreezeIsland(any(), anyString())).thenReturn(true);

        inactivity = mock(IslandInactivityService.class);
        when(inactivity.scanWorld(anyString(), any(Instant.class)))
                .thenReturn(new IslandInactivityScanReport(Instant.now(), 4, 1, 2, 0, 1, List.of()));

        protection = mock(IslandProtectionListener.class);

        IslandLocationService locations = mock(IslandLocationService.class);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);

        IslandAdminCommands commands = new IslandAdminCommands(
                () -> inactivity,
                () -> freeze,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                protection,
                locations,
                sessions,
                inlineScheduler(),
                WORLD,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildAdmin());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("Freezing names the island the admin typed and the reason they gave")
    void freezingCarriesTheReason() throws Exception {
        run("admin freeze " + ISLAND.value() + " building a lag machine", admin);

        verify(freeze).freezeIsland(eq(ISLAND), eq("building a lag machine"), eq("Admin"));
    }

    @Test
    @DisplayName("Freezing with no reason still names one, rather than storing an empty string")
    void freezingWithoutAReasonStillNamesOne() throws Exception {
        run("admin freeze " + ISLAND.value(), admin);

        verify(freeze).freezeIsland(eq(ISLAND), anyString(), eq("Admin"));
    }

    @Test
    @DisplayName("A frozen island is dropped from the protection cache, so the freeze takes effect at once")
    void freezingInvalidatesTheProtectionCache() throws Exception {
        run("admin freeze " + ISLAND.value(), admin);

        verify(protection).invalidateIsland(ISLAND);
    }

    @Test
    @DisplayName("Unfreezing names the same island and the same admin")
    void unfreezingNamesTheIsland() throws Exception {
        run("admin unfreeze " + ISLAND.value(), admin);

        verify(freeze).unfreezeIsland(eq(ISLAND), eq("Admin"));
    }

    @Test
    @DisplayName("The inactivity scan reads the world the plugin was configured with, not one in code")
    void theScanReadsTheConfiguredWorld() throws Exception {
        run("admin inactivity scan", admin);

        verify(inactivity).scanWorld(eq(WORLD), any(Instant.class));
    }

    @Test
    @DisplayName("A player without a single admin permission cannot reach the branch at all")
    void anOrdinaryPlayerCannotReachTheBranch() {
        for (String line : new String[] {
            "admin freeze " + ISLAND.value(), "admin unfreeze " + ISLAND.value(), "admin inactivity scan"
        }) {
            assertThatThrownBy(() -> run(line, ordinary))
                    .describedAs("%s must be out of reach without a permission", line)
                    .isInstanceOf(Exception.class);
        }
        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
        verify(freeze, never()).unfreezeIsland(any(), anyString());
        verify(inactivity, never()).scanWorld(anyString(), any());
    }

    @Test
    @DisplayName("Inspect alone does not open the freeze branch, because the two are separate nodes")
    void inspectAloneDoesNotOpenFreeze() {
        PlayerMock inspector = server.addPlayer("Inspector");
        inspector.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_INSPECT.node(), true);

        assertThatThrownBy(() -> run("admin freeze " + ISLAND.value(), inspector))
                .isInstanceOf(Exception.class);
        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("Inspect alone does open the inspect branch")
    void inspectAloneOpensInspect() throws Exception {
        PlayerMock inspector = server.addPlayer("Inspector");
        inspector.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_INSPECT.node(), true);

        run("admin inspect " + ISLAND.value(), inspector);

        assertThat(inspector.nextMessage()).describedAs("the branch answered").isNotNull();
    }

    @Test
    @DisplayName("A target that names no island freezes nothing")
    void anUnresolvedTargetFreezesNothing() throws Exception {
        run("admin freeze Nobody", admin);

        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("A freeze with no target at all is refused by the parser")
    void freezingNeedsATarget() {
        assertThatThrownBy(() -> run("admin freeze", admin)).isInstanceOf(Exception.class);
        verify(freeze, never()).freezeIsland(any(), anyString(), anyString());
    }
}
