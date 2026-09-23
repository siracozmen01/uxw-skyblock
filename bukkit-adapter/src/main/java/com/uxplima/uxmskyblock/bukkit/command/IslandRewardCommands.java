package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
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
import org.jspecify.annotations.Nullable;

/**
 * {@code /is rewards}: what a player has earned and not yet collected.
 *
 * <p>The reward inbox, its durable table, its two phase delivery and its idempotent claim have all
 * been here since the enterprise foundation work, and no command reached any of it. Rewards were
 * being issued into a table a player had no way to open. An inbox nobody can open is a list of
 * things the server took and did not give.
 */
public final class IslandRewardCommands {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandRewardCommands.class.getName());

    private final Supplier<@Nullable RewardInboxService> rewardServiceProvider;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandRewardCommands(
            Supplier<@Nullable RewardInboxService> rewardServiceProvider,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.rewardServiceProvider =
                Objects.requireNonNull(rewardServiceProvider, "rewardServiceProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("rewards")
                .executes(this::executeList)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("claim")
                        .executes(this::executeClaimAll)
                        .then(Cmd.argument("id", StringArgumentType.word()).executes(this::executeClaimOne)));
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        return withInbox(ctx, (player, service, profileId) -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            schedulerPort.async(() -> {
                List<RewardGrant> pending = service.getPendingRewards(profileId);
                schedulerPort.onEntity(playerUuid, () -> {
                    send(player, "rewards.header");
                    if (pending.isEmpty()) {
                        send(player, "rewards.empty");
                        return;
                    }
                    for (RewardGrant grant : pending) {
                        send(
                                player,
                                "rewards.entry",
                                Placeholder.unparsed(
                                        "id", grant.grantId().value().toString()),
                                Placeholder.unparsed("source", grant.sourceType()),
                                Placeholder.unparsed(
                                        "parts",
                                        Integer.toString(grant.components().size())));
                    }
                    send(player, "rewards.claim_hint");
                });
            });
        });
    }

    private int executeClaimAll(CommandContext<CommandSourceStack> ctx) {
        return withInbox(ctx, (player, service, profileId) -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            // Delivery puts items into an inventory, which is the viewer's own thread's business. The
            // service hands each component to its handler, and the item handler already moves itself
            // there; what runs here is the reading and the deciding.
            schedulerPort.async(() -> {
                ClaimAllRewardsResult result = service.claimAllRewards(profileId);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (result.totalProcessed() == 0) {
                        send(player, "rewards.empty");
                        return;
                    }
                    send(
                            player,
                            "rewards.claimed_all",
                            Placeholder.unparsed("claimed", Integer.toString(result.successfullyClaimed())),
                            Placeholder.unparsed("total", Integer.toString(result.totalProcessed())));
                    if (result.failedOrIncomplete() > 0) {
                        send(
                                player,
                                "rewards.claim_partial",
                                Placeholder.unparsed("failed", Integer.toString(result.failedOrIncomplete())));
                    }
                });
            });
        });
    }

    private int executeClaimOne(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "id");
        return withInbox(ctx, (player, service, profileId) -> {
            RewardGrantId grantId;
            try {
                grantId = RewardGrantId.fromString(raw);
            } catch (IllegalArgumentException notAnId) {
                send(player, "rewards.unknown", Placeholder.unparsed("id", raw));
                return;
            }
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            schedulerPort.async(() -> {
                ClaimRewardResult result;
                try {
                    result = service.claimReward(grantId, profileId);
                } catch (RuntimeException e) {
                    schedulerPort.onEntity(
                            playerUuid, () -> send(player, "rewards.unknown", Placeholder.unparsed("id", raw)));
                    return;
                }
                schedulerPort.onEntity(playerUuid, () -> {
                    if (result.success()) {
                        send(player, "rewards.claimed_one", Placeholder.unparsed("id", raw));
                        return;
                    }
                    // The player is told what kind of refusal this is, from the catalogue. The
                    // reason itself is an internal sentence in English, and it used to be put in
                    // front of the player as it was; it goes to the log instead.
                    if (result.refusal() == ClaimRewardResult.Refusal.ALREADY_BEING_CLAIMED) {
                        send(player, "rewards.claim_in_progress", Placeholder.unparsed("id", raw));
                        return;
                    }
                    LOGGER.info(() -> "Reward " + raw + " for " + player.getName() + " was not handed over: "
                            + result.optFailureReason()
                                    .orElse(result.finalState().name()));
                    send(player, "rewards.claim_failed", Placeholder.unparsed("id", raw));
                });
            });
        });
    }

    /** What a reward command needs before it can do anything: a player, the service, a profile. */
    @FunctionalInterface
    private interface InboxAction {
        void run(Player player, RewardInboxService service, ProfileId profileId);
    }

    private int withInbox(CommandContext<CommandSourceStack> ctx, InboxAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        RewardInboxService service = rewardServiceProvider.get();
        if (service == null) {
            send(player, "rewards.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        action.run(player, service, optProfile.get());
        return Cmd.OK;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
