package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is guestbook}, {@code /is rate} and {@code /is bookmarks}: what players say about an
 * island and which ones they keep.
 *
 * <p>Four tables, a Bayesian rating summary, a pinnable guestbook, a visit counter and a bookmark
 * list, all built and all unreachable. Nothing in this plugin let a player write a review, give a
 * score or save an island, so every one of those tables was empty on every server that ran it.
 */
public final class IslandSocialCommands {

    private static final int PAGE_SIZE = 10;
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final Supplier<@Nullable IslandSocialService> socialServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandSocialCommands(
            Supplier<@Nullable IslandSocialService> socialServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.socialServiceProvider =
                Objects.requireNonNull(socialServiceProvider, "socialServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildGuestbook() {
        return Cmd.literal("guestbook")
                .executes(this::executeReadGuestbook)
                .then(Cmd.literal("sign")
                        .then(Cmd.argument("message", StringArgumentType.greedyString())
                                .executes(this::executeSignGuestbook)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRate() {
        return Cmd.literal("rate")
                .executes(this::executeShowRating)
                .then(Cmd.argument("score", IntegerArgumentType.integer(MIN_SCORE, MAX_SCORE))
                        .executes(this::executeRate));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBookmarks() {
        return Cmd.literal("bookmarks")
                .executes(this::executeListBookmarks)
                .then(Cmd.literal("toggle").executes(this::executeToggleBookmark));
    }

    private int executeReadGuestbook(CommandContext<CommandSourceStack> ctx) {
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            SocialSubjectRef subject = SocialSubjectRef.island(islandId);
            List<GuestbookEntry> entries = service.listGuestbookEntries(subject, false, PAGE_SIZE, 0);
            onEntity(player, () -> {
                send(player, "social.guestbook_header");
                if (entries.isEmpty()) {
                    send(player, "social.guestbook_empty");
                    return;
                }
                for (GuestbookEntry entry : entries) {
                    send(
                            player,
                            entry.isPinned() ? "social.guestbook_pinned" : "social.guestbook_entry",
                            Placeholder.unparsed("message", entry.message()));
                }
            });
        });
    }

    private int executeSignGuestbook(CommandContext<CommandSourceStack> ctx) {
        String message = StringArgumentType.getString(ctx, "message");
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            try {
                service.signGuestbook(SocialSubjectRef.island(islandId), profileId, message, Instant.now());
                onEntity(player, () -> send(player, "social.guestbook_signed"));
            } catch (RuntimeException refused) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "social.refused",
                                Placeholder.unparsed("reason", String.valueOf(refused.getMessage()))));
            }
        });
    }

    private int executeShowRating(CommandContext<CommandSourceStack> ctx) {
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            RatingSummary summary = service.getRatingSummary(SocialSubjectRef.island(islandId));
            onEntity(
                    player,
                    () -> send(
                            player,
                            "social.rating_summary",
                            Placeholder.unparsed("count", Integer.toString(summary.totalRatings())),
                            Placeholder.unparsed("average", String.format(Locale.US, "%.2f", summary.averageScore())),
                            Placeholder.unparsed(
                                    "weighted", String.format(Locale.US, "%.2f", summary.bayesianScore()))));
        });
    }

    private int executeRate(CommandContext<CommandSourceStack> ctx) {
        int score = IntegerArgumentType.getInteger(ctx, "score");
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            try {
                service.rate(SocialSubjectRef.island(islandId), profileId, score, Instant.now());
                onEntity(
                        player,
                        () -> send(player, "social.rated", Placeholder.unparsed("score", Integer.toString(score))));
            } catch (RuntimeException refused) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "social.refused",
                                Placeholder.unparsed("reason", String.valueOf(refused.getMessage()))));
            }
        });
    }

    private int executeListBookmarks(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    List<SocialSubjectRef> saved = service.listBookmarks(profileId);
                    onEntity(player, () -> {
                        send(player, "social.bookmarks_header");
                        if (saved.isEmpty()) {
                            send(player, "social.bookmarks_empty");
                            return;
                        }
                        for (SocialSubjectRef subject : saved) {
                            send(player, "social.bookmark_entry", Placeholder.unparsed("island", subject.key()));
                        }
                    });
                }));
    }

    private int executeToggleBookmark(CommandContext<CommandSourceStack> ctx) {
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            boolean saved = service.toggleBookmark(profileId, SocialSubjectRef.island(islandId));
            onEntity(player, () -> send(player, saved ? "social.bookmark_added" : "social.bookmark_removed"));
        });
    }

    /** What a social command works on: the island the caller is standing on, and who they are. */
    @FunctionalInterface
    private interface SubjectAction {
        void run(Player player, IslandSocialService service, IslandId islandId, ProfileId profileId);
    }

    @FunctionalInterface
    private interface ServiceAction {
        void run(Player player, IslandSocialService service, ProfileId profileId);
    }

    private int withService(CommandContext<CommandSourceStack> ctx, ServiceAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandSocialService service = socialServiceProvider.get();
        if (service == null) {
            send(player, "social.disabled");
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

    /**
     * Resolves the island this command is about.
     *
     * <p>Today that is the caller's own island, because resolving the one under their feet needs the
     * spatial index and this is the seam where that would go. A guestbook entry on your own island
     * is still an entry, and it is the first thing a server owner tries.
     */
    private int onIslandHere(CommandContext<CommandSourceStack> ctx, SubjectAction action) {
        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
                    if (optIsland.isEmpty()) {
                        onEntity(player, () -> send(player, "error.no_island"));
                        return;
                    }
                    action.run(player, service, optIsland.get(), profileId);
                }));
    }

    private void onEntity(Player player, Runnable work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                work.run();
            }
        });
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
