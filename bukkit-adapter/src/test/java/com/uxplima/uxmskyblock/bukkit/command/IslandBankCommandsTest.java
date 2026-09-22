package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is bank} runs, end to end, through Brigadier.
 *
 * <p>This is the command that moves a player's money, and it shipped without a test. The amount is
 * the part worth pinning: an argument type that accepts zero or a negative number is a withdrawal
 * that adds, and the type is the only thing standing in the way.
 */
class IslandBankCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock player;
    private SkyblockEconomyBridge bridge;
    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;
    private IslandBankService bank;
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

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();

        bridge = mock(SkyblockEconomyBridge.class);
        bank = mock(IslandBankService.class);
        when(bank.getBalanceMinorUnits(PROFILE)).thenReturn(Optional.of(12_345L));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));

        this.locations = locations;
        this.sessions = sessions;
        registerWith(null);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Upkeep status reads the bankruptcy record once, off the command thread")
    void upkeepStatusReadsTheRecordOnce() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus.SOLVENT,
                        0L,
                        null,
                        java.time.Instant.now()));
        registerWith(upkeep);

        run("bank status", player);

        verify(upkeep, org.mockito.Mockito.times(1))
                .getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any());
        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage()).describedAs("the state").isNotNull();
        assertThat(player.nextMessage()).describedAs("the debt").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Upkeep status on a locked island says what the player has to do about it")
    void alockedIslandSaysWhatToDo() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.getBankruptcyRecord(org.mockito.ArgumentMatchers.eq(ISLAND), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord(
                        ISLAND,
                        com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus.LOCKED,
                        4200L,
                        null,
                        java.time.Instant.now()));
        registerWith(upkeep);

        run("bank status", player);

        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).describedAs("what is owed").contains("42");
        assertThat(player.nextMessage()).describedAs("the locked notice").isNotNull();
        assertThat(player.nextMessage()).describedAs("the hint").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A node with no upkeep service says so rather than saying nothing")
    void noUpkeepServiceIsAnAnswer() throws Exception {
        run("bank status", player);

        assertThat(player.nextMessage()).describedAs("told upkeep is off").isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Paying the debt settles the arrears and says what it cost")
    void payingTheDebtSettles() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any()))
                .thenReturn(
                        new com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult.Settled(4200L, 1000L));
        registerWith(upkeep);

        run("bank paydebt", player);

        verify(upkeep).settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any());
        assertThat(player.nextMessage())
                .describedAs("what was paid and what is left")
                .contains("42");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Paying a debt that is not there says there is nothing to pay")
    void payingNothingSaysSo() throws Exception {
        com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService upkeep =
                mock(com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService.class);
        when(upkeep.settleArrears(org.mockito.ArgumentMatchers.eq(ISLAND), any(), any()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult.NotInArrears());
        registerWith(upkeep);

        run("bank paydebt", player);

        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).describedAs("nothing else").isNull();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Builds the command group again, this time with an upkeep service behind it. */
    private void registerWith(
            com.uxplima.uxmskyblock.core.application.bank.@org.jspecify.annotations.Nullable IslandBankruptcyService
                    upkeep) {
        IslandBankCommands commands = new IslandBankCommands(
                bank,
                locations,
                bridge,
                inlineScheduler(),
                ServerNodeId.of("node-1"),
                () -> upkeep,
                Messages.bundled(),
                sessions);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.build());
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    @Test
    @DisplayName("Depositing names the amount the player typed")
    void depositingNamesTheAmount() throws Exception {
        run("bank deposit 250", player);

        verify(bridge).depositToIslandBank(any(), eq(PROFILE), eq(250L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Withdrawing names the amount the player typed")
    void withdrawingNamesTheAmount() throws Exception {
        run("bank withdraw 40", player);

        verify(bridge).withdrawFromIslandBank(any(), eq(PROFILE), eq(40L), any(ServerNodeId.class), any());
    }

    @Test
    @DisplayName("Zero and negative amounts never reach the bank")
    void zeroAndNegativeNeverReachTheBank() {
        for (String amount : new String[] {"0", "-1", "-1000"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("bank deposit " + amount, player))
                    .describedAs("a deposit of %s must be refused by the argument type", amount)
                    .isInstanceOf(Exception.class);
        }
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
        verify(bridge, never()).withdrawFromIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A word where an amount belongs never reaches the bank")
    void aWordWhereAnAmountBelongsIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("bank deposit lots", player))
                .isInstanceOf(Exception.class);
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("The bare command reads the balance and moves nothing")
    void theBareCommandReadsTheBalance() throws Exception {
        run("bank", player);

        verify(bank).getBalanceMinorUnits(PROFILE);
        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("Balance is the same branch as the bare command")
    void balanceIsTheSameBranch() throws Exception {
        run("bank balance", player);

        verify(bank).getBalanceMinorUnits(PROFILE);
    }

    @Test
    @DisplayName("Upkeep with no bankruptcy service tells the player rather than throwing")
    void upkeepWithoutTheServiceIsExplained() throws Exception {
        player.nextMessage();

        run("bank upkeep", player);

        assertThat(player.nextMessage())
                .describedAs("a switched off subsystem must answer, not go quiet")
                .isNotNull();
    }

    @Test
    @DisplayName("The console moves no money")
    void theConsoleMovesNoMoney() throws Exception {
        run("bank deposit 100", server.getConsoleSender());

        verify(bridge, never()).depositToIslandBank(any(), any(), anyLong(), any(), any());
    }
}
