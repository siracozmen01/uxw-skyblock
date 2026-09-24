package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.island.ResidencyState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What an administrator actually reads back from {@code /is admin inspect}.
 *
 * <p>The report is nine lines wide and every one of them was untested. A report is only worth
 * running if the numbers in it are the island's own, so these tests read the text a sender
 * receives rather than counting that something was sent.
 *
 * <p>The freeze reason is the line that hides a defect best. A frozen island whose reason column
 * is null is an ordinary row: a freeze written by an older build, or one restored from a file. The
 * report must name that absence in words, never print the word null and never drop the line.
 */
class WhatAnAdminInspectReportsTest extends MockBukkitHarness {

    private static final String WORLD = "skyblock_world";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final PlayerUuid ownerUuid = new PlayerUuid(UUID.randomUUID());
    private final ProfileId ownerProfile = new ProfileId(UUID.randomUUID());
    private final ProfileId secondProfile = new ProfileId(UUID.randomUUID());

    private PlayerMock admin;
    private IslandAdminFreezeService freeze;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUpInspect() {
        admin = createPlayer("Admin");
        var plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin();
        admin.addAttachment(plugin, CatalogPermissions.ADMIN_INSPECT.node(), true);
        admin.addAttachment(plugin, CatalogPermissions.ADMIN_MANAGE.node(), true);

        freeze = mock(IslandAdminFreezeService.class);
        rebuild(() -> freeze);
    }

    private void rebuild(Supplier<@Nullable IslandAdminFreezeService> freezeProvider) {
        IslandAdminCommands commands = new IslandAdminCommands(
                () -> null,
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
        return scheduler;
    }

    private Island plainIsland() {
        Island island = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(320, -640, 96), ownerUuid, ownerProfile, Instant.now());
        return island.addMember(
                new IslandMember(new PlayerUuid(UUID.randomUUID()), secondProfile, IslandRole.MEMBER, Instant.now()));
    }

    private Island frozenIsland(String reason) {
        return plainIsland().freeze(reason);
    }

    private Island frozenWithNoReasonStored() {
        Island base = plainIsland();
        return new Island(
                base.id(),
                base.bounds(),
                base.ownerPlayerUuid(),
                base.ownerProfileId(),
                base.members(),
                base.roles(),
                IslandFlags.defaults(),
                base.createdAt(),
                IslandLifecycle.ACTIVE,
                ResidencyState.UNLOADED,
                EconomicState.NORMAL,
                AdministrativeState.FROZEN,
                null);
    }

    /** Runs the verb and hands back every line the administrator reads, in order. */
    private List<String> inspect() throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        dispatcher.execute("admin inspect " + islandId.value(), source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = admin.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    @Test
    @DisplayName("The report carries the island's own owner, lifecycle, economy and membership counts")
    void theReportCarriesTheIslandsOwnNumbers() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(plainIsland()));
        when(freeze.findLocation(islandId)).thenReturn(Optional.empty());

        List<String> lines = inspect();

        assertThat(String.join("\n", lines))
                .contains(islandId.value().toString())
                .contains(ownerUuid.value().toString())
                .contains("Lifecycle: active")
                .contains("Economic state: normal")
                .contains("Members: 2, roles: 5");
    }

    @Test
    @DisplayName("An island that is not frozen reads NORMAL and carries no freeze reason line")
    void anUnfrozenIslandCarriesNoReason() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(plainIsland()));
        when(freeze.findLocation(islandId)).thenReturn(Optional.empty());

        assertThat(String.join("\n", inspect()))
                .contains("Administrative state: NORMAL")
                .doesNotContain("FROZEN")
                .doesNotContain("Freeze reason");
    }

    @Test
    @DisplayName("A frozen island names the reason the administrator wrote")
    void aFrozenIslandNamesItsReason() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(frozenIsland("chest duplication under review")));
        when(freeze.findLocation(islandId)).thenReturn(Optional.empty());

        assertThat(String.join("\n", inspect()))
                .contains("Administrative state: FROZEN")
                .contains("Freeze reason: chest duplication under review");
    }

    @Test
    @DisplayName("A frozen island with no reason stored says so in words, not null")
    void aFrozenIslandWithNoReasonSaysSoInWords() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(frozenWithNoReasonStored()));
        when(freeze.findLocation(islandId)).thenReturn(Optional.empty());

        String report = String.join("\n", inspect());

        assertThat(report).contains("Administrative state: FROZEN").contains("Freeze reason: none given");
        assertThat(report).doesNotContain("null");
    }

    @Test
    @DisplayName("The location line carries the world, the centre and the radius")
    void theLocationLineCarriesTheBounds() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(plainIsland()));
        when(freeze.findLocation(islandId))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(islandId, WORLD, 320, -640, 96)));

        assertThat(String.join("\n", inspect())).contains("Location: " + WORLD + " (320, -640) radius 96");
    }

    @Test
    @DisplayName("An island whose location was never written still reports everything else")
    void aMissingLocationDropsOnlyItsOwnLine() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.of(plainIsland()));
        when(freeze.findLocation(islandId)).thenReturn(Optional.empty());

        List<String> lines = inspect();

        assertThat(String.join("\n", lines)).doesNotContain("Location:");
        assertThat(lines).hasSize(6);
    }

    @Test
    @DisplayName("An island with no record behind it is named as missing, not reported as empty")
    void aMissingRecordIsNamed() throws Exception {
        when(freeze.findIsland(islandId)).thenReturn(Optional.empty());

        List<String> lines = inspect();

        assertThat(String.join("\n", lines))
                .contains("No island record was found")
                .contains(islandId.value().toString());
        assertThat(lines).hasSize(1);
    }

    @Test
    @DisplayName("An inspect with the freeze service switched off refuses instead of reporting half a report")
    void aSwitchedOffServiceRefuses() throws Exception {
        rebuild(() -> null);

        List<String> lines = inspect();

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("not configured");
    }

    @Test
    @DisplayName("A word that names no island is refused with the word the administrator typed")
    void anUnresolvableTargetIsRefusedByName() throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        dispatcher.execute("admin inspect Nobody", source);

        Component first = admin.nextComponentMessage();
        assertThat(first).isNotNull();
        assertThat(PLAIN.serialize(first))
                .contains("No island could be resolved")
                .contains("Nobody");
        assertThat(admin.nextComponentMessage()).isNull();
    }
}
