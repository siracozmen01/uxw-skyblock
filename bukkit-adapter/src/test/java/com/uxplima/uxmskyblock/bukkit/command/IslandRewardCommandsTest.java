package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.reward.ClaimAllRewardsResult;
import com.uxplima.uxmskyblock.core.application.reward.ClaimRewardResult;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is rewards} runs, end to end, through Brigadier.
 *
 * <p>This is the command that hands players what they earned, and it shipped without a test. The id
 * form in particular is the kind of thing that compiles and fails in front of a player: a word that
 * is not a grant id reaches the service as one, and what the service does with it is nobody's guess
 * until somebody tries.
 */
class IslandRewardCommandsTest {

    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private RewardInboxService rewards;
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
        return scheduler;
    }

    private static RewardGrant pending(RewardGrantId id) {
        return new RewardGrant(
                id,
                PROFILE,
                "SEASON_PAYOUT",
                "season-1",
                RewardGrantState.PENDING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        rewards = mock(RewardInboxService.class);

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandRewardCommands commands = new IslandRewardCommands(
                () -> rewards,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
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
    @DisplayName("The bare command lists what is waiting")
    void theBareCommandLists() throws Exception {
        when(rewards.getPendingRewards(PROFILE)).thenReturn(List.of(pending(RewardGrantId.random())));

        run("rewards", player);

        verify(rewards).getPendingRewards(PROFILE);
        verify(rewards, never()).claimAllRewards(any());
    }

    @Test
    @DisplayName("Claiming with no id claims everything")
    void claimingWithNoIdClaimsAll() throws Exception {
        when(rewards.claimAllRewards(PROFILE)).thenReturn(new ClaimAllRewardsResult(2, 2, 0, List.of()));

        run("rewards claim", player);

        verify(rewards).claimAllRewards(PROFILE);
    }

    @Test
    @DisplayName("Claiming by id claims that one")
    void claimingByIdClaimsThatOne() throws Exception {
        RewardGrantId id = RewardGrantId.random();
        when(rewards.claimReward(eq(id), eq(PROFILE))).thenReturn(ClaimRewardResult.success(id, 1));

        run("rewards claim " + id.value(), player);

        verify(rewards).claimReward(eq(id), eq(PROFILE));
    }

    @Test
    @DisplayName("A word that is not a grant id never reaches the inbox")
    void aWordThatIsNotAnIdIsRefusedHere() throws Exception {
        run("rewards claim not-an-id", player);

        verify(rewards, never()).claimReward(any(), any());
        assertThat(player.nextMessage())
                .describedAs("the player is told, rather than the service being handed nonsense")
                .isNotNull();
    }

    @Test
    @DisplayName("An inbox that is switched off refuses rather than throwing")
    void aDisabledInboxRefuses() throws Exception {
        IslandRewardCommands offline = new IslandRewardCommands(
                () -> null,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                mock(PlayerSessionCoordinator.class));
        CommandDispatcher<CommandSourceStack> offlineDispatcher = new CommandDispatcher<>();
        offlineDispatcher.register(offline.build());
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        player.nextMessage();

        offlineDispatcher.execute("rewards", source);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("A claim the service refuses tells the player why")
    void aRefusedClaimIsExplained() throws Exception {
        RewardGrantId id = RewardGrantId.random();
        when(rewards.claimReward(eq(id), eq(PROFILE)))
                .thenReturn(new ClaimRewardResult(
                        false, id, RewardGrantState.RECOVERY_REQUIRED, 0, 1, "no room in your inventory"));
        player.nextMessage();

        run("rewards claim " + id.value(), player);

        assertThat(player.nextMessage())
                .describedAs("a reward that did not arrive must say so")
                .isNotNull();
    }

    @Test
    @DisplayName("A refused claim is told from the catalogue, never in the service's own words")
    void theServicesReasonNeverReachesThePlayer() throws Exception {
        RewardGrantId id = RewardGrantId.random();
        when(rewards.claimReward(eq(id), eq(PROFILE)))
                .thenReturn(ClaimRewardResult.failure(
                        id, RewardGrantState.RECOVERY_REQUIRED, 0, 1, "Insufficient inventory space for item reward"));
        player.nextMessage();

        run("rewards claim " + id.value(), player);

        String told = player.nextMessage();
        assertThat(told).isNotNull();
        assertThat(told).doesNotContain("Insufficient").isEqualTo("rewards.claim_failed");
    }

    @Test
    @DisplayName("No catalogue's refused-claim line has room for the service's reason")
    void theRefusedClaimLineCarriesNoReason() throws Exception {
        for (String language : new String[] {"en", "tr"}) {
            try (var in = getClass().getResourceAsStream("/messages/messages_" + language + ".conf")) {
                String catalogue = new String(
                        java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                String line = catalogue
                        .lines()
                        .filter(l -> l.trim().startsWith("claim_failed"))
                        .findFirst()
                        .orElseThrow();
                assertThat(line).describedAs(language).doesNotContain("<reason>");
            }
        }
    }

    @Test
    @DisplayName("A claim of a reward already being claimed says so")
    void aClaimUnderWayIsSaidToBe() throws Exception {
        RewardGrantId id = RewardGrantId.random();
        when(rewards.claimReward(eq(id), eq(PROFILE)))
                .thenReturn(ClaimRewardResult.alreadyBeingClaimed(id, RewardGrantState.CLAIMING, 1));
        player.nextMessage();

        run("rewards claim " + id.value(), player);

        assertThat(player.nextMessage()).isEqualTo("rewards.claim_in_progress");
    }
}
