package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An island is called by the name it was given, or by whose island it is, and never by its id.
 *
 * <p>The allies, the alliance offers, the bookmarks and the board named an island nobody had named
 * by its id, so a player read {@code 91ec264e-53eb-49a1-85f6-2c0c5b876177} where they wanted to know
 * whose island it was.
 */
class AnIslandIsCalledByItsNameTest extends MockBukkitHarness {

    private static final IslandId MINE = IslandId.of(UUID.randomUUID());
    private static final IslandId OLAS = IslandId.of(UUID.randomUUID());
    private static final IslandId REEF = IslandId.of(UUID.randomUUID());
    private static final IslandId LOST = IslandId.of(UUID.randomUUID());

    private final IslandLocationService locations = mock(IslandLocationService.class);
    private final IslandNameService names = mock(IslandNameService.class);
    private final PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private PlayerMock ada;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        PlayerMock ola = createPlayer("Ola");
        ProfileId adaProfile = ProfileId.of(ada.getUniqueId());
        when(sessions.activeProfile(ada.getUniqueId())).thenReturn(Optional.of(adaProfile));
        when(locations.findIslandId(adaProfile)).thenReturn(Optional.of(MINE));
        when(locations.findIsland(OLAS)).thenReturn(Optional.of(islandOf(OLAS, ola)));
        when(locations.findIsland(REEF)).thenReturn(Optional.of(islandOf(REEF, ola)));
        when(names.getIslandName(any())).thenReturn(Optional.empty());
        when(names.getIslandName(REEF)).thenReturn(Optional.of(new IslandName("Reef")));
    }

    @Test
    @DisplayName("The allies are named by their name, or by their owner, or by a short id")
    void theAllies() throws Exception {
        IslandAllianceService alliances = mock(IslandAllianceService.class);
        when(alliances.getAllies(MINE)).thenReturn(List.of(REEF, OLAS, LOST));
        IslandAllianceCommands commands = new IslandAllianceCommands(
                () -> alliances, locations, new InlineSchedulerPort(), Messages.bundled(), sessions);
        commands.useIslandNames(() -> names);
        dispatcher.register(commands.build());

        run("alliance list");

        assertThat(String.join("\n", heard()))
                .contains("Reef")
                .contains("Ola's island")
                .contains(LOST.value().toString().substring(0, 8))
                .doesNotContain(OLAS.value().toString())
                .doesNotContain(LOST.value().toString());
    }

    @Test
    @DisplayName("An alliance offer names the island that sent it")
    void theOffers() throws Exception {
        IslandAllianceService alliances = mock(IslandAllianceService.class);
        when(alliances.getPendingInvites(MINE))
                .thenReturn(List.of(new IslandAllianceInvite(
                        AllianceInviteId.random(),
                        OLAS,
                        MINE,
                        ProfileId.of(UUID.randomUUID()),
                        Instant.now(),
                        Instant.now().plusSeconds(300))));
        IslandAllianceCommands commands = new IslandAllianceCommands(
                () -> alliances, locations, new InlineSchedulerPort(), Messages.bundled(), sessions);
        dispatcher.register(commands.build());

        run("alliance invites");

        assertThat(String.join("\n", heard()))
                .contains("Ola's island")
                .doesNotContain(OLAS.value().toString());
    }

    @Test
    @DisplayName("A bookmark names the island it saved")
    void theBookmarks() throws Exception {
        IslandSocialService social = mock(IslandSocialService.class);
        when(social.listBookmarks(ProfileId.of(ada.getUniqueId())))
                .thenReturn(List.of(SocialSubjectRef.island(REEF), SocialSubjectRef.island(OLAS)));
        IslandSocialCommands commands = new IslandSocialCommands(
                () -> social,
                mock(SpatialIslandIndex.class),
                locations,
                new InlineSchedulerPort(),
                Messages.bundled(),
                sessions);
        commands.useIslandNames(() -> names);
        dispatcher.register(commands.buildBookmarks());

        run("bookmarks");

        assertThat(String.join("\n", heard()))
                .contains("Reef")
                .contains("Ola's island")
                .doesNotContain(OLAS.value().toString())
                .doesNotContain(REEF.value().toString());
    }

    @Test
    @DisplayName("The board names an island nobody named by its owner")
    void theBoard() throws Exception {
        IslandLeaderboardService board = mock(IslandLeaderboardService.class);
        when(board.getTop(any(LeaderboardCategory.class), anyInt()))
                .thenReturn(List.of(
                        new LeaderboardEntry(1, REEF, "Reef", 90, "90"),
                        new LeaderboardEntry(2, OLAS, OLAS.value().toString(), 80, "80", false)));
        when(board.getRank(any(), any())).thenReturn(java.util.OptionalInt.empty());
        IslandProgressionCommands commands = new IslandProgressionCommands(
                locations,
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankService.class),
                board,
                mock(com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                sessions,
                new InlineSchedulerPort(),
                () -> null,
                () -> null,
                Messages.bundled());
        commands.useIslandNames(() -> names);
        dispatcher.register(commands.buildTop());

        run("top");

        assertThat(String.join("\n", heard()))
                .contains("Reef")
                .contains("Ola's island")
                .doesNotContain(OLAS.value().toString());
    }

    private static Island islandOf(IslandId id, PlayerMock owner) {
        return Island.create(
                id,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                PlayerUuid.of(owner.getUniqueId()),
                ProfileId.of(owner.getUniqueId()),
                Instant.parse("2026-10-10T12:00:00Z"));
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
