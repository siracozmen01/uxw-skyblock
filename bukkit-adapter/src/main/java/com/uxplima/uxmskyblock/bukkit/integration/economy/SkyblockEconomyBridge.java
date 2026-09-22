package com.uxplima.uxmskyblock.bukkit.integration.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmlib.hook.economy.EconomyServiceListener;
import com.uxplima.uxmlib.hook.economy.RebindingEconomyBridge;
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

    private static final Logger LOGGER = Logger.getLogger(SkyblockEconomyBridge.class.getName());

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
        // Rebinding rather than resolved once. An economy plugin that registers its service after this
        // one enables, which is every economy plugin once this one loads at startup, used to leave the
        // bridge on the dummy for as long as the server ran and the island bank refusing every move.
        EconomyBridge bridge = new RebindingEconomyBridge();
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

    /**
     * The listener that keeps the economy current as economy plugins register and unregister, or
     * nothing when this bridge was handed a fixed economy.
     */
    public Optional<Listener> economyRebinder() {
        return economyBridge instanceof RebindingEconomyBridge rebinding
                ? Optional.of(new EconomyServiceListener(rebinding))
                : Optional.empty();
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
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT,
                    "Deposit amount must be greater than zero."));
            return;
        }

        // With no economy plugin there is no wallet. The wallet adapter used to answer every
        // question about it with yes, so a deposit charged nothing and filled the island bank
        // without limit, and a withdrawal emptied it into nothing and called that a success.
        if (!economyBridge.isPresent()) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY,
                    "Deposit refused: no economy plugin is installed."));
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
                BankTransactionOutcome outcome = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + profileId);
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
                                BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                                "Failed to withdraw funds from your wallet.");
                    } else {
                        outcome = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        if (!(outcome instanceof BankTransactionOutcome.Success)) {
                            // The wallet has already been charged. If the refund does not land the
                            // player has paid for nothing, so the answer cannot be the bank's
                            // refusal: it has to say where the money went.
                            boolean refunded = economyBridge.deposit(player, (double) dollars);
                            if (!refunded) {
                                LOGGER.log(
                                        Level.SEVERE,
                                        "Wallet refund failed after a rejected island bank deposit."
                                                + " player={0} amountMinorUnits={1} bankOutcome={2}",
                                        new Object[] {playerUuid, minorUnits, outcome});
                                outcome = new BankTransactionOutcome.AuthorityRejected(
                                        BankTransactionOutcome.AuthorityRejected.Kind.REFUND_FAILED,
                                        "The island bank refused the deposit and your wallet could not be"
                                                + " refunded. Contact an administrator with the time of this"
                                                + " message.");
                            }
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
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT,
                    "Withdraw amount must be greater than zero."));
            return;
        }

        // With no economy plugin there is no wallet. The wallet adapter used to answer every
        // question about it with yes, so a deposit charged nothing and filled the island bank
        // without limit, and a withdrawal emptied it into nothing and called that a success.
        if (!economyBridge.isPresent()) {
            callback.accept(new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.NO_ECONOMY,
                    "Withdraw refused: no economy plugin is installed."));
            return;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        long minorUnits = dollars * 100L;

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = bankService.findIslandIdByProfileId(profileId);
            if (optIsland.isEmpty()) {
                BankTransactionOutcome outcome = new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_ISLAND,
                        "No island associated with profile " + profileId);
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
                        // The island bank has already been debited. Putting it back can fail too, and
                        // this used to throw that answer away and tell the player it had worked: the
                        // money was gone from the bank, never in their wallet, and the line on screen
                        // said it had been refunded.
                        BankTransactionOutcome refund = bankService.deposit(profileId, playerUuid, minorUnits, nodeId);
                        if (refund instanceof BankTransactionOutcome.Success) {
                            outcome = new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                                    "Failed to deposit funds into your wallet. Island bank funds refunded.");
                        } else {
                            LOGGER.log(
                                    Level.SEVERE,
                                    "Island bank refund failed after a wallet deposit that would not land."
                                            + " player={0} amountMinorUnits={1} refundOutcome={2}",
                                    new Object[] {playerUuid, minorUnits, refund});
                            outcome = new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.REFUND_FAILED,
                                    "Your wallet would not take the funds and the island bank could not be"
                                            + " refunded. Contact an administrator with the time of this"
                                            + " message.");
                        }
                    }
                }
            }

            final BankTransactionOutcome finalOutcome = outcome;
            schedulerPort.onEntity(playerUuid, () -> callback.accept(finalOutcome));
        });
    }
}
