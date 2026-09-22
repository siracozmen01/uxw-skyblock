package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
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
    private final Messages messages;

    /** Where money moving in and out of the island is written down for its members to read. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandBankCommands(
            IslandBankService islandBankService,
            IslandLocationService islandLocationService,
            SkyblockEconomyBridge economyBridge,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            Supplier<@Nullable IslandBankruptcyService> bankruptcyServiceProvider,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.economyBridge = Objects.requireNonNull(economyBridge, "economyBridge must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.bankruptcyServiceProvider =
                Objects.requireNonNull(bankruptcyServiceProvider, "bankruptcyServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
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
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<Long> optBalance = islandBankService.getBalanceMinorUnits(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optBalance.isEmpty()) {
                    send(player, "error.no_island");
                } else {
                    send(player, "bank.balance", Placeholder.unparsed("balance", money(optBalance.get())));
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
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            IslandId islandId = optIslandId.get();
            IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
            if (bankruptcyService == null) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "bank.upkeep_disabled"));
                return;
            }

            // The record is read here, not inside the hop below. It was read on the entity thread,
            // and a bankruptcy record that is not already cached is a query: one under the cursor of
            // every player who ever typed /is upkeep.
            Instant now = Instant.now();
            IslandBankruptcyRecord record = bankruptcyService.getBankruptcyRecord(islandId, now);

            schedulerPort.onEntity(playerUuid, () -> {
                Component status = messages.renderPlain(
                        player, "bank.status_" + record.status().name().toLowerCase(java.util.Locale.ROOT));

                send(player, "bank.status_header");
                send(player, "bank.status_state", Placeholder.component("status", status));
                send(player, "bank.status_debt", Placeholder.unparsed("debt", money(record.debtMinorUnits())));

                if (record.status() == BankruptcyStatus.GRACE && record.graceUntil() != null) {
                    java.time.Duration remaining = java.time.Duration.between(now, record.graceUntil());
                    long hours = Math.max(0, remaining.toHours());
                    long minutes = Math.max(0, remaining.toMinutesPart());
                    send(
                            player,
                            "bank.status_grace_remaining",
                            Placeholder.unparsed("hours", Long.toString(hours)),
                            Placeholder.unparsed("minutes", Long.toString(minutes)));
                } else if (record.status() == BankruptcyStatus.LOCKED) {
                    send(player, "bank.status_locked_notice");
                    send(player, "bank.status_locked_hint");
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
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optIslandId.isEmpty()) {
                    send(player, "error.no_island");
                    return;
                }
                IslandBankruptcyService bankruptcyService = bankruptcyServiceProvider.get();
                if (bankruptcyService == null) {
                    send(player, "bank.upkeep_disabled");
                    return;
                }

                IslandId islandId = optIslandId.get();
                Instant now = Instant.now();
                BankruptcyRemediationResult result = bankruptcyService.settleArrears(islandId, now, serverNodeId);

                if (result instanceof BankruptcyRemediationResult.Settled settled) {
                    send(
                            player,
                            "bank.settled",
                            Placeholder.unparsed("amount", money(settled.amountPaid())),
                            Placeholder.unparsed("balance", money(settled.remainingBalance())));
                } else if (result instanceof BankruptcyRemediationResult.InsufficientFunds ins) {
                    send(
                            player,
                            "bank.settle_insufficient",
                            Placeholder.unparsed("debt", money(ins.debtAmount())),
                            Placeholder.unparsed("balance", money(ins.currentBalance())));
                } else if (result instanceof BankruptcyRemediationResult.PaymentRefused refused) {
                    send(player, "bank.settle_refused", Placeholder.unparsed("debt", money(refused.debtAmount())));
                } else if (result instanceof BankruptcyRemediationResult.NotInArrears) {
                    send(player, "bank.not_in_arrears");
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
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        ifTheRoleAllowsIt(
                player,
                profileId,
                IslandPermission.BANK_DEPOSIT,
                () -> economyBridge.depositToIslandBank(player, profileId, amount, serverNodeId, outcome -> {
                    if (outcome instanceof BankTransactionOutcome.Success deposited) {
                        writeToTheFeed(
                                deposited,
                                profileId,
                                ActivityEventType.BANK_DEPOSIT,
                                "activity.bank_deposit",
                                player,
                                amount);
                        send(player, "bank.deposit_success", Placeholder.unparsed("amount", Long.toString(amount)));
                        IslandBankruptcyService bService = bankruptcyServiceProvider.get();
                        if (bService != null && bService.policy().autoRemediateOnDeposit()) {
                            // The bridge answers a refused deposit on the caller's own thread and a settled
                            // one off it, so which thread this outcome arrives on depends on the branch
                            // taken inside the bridge. Reading and settling arrears is two more round trips
                            // to the database either way, so it asks for the scheduler itself rather than
                            // trusting the thread it was handed.
                            schedulerPort.async(() -> islandLocationService
                                    .findIslandId(profileId)
                                    .ifPresent(islandId -> {
                                        BankruptcyRemediationResult rem =
                                                bService.settleArrears(islandId, Instant.now(), serverNodeId);
                                        if (rem instanceof BankruptcyRemediationResult.Settled settled) {
                                            send(
                                                    player,
                                                    "bank.auto_settled",
                                                    Placeholder.unparsed("amount", money(settled.amountPaid())));
                                        }
                                    }));
                        }
                    } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                        send(player, "bank.wallet_insufficient");
                    } else {
                        send(player, BankRefusalLines.keyFor(outcome, "bank.deposit_wallet_refused"));
                    }
                }));

        return Cmd.OK;
    }

    /**
     * Runs a money move only when the caller's role allows it.
     *
     * <p>The role editor has published a deposit permission and a withdraw permission since the
     * permission work and the bank read neither, so a member could empty the island bank whatever
     * their role said. The shipped member role does not hold the withdraw permission and has been
     * able to withdraw all along.
     *
     * <p>The role lives in a row, so it is read off the thread the command arrived on. The move runs
     * there too, because the move is the bridge and the bridge does its own storage work off the
     * thread as well: hopping back onto the player only to hop straight off again buys nothing and
     * costs a tick. The refusal is a message, and a message finds the player's thread by itself.
     *
     * <p>A caller with no island is not refused here: the move itself answers that, and it answers
     * it better.
     */
    private void ifTheRoleAllowsIt(Player player, ProfileId profileId, IslandPermission permission, Runnable move) {
        schedulerPort.async(() -> {
            Optional<Island> island =
                    islandLocationService.findIslandId(profileId).flatMap(islandLocationService::findIsland);
            if (island.isPresent()
                    && !island.get().isOwner(profileId)
                    && !island.get().hasPermission(profileId, permission)) {
                send(player, "bank.permission_denied");
                return;
            }
            move.run();
        });
    }

    /**
     * Writes a settled movement into the island's feed.
     *
     * <p>The island comes off the bank the movement just changed, so this costs no lookup. The
     * write itself is a row, and the bridge answers a settled movement on whichever thread it
     * happened to finish on, so it asks for the scheduler rather than trusting the one it was
     * handed.
     */
    private void writeToTheFeed(
            BankTransactionOutcome.Success movement,
            ProfileId profileId,
            ActivityEventType eventType,
            String messageKey,
            Player player,
            long amount) {
        if (!activityLog.isWriting()) {
            return;
        }
        IslandId islandId = movement.updatedBank().islandId();
        String playerName = player.getName();
        schedulerPort.async(() -> activityLog.recordForMembers(
                islandId,
                profileId,
                eventType,
                messageKey,
                java.util.Map.of("player", playerName, "amount", Long.toString(amount))));
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        ifTheRoleAllowsIt(
                player,
                profileId,
                IslandPermission.BANK_WITHDRAW,
                () -> economyBridge.withdrawFromIslandBank(player, profileId, amount, serverNodeId, outcome -> {
                    if (outcome instanceof BankTransactionOutcome.Success withdrawn) {
                        writeToTheFeed(
                                withdrawn,
                                profileId,
                                ActivityEventType.BANK_WITHDRAW,
                                "activity.bank_withdraw",
                                player,
                                amount);
                        send(player, "bank.withdraw_success", Placeholder.unparsed("amount", Long.toString(amount)));
                    } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                        send(player, "bank.withdraw_insufficient");
                    } else {
                        send(player, BankRefusalLines.keyFor(outcome, "bank.withdraw_wallet_refused"));
                    }
                }));

        return Cmd.OK;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    /** Minor units are stored as an integer; a player reads them as money. */
    private static String money(long minorUnits) {
        return String.format(java.util.Locale.ROOT, "%.2f", minorUnits / 100.0);
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
