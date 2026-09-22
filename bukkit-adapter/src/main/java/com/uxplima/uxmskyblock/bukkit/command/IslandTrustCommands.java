package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is trust}, {@code /is untrust} and {@code /is trusted}.
 *
 * <p>The grant subsystem has been here since the access work: a service, a normalized pair of
 * tables, four termination policies, a purge on a timer and a check on every block a non member
 * touches. Nothing could make a grant. The whole write side, issueGrant and revokeGrant, had no
 * caller anywhere, so the check on every click asked about grants that could not exist.
 *
 * <p>A trusted player is never a member. The grant carries whatever the operator's
 * {@code trust-permissions} names and the domain refuses management and bank withdrawal on top of
 * that, so trust cannot be a way in to the island's membership or its money.
 */
public final class IslandTrustCommands {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandTrustCommands.class.getName());

    /** The root type the protection listener asks about, so a grant has to be filed under it. */
    public static final String ISLAND_ROOT_TYPE = "ISLAND";

    /** How a moment is written, in the server's own zone. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final Supplier<@Nullable TemporaryAccessService> accessServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Supplier<TemporaryAccessConfiguration> configurationProvider;
    private final Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    /** The inbox a player reads on their next join, for a revocation they were not here to see. */
    private volatile @Nullable NotificationService notificationService;

    /** Where trust given and taken back is written down for the island's members to read. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    public IslandTrustCommands(
            Supplier<@Nullable TemporaryAccessService> accessServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Supplier<TemporaryAccessConfiguration> configurationProvider,
            Supplier<CurrentNodeProcessIdentity> nodeIdentitySupplier,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.accessServiceProvider =
                Objects.requireNonNull(accessServiceProvider, "accessServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.configurationProvider =
                Objects.requireNonNull(configurationProvider, "configurationProvider must not be null");
        this.nodeIdentitySupplier =
                Objects.requireNonNull(nodeIdentitySupplier, "nodeIdentitySupplier must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** Tells this command group where to leave a notice for a player who is not here. */
    public void useNotifications(@Nullable NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    /** {@code /is trust <player> [until]}. */
    public LiteralArgumentBuilder<CommandSourceStack> buildTrust() {
        return Cmd.literal("trust")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Player online : Bukkit.getOnlinePlayers()) {
                                builder.suggest(online.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeTrust(ctx, null))
                        .then(Cmd.argument("until", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    builder.suggest("session");
                                    builder.suggest("restart");
                                    builder.suggest("forever");
                                    builder.suggest("30m");
                                    builder.suggest("2h");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> executeTrust(ctx, StringArgumentType.getString(ctx, "until")))));
    }

    /** {@code /is untrust <player or grant id>}. */
    public LiteralArgumentBuilder<CommandSourceStack> buildUntrust() {
        return Cmd.literal("untrust")
                .then(Cmd.argument("who", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Player online : Bukkit.getOnlinePlayers()) {
                                builder.suggest(online.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(this::executeUntrust));
    }

    /** {@code /is trusted}: who holds a grant on this island right now. */
    public LiteralArgumentBuilder<CommandSourceStack> buildTrusted() {
        return Cmd.literal("trusted").executes(this::executeTrusted);
    }

    private int executeTrust(CommandContext<CommandSourceStack> ctx, @Nullable String untilRaw) {
        Player player = playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = accessServiceProvider.get();
        if (service == null) {
            send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        String targetName = StringArgumentType.getString(ctx, "player");
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            send(player, "trust.player_not_found", Placeholder.unparsed("player", targetName));
            return Cmd.OK;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "trust.not_yourself");
            return Cmd.OK;
        }
        Optional<ProfileId> optTargetProfile = activeProfile(target);
        if (optTargetProfile.isEmpty()) {
            send(player, "trust.target_not_ready", Placeholder.unparsed("player", target.getName()));
            return Cmd.OK;
        }

        TemporaryAccessConfiguration configuration = configurationProvider.get();
        Until until;
        try {
            until = readUntil(untilRaw, configuration, target);
        } catch (IllegalArgumentException e) {
            send(player, "trust.bad_duration", Placeholder.unparsed("value", untilRaw == null ? "" : untilRaw));
            return Cmd.OK;
        }

        ProfileId granterProfile = optProfile.get();
        ProfileId granteeProfile = optTargetProfile.get();
        // Both names are read here, on the thread that owns these players. Folia owns a player per
        // region thread, so reading one from the async pool throws there and races on Paper.
        String granterName = player.getName();
        String granteeName = target.getName();
        PlayerUuid granterUuid = new PlayerUuid(player.getUniqueId());
        PlayerUuid granteeUuid = new PlayerUuid(target.getUniqueId());

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandLocationService.findIslandId(granterProfile).flatMap(islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(granterUuid, () -> send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!roleOf(island, granterProfile).hasPermission(IslandPermission.MEMBER_INVITE)) {
                schedulerPort.onEntity(granterUuid, () -> send(player, "trust.no_permission"));
                return;
            }

            try {
                service.issueGrant(
                        island.id().value().toString(),
                        ISLAND_ROOT_TYPE,
                        island.id().value().toString(),
                        granteeProfile,
                        granteeUuid,
                        ProfileType.CLASSIC,
                        granterProfile,
                        until.policy(),
                        until.anchorPlayerUuid(),
                        until.anchorSessionEpoch(),
                        until.anchorNodeId(),
                        until.anchorProcessGenerationId(),
                        configuration.trustPermissions(),
                        until.expiresAt());
            } catch (RuntimeException e) {
                schedulerPort.onEntity(granterUuid, () -> send(player, "trust.refused"));
                return;
            }

            activityLog.recordForMembers(
                    island.id(),
                    granterProfile,
                    ActivityEventType.TRUST_GRANTED,
                    "activity.trust_granted",
                    java.util.Map.of("player", granteeName, "actor", granterName));

            String describedUntil = describe(until);
            schedulerPort.onEntity(
                    granterUuid,
                    () -> send(
                            player,
                            "trust.granted",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("until", describedUntil)));
            schedulerPort.onEntity(granteeUuid, () -> {
                if (target.isOnline()) {
                    send(
                            target,
                            "trust.received",
                            Placeholder.unparsed("player", granterName),
                            Placeholder.unparsed("until", describedUntil));
                }
            });
        });
        return Cmd.OK;
    }

    private int executeUntrust(CommandContext<CommandSourceStack> ctx) {
        Player player = playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = accessServiceProvider.get();
        if (service == null) {
            send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId revokerProfile = optProfile.get();
        PlayerUuid revokerUuid = new PlayerUuid(player.getUniqueId());
        String who = StringArgumentType.getString(ctx, "who");
        // Who is here, and what they are called, is read on the thread that owns them. Folia owns a
        // player per region thread, so asking one from the async pool throws there.
        String revokerName = player.getName();
        Map<ProfileId, Player> onlineByProfile = onlineByProfile();

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandLocationService.findIslandId(revokerProfile).flatMap(islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(revokerUuid, () -> send(player, "error.no_island"));
                return;
            }
            Island island = optIsland.get();
            if (!roleOf(island, revokerProfile).hasPermission(IslandPermission.MEMBER_INVITE)) {
                schedulerPort.onEntity(revokerUuid, () -> send(player, "trust.no_permission"));
                return;
            }

            List<TemporaryAccessGrant> held = service.getActiveGrantsForRoot(
                    ISLAND_ROOT_TYPE, island.id().value().toString());
            List<TemporaryAccessGrant> matching = matching(held, who, onlineByProfile);
            if (matching.isEmpty()) {
                schedulerPort.onEntity(
                        revokerUuid, () -> send(player, "trust.nothing_to_revoke", Placeholder.unparsed("who", who)));
                return;
            }
            for (TemporaryAccessGrant grant : matching) {
                service.revokeGrant(grant.grantId(), revokerProfile);
            }
            activityLog.recordForMembers(
                    island.id(),
                    revokerProfile,
                    ActivityEventType.TRUST_REVOKED,
                    "activity.trust_revoked",
                    java.util.Map.of("player", who, "actor", revokerName));

            int revoked = matching.size();
            schedulerPort.onEntity(
                    revokerUuid,
                    () -> send(
                            player,
                            "trust.revoked",
                            Placeholder.unparsed("who", who),
                            Placeholder.unparsed("count", Integer.toString(revoked))));
            java.util.Set<ProfileId> told = new java.util.LinkedHashSet<>();
            for (TemporaryAccessGrant grant : matching) {
                if (!told.add(grant.granteeProfileId())) {
                    continue;
                }
                Player grantee = onlineByProfile.get(grant.granteeProfileId());
                if (grantee != null) {
                    schedulerPort.onEntity(
                            new PlayerUuid(grantee.getUniqueId()),
                            () -> send(grantee, "trust.revoked_notice", Placeholder.unparsed("player", revokerName)));
                    continue;
                }
                // Losing your standing on an island while you are away is exactly what the inbox is
                // for. One notice per player, not one per grant they happened to hold.
                leaveNotice(grant.granteeProfileId(), revokerName);
            }
        });
        return Cmd.OK;
    }

    private int executeTrusted(CommandContext<CommandSourceStack> ctx) {
        Player player = playerOrNull(ctx);
        if (player == null) {
            return Cmd.OK;
        }
        TemporaryAccessService service = accessServiceProvider.get();
        if (service == null) {
            send(player, "trust.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<Island> optIsland =
                    islandLocationService.findIslandId(profileId).flatMap(islandLocationService::findIsland);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            List<TemporaryAccessGrant> held = service.getActiveGrantsForRoot(
                    ISLAND_ROOT_TYPE, optIsland.get().id().value().toString());
            schedulerPort.onEntity(playerUuid, () -> {
                if (held.isEmpty()) {
                    send(player, "trust.none");
                    return;
                }
                send(player, "trust.header", Placeholder.unparsed("count", Integer.toString(held.size())));
                for (TemporaryAccessGrant grant : held) {
                    send(
                            player,
                            "trust.entry",
                            Placeholder.unparsed("who", nameOf(grant.granteeProfileId())),
                            Placeholder.unparsed("until", describe(grant)),
                            Placeholder.unparsed("id", grant.grantId().value().toString()));
                }
            });
        });
        return Cmd.OK;
    }

    /** Writes a revocation down for a player who is not here to be told. */
    private void leaveNotice(ProfileId recipient, String revokerName) {
        NotificationService service = this.notificationService;
        if (service == null) {
            return;
        }
        try {
            service.notify(
                    recipient,
                    NotificationCategory.TRUST_REVOKED,
                    "notification.trust_revoked",
                    java.util.Map.of("player", revokerName),
                    null);
        } catch (RuntimeException e) {
            // The revocation itself happened and stands.
            LOGGER.log(
                    java.util.logging.Level.WARNING, e, () -> "Leaving a trust notice for " + recipient + " failed.");
        }
    }

    /** Grants held by the named player, or the one grant whose id was typed. */
    private List<TemporaryAccessGrant> matching(
            List<TemporaryAccessGrant> held, String who, Map<ProfileId, Player> onlineByProfile) {
        List<TemporaryAccessGrant> matches = new ArrayList<>();
        for (TemporaryAccessGrant grant : held) {
            if (grant.grantId().value().toString().equalsIgnoreCase(who)) {
                return List.of(grant);
            }
            Player online = onlineByProfile.get(grant.granteeProfileId());
            if (online != null && who.equalsIgnoreCase(online.getName())) {
                matches.add(grant);
            }
        }
        return matches;
    }

    /**
     * Who is here now, by the profile they are playing.
     *
     * <p>Read once, on the thread the command arrives on, because everything that needs it happens
     * after a hop off that thread and a player belongs to whichever thread owns them.
     */
    private Map<ProfileId, Player> onlineByProfile() {
        if (sessionCoordinator == null) {
            return Map.of();
        }
        Map<ProfileId, Player> byProfile = new java.util.LinkedHashMap<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            sessionCoordinator.activeProfile(online.getUniqueId()).ifPresent(profile -> byProfile.put(profile, online));
        }
        return byProfile;
    }

    /**
     * What the operator's words mean in the domain's terms.
     *
     * <p>All four termination policies the documents name are reachable, because a feature is not
     * half a feature: a word for the session, a word for the restart, a word for until revoked, and
     * anything else read as a length of time.
     */
    private Until readUntil(@Nullable String raw, TemporaryAccessConfiguration configuration, Player target) {
        if (raw == null || raw.isBlank()) {
            return timed(configuration.defaultDuration(), configuration);
        }
        String word = raw.strip().toLowerCase(Locale.ROOT);
        return switch (word) {
            case "session", "logout" -> {
                ActiveSession session =
                        sessionCoordinator == null ? null : sessionCoordinator.getActiveSession(target.getUniqueId());
                if (session == null) {
                    throw new IllegalArgumentException("no live session to anchor to");
                }
                yield new Until(
                        TerminationPolicy.UNTIL_SESSION_END,
                        new PlayerUuid(target.getUniqueId()),
                        session.sessionEpoch(),
                        null,
                        null,
                        null);
            }
            case "restart" -> {
                CurrentNodeProcessIdentity identity = nodeIdentitySupplier.get();
                yield new Until(
                        TerminationPolicy.NODE_PROCESS_RESTART,
                        null,
                        null,
                        identity.nodeId(),
                        identity.processGenerationId(),
                        null);
            }
            case "forever", "revoked" -> new Until(TerminationPolicy.UNTIL_REVOKED, null, null, null, null, null);
            default -> timed(Durations.parse(word), configuration);
        };
    }

    /** A length of time, never longer than the operator allows. */
    private Until timed(Duration wanted, TemporaryAccessConfiguration configuration) {
        if (wanted.isNegative() || wanted.isZero()) {
            throw new IllegalArgumentException("a grant must last longer than nothing: " + wanted);
        }
        Duration capped = wanted.compareTo(configuration.maxDuration()) > 0 ? configuration.maxDuration() : wanted;
        return new Until(
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                Instant.now().plus(capped));
    }

    private String describe(Until until) {
        Instant expiresAt = until.expiresAt();
        return expiresAt != null ? WHEN.format(expiresAt) : word(until.policy());
    }

    private String describe(TemporaryAccessGrant grant) {
        Instant expiresAt = grant.expiresAt();
        return expiresAt != null ? WHEN.format(expiresAt) : word(grant.terminationPolicy());
    }

    private static String word(TerminationPolicy policy) {
        return switch (policy) {
            case UNTIL_SESSION_END -> "session";
            case NODE_PROCESS_RESTART -> "restart";
            case UNTIL_REVOKED -> "forever";
            case UNTIL_TIMESTAMP -> "";
        };
    }

    private static IslandRole roleOf(Island island, ProfileId profileId) {
        if (island.ownerProfileId().equals(profileId)) {
            return IslandRole.OWNER;
        }
        IslandMember member = island.members().get(profileId);
        return member != null ? member.role() : IslandRole.VISITOR;
    }

    private @Nullable Player onlinePlayerFor(ProfileId profileId) {
        if (sessionCoordinator == null) {
            return null;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (sessionCoordinator
                    .activeProfile(online.getUniqueId())
                    .filter(profileId::equals)
                    .isPresent()) {
                return online;
            }
        }
        return null;
    }

    /** The name behind a profile when its player is here, and the profile itself when they are not. */
    private String nameOf(ProfileId profileId) {
        Player online = onlinePlayerFor(profileId);
        return online != null ? online.getName() : profileId.value().toString();
    }

    private @Nullable Player playerOrNull(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, "error.players_only");
        return null;
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(CommandSender sender, String key, TagResolver... resolvers) {
        messages.send(sender, key, resolvers);
    }

    /** One answer to "how long", in the shape issueGrant wants it. */
    private record Until(
            TerminationPolicy policy,
            @Nullable PlayerUuid anchorPlayerUuid,
            @Nullable Long anchorSessionEpoch,
            @Nullable String anchorNodeId,
            @Nullable String anchorProcessGenerationId,
            @Nullable Instant expiresAt) {}
}
