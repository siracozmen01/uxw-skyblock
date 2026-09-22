package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** What the inactivity scan, freeze and unfreeze tell an administrator, read off the shipped catalogue. */
class WhatTheAdminCommandsSayTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String WORLD = "skyblock_world";
    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private PlayerMock admin;
    private IslandInactivityService inactivity;
    private IslandAdminFreezeService freeze;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        admin = createPlayer("Admin");
        inactivity = mock(IslandInactivityService.class);
        freeze = mock(IslandAdminFreezeService.class);
        build(() -> inactivity, () -> freeze);
    }

    private void grant(String... nodes) {
        var plugin = MockBukkit.createMockPlugin();
        for (String node : nodes) {
            admin.addAttachment(plugin, node, true);
        }
    }

    private void build(
            Supplier<@Nullable IslandInactivityService> inactivityProvider,
            Supplier<@Nullable IslandAdminFreezeService> freezeProvider) {
        IslandAdminCommands commands = new IslandAdminCommands(
                inactivityProvider,
                freezeProvider,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                () -> null,
                mock(IslandProtectionListener.class),
                mock(IslandLocationService.class),
                mock(PlayerSessionCoordinator.class),
                inlineScheduler(),
                WORLD,
                Messages.bundled());
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildAdmin());
    }

    @Test
    @DisplayName("A moderator trusted only to inspect cannot start a scan that deletes islands")
    void anInspectorCannotScan() {
        grant(CatalogPermissions.ADMIN_INSPECT.node());

        List<String> said = new ArrayList<>();
        try {
            said.addAll(run("admin inactivity scan"));
        } catch (CommandSyntaxException refused) {
            // Brigadier refuses a branch the sender may not see as an unknown command.
        }

        verify(inactivity, never()).scanWorld(anyString(), any());
        assertThat(said).isEmpty();
    }

    @Test
    @DisplayName("A scan reports every number the service counted")
    void aScanReportsItsNumbers() throws Exception {
        grant(CatalogPermissions.ADMIN_MANAGE.node());
        when(inactivity.scanWorld(eq(WORLD), any()))
                .thenReturn(new IslandInactivityScanReport(Instant.now(), 40, 3, 5, 2, 30, List.of()));

        assertThat(String.join("\n", run("admin inactivity scan")))
                .contains("Starting")
                .contains("40 evaluated, 3 successions, 5 archived, 2 deleted, 30 skipped");
    }

    @Test
    @DisplayName("A scan that fails says it failed")
    void aFailedScanSaysSo() throws Exception {
        grant(CatalogPermissions.ADMIN_MANAGE.node());
        when(inactivity.scanWorld(eq(WORLD), any())).thenThrow(new IllegalStateException("database is gone"));

        assertThat(String.join("\n", run("admin inactivity scan"))).contains("scan failed");
    }

    @Test
    @DisplayName("A node without the inactivity service says so rather than scanning nothing")
    void noScannerIsSaid() throws Exception {
        grant(CatalogPermissions.ADMIN_MANAGE.node());
        build(() -> null, () -> freeze);

        assertThat(run("admin inactivity scan")).singleElement().asString().contains("not enabled");
    }

    @Test
    @DisplayName("A freeze names the island and the reason the admin gave")
    void aFreezeNamesIslandAndReason() throws Exception {
        grant(CatalogPermissions.ADMIN_FREEZE.node());
        when(freeze.freezeIsland(eq(ISLAND), anyString(), anyString())).thenReturn(true);

        assertThat(run("admin freeze " + ISLAND.value() + " duplicated diamonds"))
                .singleElement()
                .asString()
                .contains(ISLAND.value().toString())
                .contains("duplicated diamonds");
    }

    @Test
    @DisplayName("A freeze the service refuses says it could not freeze")
    void aRefusedFreezeSaysSo() throws Exception {
        grant(CatalogPermissions.ADMIN_FREEZE.node());
        when(freeze.freezeIsland(eq(ISLAND), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Cannot freeze island while lifecycle is DELETED"));

        assertThat(run("admin freeze " + ISLAND.value()))
                .singleElement()
                .asString()
                .contains("could not be frozen");
    }

    @Test
    @DisplayName("An unfreeze names the island it lifted")
    void anUnfreezeNamesTheIsland() throws Exception {
        grant(CatalogPermissions.ADMIN_FREEZE.node());
        when(freeze.unfreezeIsland(eq(ISLAND), anyString())).thenReturn(true);

        assertThat(run("admin unfreeze " + ISLAND.value()))
                .singleElement()
                .asString()
                .contains("unfroze")
                .contains(ISLAND.value().toString());
    }

    @Test
    @DisplayName("A node without the freeze service says so for freeze and unfreeze")
    void noFreezeServiceIsSaid() throws Exception {
        grant(CatalogPermissions.ADMIN_FREEZE.node());
        build(() -> inactivity, () -> null);

        List<String> said = new ArrayList<>(run("admin freeze " + ISLAND.value()));
        said.addAll(run("admin unfreeze " + ISLAND.value()));

        assertThat(said).hasSize(2).allMatch(line -> line.contains("not enabled"));
    }

    private List<String> run(String line) throws CommandSyntaxException {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = admin.nextComponentMessage()) != null) {
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
