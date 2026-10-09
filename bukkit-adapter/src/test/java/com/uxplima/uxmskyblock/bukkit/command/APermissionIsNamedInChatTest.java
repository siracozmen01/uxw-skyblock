package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A permission is named in chat the way the reader's language names it.
 *
 * <p>{@code /is permissions} listed every permission as its constant in lower case, so a player read
 * {@code block_break, chest_open, shulker_open} where the window beside it spoke English.
 */
class APermissionIsNamedInChatTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());

    private final IslandMembershipService membership = mock(IslandMembershipService.class);
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private PlayerMock ada;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        ProfileId adaProfile = ProfileId.of(ada.getUniqueId());
        Island island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                PlayerUuid.of(ada.getUniqueId()),
                adaProfile,
                Instant.parse("2026-10-09T12:00:00Z"));
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(adaProfile)).thenReturn(Optional.of(ISLAND));
        when(locations.findIsland(ISLAND)).thenReturn(Optional.of(island));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(ada.getUniqueId())).thenReturn(Optional.of(adaProfile));
        when(membership.setRolePermission(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new IslandMembershipService.PermissionOutcome.Changed("member", "chest_open", false));
        IslandMembershipCommands commands = new IslandMembershipCommands(
                () -> membership, locations, new InlineSchedulerPort(), Messages.bundled(), sessions);
        dispatcher.register(commands.buildPermissions());
    }

    @Test
    @DisplayName("The roles in chat name what each may do in words, not in constants")
    void theListNamesThePermissions() throws Exception {
        run("permissions");

        String said = String.join("\n", heard());
        assertThat(said).contains("Break blocks").contains("Open chests").doesNotContain("block_break");
    }

    @Test
    @DisplayName("A permission moved is named in words")
    void aChangeNamesThePermission() throws Exception {
        run("permissions member chest_open off");

        assertThat(heard()).singleElement().asString().contains("Open chests").doesNotContain("chest_open");
    }

    private void run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(ada);
        dispatcher.execute(line, source);
    }

    private List<String> heard() {
        List<String> lines = new ArrayList<>();
        for (Component line = ada.nextComponentMessage(); line != null; line = ada.nextComponentMessage()) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return lines;
    }
}
