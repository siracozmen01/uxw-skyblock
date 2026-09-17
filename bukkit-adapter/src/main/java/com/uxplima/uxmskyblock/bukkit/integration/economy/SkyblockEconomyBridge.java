package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * High-reliability economy bridge orchestrating two-phase compensating transfers between
 * player personal wallets (via Vault/VaultUnlocked through {@link EconomyBridge}) and
 * distributed {@link IslandBankService}.
 */
public final class SkyblockEconomyBridge {

    private final EconomyBridge economyBridge;
    private final IslandBankService bankService;
    private final SchedulerPort schedulerPort;

    public SkyblockEconomyBridge(
            EconomyBridge economyBridge, IslandBankService bankService, SchedulerPort schedulerPort) {
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    public static SkyblockEconomyBridge createDefault(IslandBankService bankService, SchedulerPort schedulerPort) {
        return new SkyblockEconomyBridge(EconomyBridge.orDummy(), bankService, schedulerPort);
    }

    public boolean isEconomyAvailable() {
        return economyBridge.isPresent();
    }

    public double balance(OfflinePlayer player) {
        return economyBridge.balance(player);
    }

    public String format(double amount) {
        return economyBridge.format(amount);
    }

    /**
     * Deposits money from player's wallet into the island bank.
     * If bank mutation fails, wallet deduction is compensated and refunded immediately.
     */
    public void depositToIslandBank(
            Player player,
            ProfileId profileId,
            long dollars,
            ServerNodeId nodeId,
            Consumer<BankTransactionOutcome> callback) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (dollars <= 0) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected("Deposit amount must be greater than zero."));
            return;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        long minorUnits = dollars * 100L;

        if (economyBridge.isPresent()) {
            if (!economyBridge.has(player, (double) dollars)) {
                callback.accept(new BankTransactionOutcome.InsufficientFunds(
                        (long) (economyBridge.balance(player) * 100L), minorUnits));
                return;
            }

            boolean withdrawn = economyBridge.withdraw(player, (double) dollars);
            if (!withdrawn) {
                callback.accept(
                        new BankTransactionOutcome.AuthorityRejected("Failed to withdraw funds from your wallet."));
                return;
            }

            schedulerPort.async(() -> {
                BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                if (!(outcome instanceof BankTransactionOutcome.Success)) {
                    // Compensating transaction: refund player's personal wallet
                    economyBridge.deposit(player, (double) dollars);
                }
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
            });
        } else {
            schedulerPort.async(() -> {
                BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
            });
        }
    }

    /**
     * Withdraws money from the island bank into the player's personal wallet.
     * If wallet deposit fails, island bank deduction is compensated and refunded immediately.
     */
    public void withdrawFromIslandBank(
            Player player,
            ProfileId profileId,
            long dollars,
            ServerNodeId nodeId,
            Consumer<BankTransactionOutcome> callback) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (dollars <= 0) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected("Withdraw amount must be greater than zero."));
            return;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        long minorUnits = dollars * 100L;

        schedulerPort.async(() -> {
            BankTransactionOutcome outcome = bankService.withdraw(profileId, playerUuid, minorUnits, nodeId);
            if (outcome instanceof BankTransactionOutcome.Success) {
                if (economyBridge.isPresent()) {
                    boolean deposited = economyBridge.deposit(player, (double) dollars);
                    if (!deposited) {
                        // Compensating transaction: return funds to island bank
                        bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        outcome = new BankTransactionOutcome.AuthorityRejected(
                                "Failed to deposit funds into your wallet. Island bank funds refunded.");
                    }
                }
            }
            final BankTransactionOutcome finalOutcome = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(finalOutcome));
        });
    }
}
