package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is level}, {@code /is worth}, {@code /is value}, {@code /is recalc}, {@code /is top} and
 * {@code /is biome} run, end to end, through Brigadier.
 *
 * <p>The mission count is the part worth pinning. Both scoring paths once passed a hardcoded zero,
 * so {@code levels.quest-weight} was a number an operator could set and never see applied. A test
 * that only watches the message would have passed the whole time.
 */
class IslandProgressionCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final int COMPLETED_MISSIONS = 7;

    private ServerMock server;
    private PlayerMock player;
    private IslandWorthService worth;
    private IslandLeaderboardService leaderboard;
    private BiomeModificationPort biomes;
    private IslandLocationService locations;
    private IslandProgressionCommands commands;
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
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onGlobal(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        worth = mock(IslandWorthService.class);
        when(worth.calculateScore(any(), anyInt(), anyLong())).thenReturn(IslandScoreBreakdown.zero());
        doAnswer(invocation -> {
                    Consumer<IslandScoreBreakdown> onComplete = invocation.getArgument(5);
                    onComplete.accept(IslandScoreBreakdown.zero());
                    return null;
                })
                .when(worth)
                .triggerAsyncRecalculation(any(), any(), any(), anyInt(), anyLong(), any());

        IslandMissionService missions = mock(IslandMissionService.class);
        when(missions.countCompleted(ISLAND, PROFILE)).thenReturn(COMPLETED_MISSIONS);

        leaderboard = mock(IslandLeaderboardService.class);
        when(leaderboard.getTop(any(LeaderboardCategory.class), anyInt())).thenReturn(List.of());

        biomes = mock(BiomeModificationPort.class);
        when(biomes.applyBiome(any(), any())).thenReturn(CompletableFuture.completedFuture(true));

        IslandBankService bank = mock(IslandBankService.class);
        when(bank.getBalanceMinorUnits(PROFILE)).thenReturn(Optional.of(5_000L));

        locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));
        when(locations.findLocation(ISLAND))
                .thenReturn(Optional.of(new IslandLocation(
                        ISLAND,
                        "skyblock_world",
                        IslandBounds.fromCenterAndRadius(0, 0, 100),
                        0.5,
                        100.0,
                        0.5,
                        0.0f,
                        0.0f)));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        commands = new IslandProgressionCommands(
                locations,
                bank,
                leaderboard,
                biomes,
                sessions,
                inlineScheduler(),
                () -> worth,
                () -> missions,
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()));

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildLevel());
        dispatcher.register(commands.buildRecalc());
        dispatcher.register(commands.buildWorth());
        dispatcher.register(commands.buildValue());
        dispatcher.register(commands.buildTop());
        dispatcher.register(commands.buildBiome());
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
    @DisplayName("A bare /is biome names the biomes on offer and how to choose one")
    void aBareBiomeNamesTheOffer() throws Exception {
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(new IslandProgressionCommands(
                        locations,
                        mock(IslandBankService.class),
                        leaderboard,
                        biomes,
                        mock(PlayerSessionCoordinator.class),
                        inlineScheduler(),
                        () -> worth,
                        () -> mock(IslandMissionService.class),
                        Messages.bundled())
                .buildBiome());

        run("biome", player);

        assertThat(player.nextMessage()).contains("Biomes this server offers").contains("plains");
    }

    @Test
    @DisplayName("The level a player is shown counts the missions they finished")
    void theLevelCountsFinishedMissions() throws Exception {
        run("level", player);

        verify(worth).calculateScore(eq(ISLAND), eq(COMPLETED_MISSIONS), anyLong());
        verify(worth, never()).calculateScore(any(), eq(0), anyLong());
    }

    @Test
    @DisplayName("The recalculation counts them too, and that is the number that gets published")
    void theRecalculationCountsFinishedMissions() throws Exception {
        run("level recalculate", player);

        verify(worth)
                .triggerAsyncRecalculation(
                        eq(ISLAND), eq("skyblock_world"), any(), eq(COMPLETED_MISSIONS), anyLong(), any());
    }

    @Test
    @DisplayName("The short /is recalc reaches the same recalculation as /is level recalculate")
    void recalcIsTheSameCommand() throws Exception {
        run("recalc", player);

        verify(worth).triggerAsyncRecalculation(eq(ISLAND), any(), any(), eq(COMPLETED_MISSIONS), anyLong(), any());
    }

    @Test
    @DisplayName("The balance the score is given is the island bank's, not zero")
    void theScoreIsGivenTheRealBalance() throws Exception {
        run("level", player);

        verify(worth).calculateScore(any(), anyInt(), eq(5_000L));
    }

    @Test
    @DisplayName("/is worth and /is value land on the same handler as /is level")
    void worthAndValueAreAliases() throws Exception {
        run("worth", player);
        run("value", player);

        verify(worth, times(2)).calculateScore(eq(ISLAND), eq(COMPLETED_MISSIONS), anyLong());
    }

    @Test
    @DisplayName("A bare /is top asks for the level board")
    void topDefaultsToLevel() throws Exception {
        run("top", player);

        verify(leaderboard).getTop(LeaderboardCategory.LEVEL, 10);
    }

    @Test
    @DisplayName("/is top names the board the player asked for")
    void topTakesACategory() throws Exception {
        run("top worth", player);
        run("top bank", player);

        verify(leaderboard).getTop(LeaderboardCategory.WORTH, 10);
        verify(leaderboard).getTop(LeaderboardCategory.BANK, 10);
    }

    @Test
    @DisplayName("A category nobody publishes falls back to the level board rather than failing")
    void anUnknownCategoryFallsBackToLevel() throws Exception {
        run("top nonsense", player);

        verify(leaderboard).getTop(LeaderboardCategory.LEVEL, 10);
    }

    /** Says the island has reached this level, which is what a biome asks for. */
    private void islandAtLevel(long level) {
        when(worth.calculateScore(any(), anyInt(), anyLong()))
                .thenReturn(new IslandScoreBreakdown(0L, 0L, 0L, 0L, 0L, level, 0L, 0L));
    }

    /** Puts the caller on the island under a role holding exactly these permissions. */
    private void callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission... permissions) {
        com.uxplima.uxmskyblock.core.domain.island.Island island =
                com.uxplima.uxmskyblock.core.domain.island.Island.create(
                        ISLAND,
                        IslandBounds.fromCenterAndRadius(0, 0, 100),
                        new PlayerUuid(java.util.UUID.randomUUID()),
                        new ProfileId(java.util.UUID.randomUUID()),
                        java.time.Instant.now());
        com.uxplima.uxmskyblock.core.domain.island.IslandRole role =
                new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                        "CUSTOM",
                        400,
                        "Custom",
                        permissions.length == 0
                                ? java.util.EnumSet.noneOf(
                                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.class)
                                : java.util.EnumSet.of(permissions[0], permissions),
                        false);
        when(locations.findIsland(ISLAND))
                .thenReturn(Optional.of(island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                        new PlayerUuid(player.getUniqueId()), PROFILE, role, java.time.Instant.now()))));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The board tells a player where their own island stands")
    void theboardNamesTheCallersOwnPlace() throws Exception {
        when(leaderboard.getTop(any(LeaderboardCategory.class), anyInt())).thenReturn(List.of());
        when(leaderboard.getRank(LeaderboardCategory.LEVEL, ISLAND)).thenReturn(java.util.OptionalInt.of(42));

        run("top", player);

        String last = null;
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            last = line;
        }
        // This catalogue renders a key as the key, so the line the player got is the key itself.
        assertThat(last)
                .describedAs("a board prints ten and a server has hundreds, so a player outside the "
                        + "ten used to learn nothing from a board about them")
                .isEqualTo("leaderboard.your_rank");
        verify(leaderboard).getRank(LeaderboardCategory.LEVEL, ISLAND);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("An island the board does not hold is told so, which is not the same as no island")
    void anunplacedIslandIsToldSo() throws Exception {
        when(leaderboard.getTop(any(LeaderboardCategory.class), anyInt())).thenReturn(List.of());
        when(leaderboard.getRank(any(LeaderboardCategory.class), any())).thenReturn(java.util.OptionalInt.empty());

        run("top", player);

        String last = null;
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            last = line;
        }
        assertThat(last)
                .describedAs("not on the board is a different answer from having no island")
                .isEqualTo("leaderboard.your_rank_unplaced");
    }

    @Test
    @DisplayName("A biome the plugin publishes is applied to an island that has reached its level")
    void aKnownBiomeIsApplied() throws Exception {
        islandAtLevel(5L);

        run("biome desert", player);

        verify(biomes).applyBiome(ISLAND, IslandBiome.DESERT);
    }

    @Test
    @DisplayName("An island below the level the operator set does not get the biome")
    void abiomeAskingForALevelIsRefusedBelowIt() throws Exception {
        islandAtLevel(4L);

        run("biome desert", player);

        verify(biomes, never()).applyBiome(any(), any());
        assertThat(player.nextMessage())
                .describedAs("the level a biome asks for was written into the code and read nowhere")
                .isNotNull();
    }

    @Test
    @DisplayName("The level the operator writes is the level that counts, not the one shipped")
    void theOperatorsNumberIsTheOneThatCounts() throws Exception {
        islandAtLevel(1L);
        commands.useBiomeRules(new com.uxplima.uxmskyblock.bukkit.config.BiomeConfiguration(
                true, java.util.Map.of(IslandBiome.DESERT, 1), java.util.Map.of(IslandBiome.DESERT, true)));

        run("biome desert", player);

        verify(biomes).applyBiome(ISLAND, IslandBiome.DESERT);
    }

    @Test
    @DisplayName("A biome the operator took off the server is refused whatever the level")
    void abiomeTakenOffIsRefused() throws Exception {
        islandAtLevel(100L);
        commands.useBiomeRules(new com.uxplima.uxmskyblock.bukkit.config.BiomeConfiguration(
                true, java.util.Map.of(IslandBiome.DESERT, 0), java.util.Map.of(IslandBiome.DESERT, false)));

        run("biome desert", player);

        verify(biomes, never()).applyBiome(any(), any());
    }

    @Test
    @DisplayName("A role that may not change the biome cannot repaint the island the owner built")
    void arolewithoutTheBiomePermissionIsRefused() throws Exception {
        islandAtLevel(100L);
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_BREAK);

        run("biome desert", player);

        verify(biomes, never()).applyBiome(any(), any());
    }

    @Test
    @DisplayName("A role that may change the biome does")
    void arolewithTheBiomePermissionGoesThrough() throws Exception {
        islandAtLevel(100L);
        callerHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BIOME_CHANGE);

        run("biome desert", player);

        verify(biomes).applyBiome(ISLAND, IslandBiome.DESERT);
    }

    @Test
    @DisplayName("A biome nobody publishes never reaches the world")
    void anUnknownBiomeNeverReachesTheWorld() throws Exception {
        run("biome atlantis", player);

        verify(biomes, never()).applyBiome(any(), any());
    }

    @Test
    @DisplayName("/is biome with nothing after it names no biome and changes none, rather than guessing one")
    void biomeNeedsAName() throws Exception {
        run("biome", player);

        verify(biomes, never()).applyBiome(any(), any());
    }

    @Test
    @DisplayName("The console is told to be a player rather than scoring a nonexistent island")
    void theConsoleIsRefused() throws Exception {
        run("level", server.getConsoleSender());

        verify(worth, never()).calculateScore(any(), anyInt(), anyLong());
    }
}
