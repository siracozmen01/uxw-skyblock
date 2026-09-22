package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome.AuthorityRejected.Kind;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What a player reads when their money did not move.
 *
 * <p>A refusal used to reach the player as its own reason: an English sentence from the code, often
 * with an island's id in it, inside a line the player read in their own language. A clash with
 * another write and a repeated operation read as a Java record.
 */
class WhatTheBankTellsAPlayerTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());
    private static final String ISLAND_TEXT = UUID.randomUUID().toString();

    private PlayerMock player;
    private SkyblockEconomyBridge bridge;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        player = createPlayer("Banker");
        bridge = mock(SkyblockEconomyBridge.class);
        IslandBankService bank = mock(IslandBankService.class);
        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(IslandId.of(UUID.randomUUID())));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        IslandBankCommands commands = new IslandBankCommands(
                bank,
                locations,
                bridge,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                () -> null,
                Messages.bundled(),
                sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("No refusal ever puts its own reason in front of the player")
    void noRefusalShowsItsReason(Kind kind) throws Exception {
        String reason = "No island associated with profile " + ISLAND_TEXT;
        answerDeposit(new BankTransactionOutcome.AuthorityRejected(kind, reason));
        answerWithdraw(new BankTransactionOutcome.AuthorityRejected(kind, reason));

        List<String> said = new ArrayList<>();
        said.addAll(run("bank deposit 5"));
        said.addAll(run("bank withdraw 5"));

        assertThat(said).hasSize(2).noneMatch(line -> line.contains(ISLAND_TEXT) || line.contains("associated"));
    }

    @Test
    @DisplayName("A player with no island is told so, not handed a profile id")
    void noIslandIsSaidPlainly() throws Exception {
        answerDeposit(new BankTransactionOutcome.AuthorityRejected(Kind.NO_ISLAND, "No island " + ISLAND_TEXT));

        assertThat(run("bank deposit 5")).singleElement().asString().doesNotContain(ISLAND_TEXT);
    }

    @Test
    @DisplayName("A wallet that would not pay reads differently from one that would not take")
    void theWalletLineFollowsTheDirection() throws Exception {
        answerDeposit(new BankTransactionOutcome.AuthorityRejected(Kind.WALLET_REFUSED, "x"));
        answerWithdraw(new BankTransactionOutcome.AuthorityRejected(Kind.WALLET_REFUSED, "x"));

        assertThat(run("bank deposit 5")).singleElement().asString().contains("nothing was deposited");
        assertThat(run("bank withdraw 5")).singleElement().asString().contains("back in the island bank");
    }

    @Test
    @DisplayName("Money that could not be put back tells the player to fetch an administrator")
    void aFailedRefundSendsThePlayerToAnAdministrator() throws Exception {
        answerWithdraw(new BankTransactionOutcome.AuthorityRejected(Kind.REFUND_FAILED, "x"));

        assertThat(run("bank withdraw 5")).singleElement().asString().contains("administrator");
    }

    @Test
    @DisplayName("Another server holding the island says to try again, not which node holds it")
    void anotherServerSaysTryAgain() throws Exception {
        answerDeposit(new BankTransactionOutcome.AuthorityRejected(Kind.NO_AUTHORITY, "held by node-7"));

        assertThat(run("bank deposit 5"))
                .singleElement()
                .asString()
                .contains("Another server")
                .doesNotContain("node-7");
    }

    @Test
    @DisplayName("A clash with another write and a repeated transfer never read as a Java record")
    void noRecordReachesThePlayer() throws Exception {
        answerDeposit(new BankTransactionOutcome.StaleVersion(3, 4));
        answerWithdraw(new BankTransactionOutcome.DuplicateOperation(UUID.randomUUID(), "seen"));

        String busy = run("bank deposit 5").getFirst();
        String repeated = run("bank withdraw 5").getFirst();

        assertThat(busy).contains("same moment").doesNotContain("StaleVersion").doesNotContain("expectedVersion");
        assertThat(repeated).contains("already made").doesNotContain("DuplicateOperation");
    }

    @Test
    @DisplayName("A Turkish player reads a refusal wholly in Turkish")
    void aTurkishPlayerReadsTurkish() throws Exception {
        player.setLocale(Locale.forLanguageTag("tr"));
        answerDeposit(new BankTransactionOutcome.AuthorityRejected(Kind.NO_AUTHORITY, "Authority lease has expired"));

        assertThat(run("bank deposit 5"))
                .singleElement()
                .asString()
                .contains("başka bir sunucu")
                .doesNotContain("Authority");
    }

    private void answerDeposit(BankTransactionOutcome outcome) {
        doAnswer(call -> {
                    call.<Consumer<BankTransactionOutcome>>getArgument(4).accept(outcome);
                    return null;
                })
                .when(bridge)
                .depositToIslandBank(any(), eq(PROFILE), anyLong(), any(), any());
    }

    private void answerWithdraw(BankTransactionOutcome outcome) {
        doAnswer(call -> {
                    call.<Consumer<BankTransactionOutcome>>getArgument(4).accept(outcome);
                    return null;
                })
                .when(bridge)
                .withdrawFromIslandBank(any(), eq(PROFILE), anyLong(), any(), any());
    }

    private List<String> run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(player);
        dispatcher.execute(line, source);

        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
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
