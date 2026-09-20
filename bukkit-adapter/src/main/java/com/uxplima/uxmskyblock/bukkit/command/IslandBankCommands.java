package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island bank and upkeep commands:
 * /is bank [balance|status|upkeep|paydebt|deposit|withdraw]
 */
public final class IslandBankCommands {

    private final IslandBankService islandBankService;
    private final IslandLocationService islandLocationService;
    private final SkyblockEconomyBridge economyBridge;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandBankCommands(
            IslandBankService islandBankService,
            IslandLocationService islandLocationService,
            SkyblockEconomyBridge economyBridge,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.bankruptcyServiceProvider =
                Objects.requireNonNull(bankruptcyServiceProvider, "bankruptcyServiceProvider must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("bank")
                .executes(this::executeBankBalance)
                .then(Cmd.literal("balance").executes(this::executeBankBalance))
                .then(Cmd.literal("status").executes(this::executeBankStatus))
                .then(Cmd.literal("upkeep").executes(this::executeBankStatus))
                .then(Cmd.literal("paydebt").executes(this::executeBankPayDebt))
                .then(Cmd.literal("deposit")
                        .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                .executes(this::executeBankDeposit)))
                .then(Cmd.literal("withdraw")
                        .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                .executes(this::executeBankWithdraw)));
    }

    private int executeBankBalance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Long> optBalance = islandBankService.getBalanceMinorUnits(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optBalance.isEmpty()) {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                } else {
                    send(
                            player,
                            Component.text(
                                    "Island Bank Balance: $" + (optBalance.get() / 100.0), NamedTextColor.GREEN));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankStatus(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, Component.text("You do not belong to an island.", NamedTextColor.RED));
                    return;
                }
                IslandId islandId = optIslandId.get();
                IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
                if (bankruptcyService == null) {
                    send(
                            player,
                            Component.text(
                                    "Island upkeep and bankruptcy subsystem is not active.", NamedTextColor.GRAY));
                    return;
                }

                Instant now = Instant.now();
                IslandBankruptcyRecord record = bankruptcyService.getBankruptcyRecord(islandId, now);
                NamedTextColor statusColor =
                        switch (record.status()) {
                            case SOLVENT -> NamedTextColor.GREEN;
                            case GRACE -> NamedTextColor.YELLOW;
                            case LOCKED -> NamedTextColor.RED;
                        };

                send(player, Component.text("--- Island Bank & Upkeep Status ---", NamedTextColor.GOLD));
                send(
                        player,
                        Component.text("Bankruptcy Status: ", NamedTextColor.GRAY)
                                .append(Component.text(record.status().name(), statusColor)));
                send(
                        player,
                        Component.text(
                                "Outstanding Debt: $" + String.format("%.2f", record.debtMinorUnits() / 100.0),
                                NamedTextColor.GRAY));

                if (record.status() == BankruptcyStatus.GRACE && record.graceUntil() != null) {
                    java.time.Duration remaining = java.time.Duration.between(now, record.graceUntil());
                    long hours = Math.max(0, remaining.toHours());
                    long minutes = Math.max(0, remaining.toMinutesPart());
                    send(
                            player,
                            Component.text(
                                    "Grace Remaining: " + hours + "h " + minutes + "m (until lockout)",
                                    NamedTextColor.YELLOW));
                } else if (record.status() == BankruptcyStatus.LOCKED) {
                    send(
                            player,
                            Component.text(
                                    "Island is LOCKED! Spawners, crops, and visitor entries are suppressed.",
                                    NamedTextColor.RED));
                    send(player, Component.text("Use /is bank paydebt or deposit to remediate.", NamedTextColor.AQUA));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankPayDebt(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, Component.text("You do not belong to an island.", NamedTextColor.RED));
                    return;
                }
                IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
                if (bankruptcyService == null) {
                    send(
                            player,
                            Component.text(
                                    "Island upkeep and bankruptcy subsystem is not active.", NamedTextColor.GRAY));
                    return;
                }

                IslandId islandId = optIslandId.get();
                Instant now = Instant.now();
                BankruptcyRemediationResult result = bankruptcyService.settleArrears(islandId, now, serverNodeId);

                if (result instanceof BankruptcyRemediationResult.Settled settled) {
                    send(
                            player,
                            Component.text(
                                    "Successfully settled $"
                                            + String.format("%.2f", settled.amountPaid() / 100.0)
                                            + " in arrears! New bank balance: $"
                                            + String.format("%.2f", settled.remainingBalance() / 100.0)
                                            + ". Island is now SOLVENT.",
                                    NamedTextColor.GREEN));
                } else if (result instanceof BankruptcyRemediationResult.InsufficientFunds ins) {
                    send(
                            player,
                            Component.text(
                                    "Insufficient bank funds to settle arrears! Debt: $"
                                            + String.format("%.2f", ins.debtAmount() / 100.0)
                                            + ", Available bank balance: $"
                                            + String.format("%.2f", ins.currentBalance() / 100.0)
                                            + ". Deposit more funds to clear debt.",
                                    NamedTextColor.RED));
                } else if (result instanceof BankruptcyRemediationResult.NotInArrears) {
                    send(
                            player,
                            Component.text(
                                    "Your island has no outstanding arrears and is fully SOLVENT.",
                                    NamedTextColor.GREEN));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        economyBridge.depositToIslandBank(player, profileId, amount, serverNodeId, outcome -> {
            if (outcome instanceof BankTransactionOutcome.Success) {
                send(player, Component.text("Deposited $" + amount + " into the island bank.", NamedTextColor.GREEN));
                IslandBankruptcyService bService = bankruptcyServiceProvider.get();
                if (bService != null && bService.policy().autoRemediateOnDeposit()) {
                    islandLocationService.findIslandId(profileId).ifPresent(islandId -> {
                        BankruptcyRemediationResult rem = bService.settleArrears(islandId, Instant.now(), serverNodeId);
                        if (rem instanceof BankruptcyRemediationResult.Settled settled) {
                            send(
                                    player,
                                    Component.text(
                                            "Outstanding arrears of $"
                                                    + String.format("%.2f", settled.amountPaid() / 100.0)
                                                    + " were automatically settled from deposit! Island is now SOLVENT.",
                                            NamedTextColor.GOLD));
                        }
                    });
                }
            } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                send(player, Component.text("Insufficient funds in your personal wallet.", NamedTextColor.RED));
            } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rej) {
                send(player, Component.text("Deposit rejected: " + rej.reason(), NamedTextColor.RED));
            } else {
                send(player, Component.text("Deposit failed: " + outcome, NamedTextColor.RED));
            }
        });

        return Cmd.OK;
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        economyBridge.withdrawFromIslandBank(player, profileId, amount, serverNodeId, outcome -> {
            if (outcome instanceof BankTransactionOutcome.Success) {
                send(player, Component.text("Withdrew $" + amount + " from the island bank.", NamedTextColor.GREEN));
            } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                send(player, Component.text("Insufficient funds in the island bank.", NamedTextColor.RED));
            } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rej) {
                send(player, Component.text("Withdrawal rejected: " + rej.reason(), NamedTextColor.RED));
            } else {
                send(player, Component.text("Withdrawal failed: " + outcome, NamedTextColor.RED));
            }
        });

        return Cmd.OK;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }
}
