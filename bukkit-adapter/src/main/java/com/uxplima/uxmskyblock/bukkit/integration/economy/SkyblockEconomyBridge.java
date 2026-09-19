package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * High-reliability economy bridge orchestrating two-phase compensating transfers between
 * player personal wallets (via Vault/VaultUnlocked through {@link EconomyBridge}) and
 * distributed {@link IslandBankService} backed by durable write-ahead sagas.
 */
public final class SkyblockEconomyBridge {

    private final EconomyBridge economyBridge;
    private final IslandBankService bankService;
    private final SchedulerPort schedulerPort;
    private final @Nullable EconomySagaCoordinator sagaCoordinator;

    public SkyblockEconomyBridge(
            EconomyBridge economyBridge,
            IslandBankService bankService,
            SchedulerPort schedulerPort,
            @Nullable EconomySagaCoordinator sagaCoordinator) {
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.sagaCoordinator = sagaCoordinator;
    }

    public SkyblockEconomyBridge(
            EconomyBridge economyBridge, IslandBankService bankService, SchedulerPort schedulerPort) {
        this(economyBridge, bankService, schedulerPort, null);
    }

    public static SkyblockEconomyBridge createDefault(
            IslandBankService bankService, SchedulerPort schedulerPort, @Nullable EconomySagaPort sagaPort) {
        EconomyBridge bridge = EconomyBridge.orDummy();
        EconomySagaCoordinator coordinator = null;
        if (sagaPort != null) {
            BukkitVaultWalletAdapter walletAdapter = new BukkitVaultWalletAdapter(bridge);
            coordinator = new EconomySagaCoordinator(sagaPort, walletAdapter, bankService, Duration.ofSeconds(30));
        }
        return new SkyblockEconomyBridge(bridge, bankService, schedulerPort, coordinator);
    }

    public static SkyblockEconomyBridge createDefault(IslandBankService bankService, SchedulerPort schedulerPort) {
        return createDefault(bankService, schedulerPort, null);
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

    public boolean depositWallet(OfflinePlayer player, double amount) {
        if (!economyBridge.isPresent()) {
            return false;
        }
        return economyBridge.deposit(player, amount);
    }

    public int recoverPendingSagas(ServerNodeId nodeId) {
        if (sagaCoordinator != null) {
            return sagaCoordinator.recoverIncompleteSagas(Instant.now(), nodeId);
        }
        return 0;
    }

    /**
     * Deposits money from player's wallet into the island bank via durable two-phase saga.
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

        if (economyBridge.isPresent() && !economyBridge.has(player, (double) dollars)) {
            callback.accept(new BankTransactionOutcome.InsufficientFunds(
                    (long) (economyBridge.balance(player) * 100L), minorUnits));
            return;
        }

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = bankService.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                BankTransactionOutcome outcome =
                        new BankTransactionOutcome.AuthorityRejected("No island associated with profile " + profileId);
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
                return;
            }

            BankTransactionOutcome outcome;
            if (sagaCoordinator != null) {
                SagaId sagaId = SagaId.random();
                outcome = sagaCoordinator.executeDeposit(
                        sagaId, playerUuid, profileId, optIsland.get(), minorUnits, "VAULT", nodeId, Instant.now());
            } else {
                if (economyBridge.isPresent()) {
                    boolean withdrawn = economyBridge.withdraw(player, (double) dollars);
                    if (!withdrawn) {
                        outcome = new BankTransactionOutcome.AuthorityRejected(
                                "Failed to withdraw funds from your wallet.");
                    } else {
                        outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        if (!(outcome instanceof BankTransactionOutcome.Success)) {
                            economyBridge.deposit(player, (double) dollars);
                        }
                    }
                } else {
                    outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                }
            }

            final BankTransactionOutcome finalOutcome = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(finalOutcome));
        });
    }

    /**
     * Withdraws money from the island bank into the player's personal wallet via durable two-phase saga.
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
            Optional<IslandId> optIsland = bankService.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                BankTransactionOutcome outcome =
                        new BankTransactionOutcome.AuthorityRejected("No island associated with profile " + profileId);
                schedulerPort.onEntity(playerUuid, () -> callback.accept(outcome));
                return;
            }

            BankTransactionOutcome outcome;
            if (sagaCoordinator != null) {
                SagaId sagaId = SagaId.random();
                outcome = sagaCoordinator.executeWithdraw(
                        sagaId, playerUuid, profileId, optIsland.get(), minorUnits, "VAULT", nodeId, Instant.now());
            } else {
                outcome = bankService.withdraw(profileId, playerUuid, minorUnits, nodeId);
                if (outcome instanceof BankTransactionOutcome.Success && economyBridge.isPresent()) {
                    boolean deposited = economyBridge.deposit(player, (double) dollars);
                    if (!deposited) {
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
