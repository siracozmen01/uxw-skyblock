package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
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
import com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.social.DwellTimeNotMetException;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookMessageTooLongException;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookPinnedLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SelfRatingNotAllowedException;
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

    private static final Logger LOGGER = Logger.getLogger(IslandSocialCommands.class.getName());

    private static final int PAGE_SIZE = 10;
    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final Supplier<@Nullable IslandSocialService> socialServiceProvider;
    private final SpatialIslandIndex spatialIndex;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandSocialCommands(
            Supplier<@Nullable IslandSocialService> socialServiceProvider,
            SpatialIslandIndex spatialIndex,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.socialServiceProvider =
                Objects.requireNonNull(socialServiceProvider, "socialServiceProvider must not be null");
        this.spatialIndex = Objects.requireNonNull(spatialIndex, "spatialIndex must not be null");
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
                                .executes(this::executeSignGuestbook)))
                .then(moderation("pin", Moderation.PIN))
                .then(moderation("unpin", Moderation.UNPIN))
                .then(moderation("hide", Moderation.HIDE))
                .then(moderation("show", Moderation.SHOW))
                .then(moderation("delete", Moderation.DELETE));
    }

    /** What an owner can do to an entry on their own island's page. */
    private enum Moderation {
        PIN,
        UNPIN,
        HIDE,
        SHOW,
        DELETE
    }

    private LiteralArgumentBuilder<CommandSourceStack> moderation(String verb, Moderation what) {
        return Cmd.literal(verb)
                .then(Cmd.argument("entry", StringArgumentType.word()).executes(ctx -> executeModerate(ctx, what)));
    }

    /**
     * Pins, hides or deletes one entry on the guestbook the player is standing on.
     *
     * <p>The service could do all of this since the social work and nothing could ask it to: pin,
     * unpin, hide and delete had no caller anywhere, so an island's owner could not touch what a
     * visitor wrote on their page, and the operator's max-pinned governed nothing.
     *
     * <p>The entry is named by its id, which the read prints, so moderating cannot land on the
     * wrong line because somebody signed the book between reading it and acting on it.
     */
    private int executeModerate(CommandContext<CommandSourceStack> ctx, Moderation what) {
        String entryId = StringArgumentType.getString(ctx, "entry");
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            if (!mayModerate(islandId, profileId)) {
                onEntity(player, () -> send(player, "social.guestbook_no_permission"));
                return;
            }
            SocialSubjectRef subject = SocialSubjectRef.island(islandId);
            try {
                boolean changed =
                        switch (what) {
                            case PIN -> {
                                service.pinGuestbookEntry(subject, entryId);
                                yield true;
                            }
                            case UNPIN -> service.unpinGuestbookEntry(subject, entryId);
                            case HIDE -> service.hideGuestbookEntry(subject, entryId, true);
                            case SHOW -> service.hideGuestbookEntry(subject, entryId, false);
                            case DELETE -> service.deleteGuestbookEntry(subject, entryId);
                        };
                onEntity(player, () -> {
                    if (!changed) {
                        send(player, "social.guestbook_unknown_entry", Placeholder.unparsed("entry", entryId));
                        return;
                    }
                    send(player, doneMessage(what));
                });
            } catch (GuestbookPinnedLimitExceededException full) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "social.guestbook_pin_limit",
                                Placeholder.unparsed("max", Integer.toString(service.maxPinnedEntries()))));
            }
        });
    }

    private static String doneMessage(Moderation what) {
        return switch (what) {
            case PIN -> "social.guestbook_pinned_ok";
            case UNPIN -> "social.guestbook_unpinned";
            case HIDE -> "social.guestbook_hidden";
            case SHOW -> "social.guestbook_shown";
            case DELETE -> "social.guestbook_deleted";
        };
    }

    /** Whether this profile may moderate this island's page. Management, never membership. */
    private boolean mayModerate(IslandId islandId, ProfileId profileId) {
        return islandLocationService
                .findIsland(islandId)
                .map(island -> roleOf(island, profileId).hasPermission(IslandPermission.SETTINGS_MODIFY))
                .orElse(false);
    }

    private static IslandRole roleOf(Island island, ProfileId profileId) {
        if (island.ownerProfileId().equals(profileId)) {
            return IslandRole.OWNER;
        }
        IslandMember member = island.members().get(profileId);
        return member != null ? member.role() : IslandRole.VISITOR;
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
            // Whoever may moderate the page sees what is hidden on it. Hiding something you can
            // never see again is a one way door, and the show verb needs something to name.
            boolean moderator = mayModerate(islandId, profileId);
            List<GuestbookEntry> entries = service.listGuestbookEntries(subject, moderator, PAGE_SIZE, 0);
            onEntity(player, () -> {
                send(player, "social.guestbook_header");
                if (entries.isEmpty()) {
                    send(player, "social.guestbook_empty");
                    return;
                }
                for (GuestbookEntry entry : entries) {
                    send(
                            player,
                            lineFor(entry),
                            Placeholder.unparsed("message", entry.message()),
                            Placeholder.unparsed("entry", entry.reviewId()));
                }
            });
        });
    }

    private static String lineFor(GuestbookEntry entry) {
        if (entry.isHidden()) {
            return "social.guestbook_hidden_entry";
        }
        return entry.isPinned() ? "social.guestbook_pinned" : "social.guestbook_entry";
    }

    private int executeSignGuestbook(CommandContext<CommandSourceStack> ctx) {
        String message = StringArgumentType.getString(ctx, "message");
        return onIslandHere(ctx, (player, service, islandId, profileId) -> {
            try {
                service.signGuestbook(SocialSubjectRef.island(islandId), profileId, message, Instant.now());
                onEntity(player, () -> send(player, "social.guestbook_signed"));
            } catch (GuestbookMessageTooLongException tooLong) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "social.guestbook_too_long",
                                Placeholder.unparsed("max", Integer.toString(tooLong.maxLength()))));
            } catch (RuntimeException refused) {
                LOGGER.log(Level.WARNING, "A guestbook message could not be saved", refused);
                onEntity(player, () -> send(player, "social.guestbook_failed"));
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
            } catch (SelfRatingNotAllowedException own) {
                onEntity(player, () -> send(player, "social.rate_own_island"));
            } catch (DwellTimeNotMetException early) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "social.rate_stay_longer",
                                Placeholder.unparsed("time", formatDuration(early.remaining()))));
            } catch (RuntimeException refused) {
                // The player is told from the catalogue. What went wrong is an internal sentence in
                // English, and it used to be put in front of the player as it was.
                LOGGER.log(Level.WARNING, "A rating could not be saved", refused);
                onEntity(player, () -> send(player, "social.rate_failed"));
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
     * Resolves the island this command is about: the one the caller is standing on.
     *
     * <p>A guestbook you can only sign on your own island is a guestbook nobody signs, and a rating
     * you can only give your own island is worth nothing. The island under the player's feet is what
     * these three verbs are for.
     *
     * <p>Where they stand is read here, on the thread that owns them, and the lookup happens off it.
     * The spatial index answers from memory and never queries on the hot path; a miss means the
     * chunk has not been indexed yet, and the caller is told rather than made to wait.
     */
    private int onIslandHere(CommandContext<CommandSourceStack> ctx, SubjectAction action) {
        if (!(ctx.getSource().getSender() instanceof Player standing)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location at = standing.getLocation();
        if (at == null || at.getWorld() == null) {
            send(standing, "social.nowhere");
            return Cmd.OK;
        }
        String worldName = at.getWorld().getName();
        int blockX = at.getBlockX();
        int blockZ = at.getBlockZ();

        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    Optional<Island> here = spatialIndex.findIslandAt(worldName, blockX, blockZ);
                    if (here.isEmpty()) {
                        onEntity(player, () -> send(player, "social.not_on_an_island"));
                        return;
                    }
                    action.run(player, service, here.get().id(), profileId);
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

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(1, (duration.toMillis() + 999) / 1000);
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%ds", secs);
    }
}
