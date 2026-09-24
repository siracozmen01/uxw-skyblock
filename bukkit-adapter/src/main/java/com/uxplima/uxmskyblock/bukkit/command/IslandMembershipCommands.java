package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is invite}, {@code /is accept}, {@code /is deny}, {@code /is kick}, {@code /is leave},
 * {@code /is members} and {@code /is role}: who belongs to the island.
 *
 * <p>This is a skyblock and a team is the point of one, and there was no way to make one. The
 * domain has carried addMember and removeMember since it was written, the roles carry
 * MEMBER_INVITE, MEMBER_KICK, MEMBER_PROMOTE and MEMBER_DEMOTE, the MEMBERS upgrade raises a cap,
 * the members menu has an invite button, and none of it reached a command: every island on every
 * server was a solo island.
 */
public final class IslandMembershipCommands {

    /**
     * What the operator wrote for the milestones this command group reaches.
     *
     * <p>The reply a command gives is the catalogue's. What a server wants beside it, a sound, a
     * title, a bar, is the operator's list and nothing here decides it. A node with no list fires
     * nothing, which is a server that wrote none.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer effectPlayer;

    /** Tells this command group what the operator wrote for its milestones. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    /** Fires one milestone for one player, when the operator wrote anything for it. */
    private void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandMembershipCommands.class.getName());

    private final Supplier<@Nullable IslandMembershipService> membershipServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    /**
     * The lock that stops a player leaving one island and being recruited into the next.
     *
     * <p>The service has had the check and the record since the anti abuse work and nothing called
     * either, so {@code coop-join-cooldown} and the permission that bypasses it were two settings
     * an operator could write and watch do nothing.
     */
    private volatile @Nullable IslandAntiAbuseService coopHoppingLock;

    private volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseRules;

    /**
     * The inbox a player reads on their next join.
     *
     * <p>Losing your place on an island is the kind of thing you find out about by walking into a
     * wall a week later. It is told to you now, in your own language, whether or not you were here
     * when it happened.
     */
    private volatile @Nullable NotificationService notificationService;

    /** Where what happens to this island's membership is written down for its members to read. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    public IslandMembershipCommands(
            Supplier<@Nullable IslandMembershipService> membershipServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.membershipServiceProvider =
                Objects.requireNonNull(membershipServiceProvider, "membershipServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /** Tells this command group where to leave a notice for a player who is not here. */
    public void useNotifications(@Nullable NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Tells this command group which lock holds a player who has just left an island. */
    public void useCoopHoppingLock(
            @Nullable IslandAntiAbuseService lock,
            com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration rules) {
        this.coopHoppingLock = lock;
        this.antiAbuseRules = rules;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    /**
     * Tells {@code recipient} now when they are on this server, and leaves a notice for their next
     * login when they are not.
     *
     * <p>Every change to a member was left as a notice only, and a notice is read out when a session
     * is made, so a player standing on the island when they were invited, promoted or kicked heard
     * nothing until they logged in again. An invite has no notice at all, because it runs out long
     * before a later login.
     */
    private void tellOrLeave(
            ProfileId recipient,
            String liveKey,
            NotificationCategory category,
            @Nullable String noticeKey,
            Map<String, String> values) {
        Optional<Player> online = onlinePlayerOf(recipient);
        if (online.isPresent()) {
            Player reader = online.get();
            schedulerPort.onEntity(new PlayerUuid(reader.getUniqueId()), () -> {
                if (!reader.isOnline()) {
                    return;
                }
                java.util.List<TagResolver> resolvers = new java.util.ArrayList<>(values.size());
                for (Map.Entry<String, String> value : values.entrySet()) {
                    resolvers.add(Placeholder.unparsed(value.getKey(), messages.words(reader, value.getValue())));
                }
                messages.send(reader, liveKey, resolvers.toArray(new TagResolver[0]));
            });
            return;
        }
        if (noticeKey != null) {
            leaveNotice(recipient, category, noticeKey, values);
        }
    }

    /** The player on this server whose active profile is {@code profile}, if there is one. */
    private Optional<Player> onlinePlayerOf(ProfileId profile) {
        PlayerSessionCoordinator sessions = this.sessionCoordinator;
        if (sessions == null) {
            return Optional.empty();
        }
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (sessions.activeProfile(online.getUniqueId())
                    .filter(profile::equals)
                    .isPresent()) {
                return Optional.of(online);
            }
        }
        return Optional.empty();
    }

    /**
     * Leaves a notice for a member who may not be here to read it.
     *
     * <p>What is stored is the name of a message and the values it has holes for, never a sentence.
     */
    private void leaveNotice(
            ProfileId recipient, NotificationCategory category, String messageKey, Map<String, String> values) {
        NotificationService service = this.notificationService;
        if (service == null) {
            return;
        }
        try {
            service.notify(recipient, category, messageKey, values, null);
        } catch (RuntimeException e) {
            // The island change itself already happened and stands. A notice that could not be
            // written is worth a line in the log and nothing more.
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    e,
                    () -> "Leaving a " + category + " notice for " + recipient + " failed.");
        }
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildInvite() {
        return Cmd.literal("invite")
                .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeInvite));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildAccept() {
        return Cmd.literal("accept").executes(this::executeAccept);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDeny() {
        return Cmd.literal("deny").executes(this::executeDeny);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildKick() {
        return Cmd.literal("kick")
                .then(Cmd.argument("player", StringArgumentType.word()).executes(this::executeKick));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLeave() {
        return Cmd.literal("leave").executes(this::executeLeave);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildMembers() {
        return Cmd.literal("members").executes(this::executeMembers);
    }

    /** {@code /is role <player> <role>}: what somebody on the island may do. */
    public LiteralArgumentBuilder<CommandSourceStack> buildRole() {
        return Cmd.literal("role")
                .then(Cmd.argument("player", StringArgumentType.word())
                        .then(Cmd.argument("role", StringArgumentType.word()).executes(this::executeRole)));
    }

    /**
     * {@code /is permissions} and {@code /is permissions <role> <permission> <on|off>}.
     *
     * <p>The design document publishes this for overriding a permission per role, the table that
     * holds it has been written on every save since the island writer was written, and nothing could
     * move one: the roles an island was created with were the roles it died with.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildPermissions() {
        return Cmd.literal("permissions")
                .executes(this::executePermissionList)
                .then(Cmd.argument("role", StringArgumentType.word())
                        .then(Cmd.argument("permission", StringArgumentType.word())
                                .then(Cmd.argument("state", StringArgumentType.word())
                                        .executes(this::executeSetPermission))));
    }

    private int executePermissionList(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<IslandId> optIsland = islandLocationService.findIslandId(actor);
                    Optional<com.uxplima.uxmskyblock.core.domain.island.Island> optIslandRow =
                            optIsland.flatMap(islandLocationService::findIsland);
                    if (optIslandRow.isEmpty()) {
                        send(player, "error.no_island");
                        return;
                    }
                    com.uxplima.uxmskyblock.core.domain.island.Island island = optIslandRow.get();
                    send(player, "member.permissions_header");
                    island.roles().values().stream()
                            .sorted(java.util.Comparator.comparingInt(
                                    com.uxplima.uxmskyblock.core.domain.island.IslandRole::weight))
                            .forEach(role -> send(
                                    player,
                                    "member.permissions_entry",
                                    Placeholder.unparsed(
                                            "role", messages.named(player, "roles", role.id(), role.displayName())),
                                    Placeholder.unparsed("permissions", permissionsOf(role))));
                }));
    }

    /** The permissions a role carries, lower case and comma separated, or a word saying none. */
    private String permissionsOf(com.uxplima.uxmskyblock.core.domain.island.IslandRole role) {
        return role.permissions().stream()
                .map(permission -> permission.name().toLowerCase(java.util.Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private int executeSetPermission(CommandContext<CommandSourceStack> ctx) {
        String role = StringArgumentType.getString(ctx, "role");
        String permission = StringArgumentType.getString(ctx, "permission");
        String state = StringArgumentType.getString(ctx, "state");
        boolean allowed =
                "on".equalsIgnoreCase(state) || "true".equalsIgnoreCase(state) || "yes".equalsIgnoreCase(state);
        boolean denied =
                "off".equalsIgnoreCase(state) || "false".equalsIgnoreCase(state) || "no".equalsIgnoreCase(state);
        if (!allowed && !denied) {
            send(ctx.getSource().getSender(), "member.permission_state", Placeholder.unparsed("state", state));
            return Cmd.OK;
        }

        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    switch (service.setRolePermission(actor, role, permission, allowed)) {
                        case IslandMembershipService.PermissionOutcome.Changed changed ->
                            send(
                                    player,
                                    changed.allowed() ? "member.permission_granted" : "member.permission_revoked",
                                    Placeholder.unparsed(
                                            "role",
                                            messages.named(player, "roles", changed.roleId(), changed.roleId())),
                                    Placeholder.unparsed("permission", changed.permission()));
                        case IslandMembershipService.PermissionOutcome.NotAllowed ignored ->
                            send(player, "member.role_no_permission");
                        case IslandMembershipService.PermissionOutcome.NoIsland ignored ->
                            send(player, "error.no_island");
                        case IslandMembershipService.PermissionOutcome.UnknownRole unknown ->
                            send(
                                    player,
                                    "member.unknown_role",
                                    Placeholder.unparsed("role", unknown.roleId()),
                                    Placeholder.unparsed("roles", unknown.available()));
                        case IslandMembershipService.PermissionOutcome.UnknownPermission unknown ->
                            send(
                                    player,
                                    "member.unknown_permission",
                                    Placeholder.unparsed("permission", unknown.permission()),
                                    Placeholder.unparsed("permissions", unknown.available()));
                        case IslandMembershipService.PermissionOutcome.CannotChangeOwnerRole ignored ->
                            send(player, "member.owner_role_stays");
                    }
                }));
    }

    private int executeInvite(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    switch (service.invite(actor, optTarget.get())) {
                        case IslandMembershipService.InviteOutcome.Sent sent -> {
                            send(player, "member.invited", Placeholder.unparsed("player", target));
                            tellOrLeave(
                                    optTarget.get(),
                                    "member.invite_received",
                                    NotificationCategory.INVITE,
                                    null,
                                    Map.of("player", player.getName()));
                        }
                        case IslandMembershipService.InviteOutcome.NotAllowed ignored ->
                            send(player, "member.invite_no_permission");
                        case IslandMembershipService.InviteOutcome.NoIsland ignored -> send(player, "error.no_island");
                        case IslandMembershipService.InviteOutcome.AlreadyAMember ignored ->
                            send(player, "member.already_a_member", Placeholder.unparsed("player", target));
                        case IslandMembershipService.InviteOutcome.AlreadyOnAnotherIsland ignored ->
                            send(player, "member.already_elsewhere", Placeholder.unparsed("player", target));
                        case IslandMembershipService.InviteOutcome.IslandFull full ->
                            send(
                                    player,
                                    "member.island_full",
                                    Placeholder.unparsed("max", Integer.toString(full.allowed())));
                    }
                }));
    }

    private int executeAccept(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    PlayerUuid joining = new PlayerUuid(player.getUniqueId());
                    if (heldByTheCoopLock(player, joining)) {
                        return;
                    }
                    switch (service.accept(actor, joining)) {
                        case IslandMembershipService.JoinOutcome.Joined joined -> {
                            activityLog.recordForMembers(
                                    joined.islandId(),
                                    actor,
                                    ActivityEventType.MEMBER_JOINED,
                                    "activity.member_joined",
                                    Map.of("player", player.getName()));
                            send(player, "member.joined");
                            fireMilestone("member-joined", player);
                        }
                        case IslandMembershipService.JoinOutcome.NoInvite ignored -> send(player, "member.no_invite");
                        case IslandMembershipService.JoinOutcome.AlreadyOnAnIsland ignored ->
                            send(player, "member.you_have_an_island");
                        case IslandMembershipService.JoinOutcome.IslandMissing ignored ->
                            send(player, "error.no_island");
                        case IslandMembershipService.JoinOutcome.IslandFull full ->
                            send(
                                    player,
                                    "member.island_full",
                                    Placeholder.unparsed("max", Integer.toString(full.allowed())));
                    }
                }));
    }

    private int executeDeny(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    send(player, service.decline(actor) ? "member.declined" : "member.no_invite");
                }));
    }

    private int executeKick(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    Optional<PlayerUuid> optTargetUuid = resolveUuid(target);
                    IslandMembershipService.RemovalOutcome outcome = service.kick(actor, optTarget.get());
                    if (outcome instanceof IslandMembershipService.RemovalOutcome.Removed removed) {
                        optTargetUuid.ifPresent(this::lockOutOfTheNextIsland);
                        activityLog.recordForMembers(
                                removed.islandId(),
                                actor,
                                ActivityEventType.MEMBER_LEFT,
                                "activity.member_kicked",
                                Map.of("player", target, "actor", player.getName()));
                        tellOrLeave(
                                optTarget.get(),
                                "member.kicked_you",
                                NotificationCategory.KICK,
                                "notification.kicked",
                                Map.of("player", player.getName()));
                    }
                    reportRemoval(player, outcome, target);
                }));
    }

    private int executeLeave(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    IslandMembershipService.RemovalOutcome outcome = service.leave(actor);
                    if (outcome instanceof IslandMembershipService.RemovalOutcome.Removed removed) {
                        lockOutOfTheNextIsland(new PlayerUuid(player.getUniqueId()));
                        activityLog.recordForMembers(
                                removed.islandId(),
                                actor,
                                ActivityEventType.MEMBER_LEFT,
                                "activity.member_left",
                                Map.of("player", player.getName()));
                    }
                    reportRemoval(player, outcome, player.getName());
                }));
    }

    private void reportRemoval(Player player, IslandMembershipService.RemovalOutcome outcome, String target) {
        switch (outcome) {
            case IslandMembershipService.RemovalOutcome.Removed removed ->
                send(player, "member.removed", Placeholder.unparsed("player", target));
            case IslandMembershipService.RemovalOutcome.NotAllowed ignored -> send(player, "member.kick_no_permission");
            case IslandMembershipService.RemovalOutcome.NoIsland ignored -> send(player, "error.no_island");
            case IslandMembershipService.RemovalOutcome.NotAMember ignored ->
                send(player, "member.not_a_member", Placeholder.unparsed("player", target));
            case IslandMembershipService.RemovalOutcome.CannotRemoveOwner ignored -> send(player, "member.owner_stays");
        }
    }

    private int executeMembers(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<IslandId> optIsland = islandLocationService.findIslandId(actor);
                    if (optIsland.isEmpty()) {
                        send(player, "error.no_island");
                        return;
                    }
                    List<IslandMember> members = service.members(optIsland.get());
                    send(player, "member.header", Placeholder.unparsed("count", Integer.toString(members.size())));
                    for (IslandMember member : members) {
                        send(
                                player,
                                "member.entry",
                                Placeholder.unparsed("player", nameOf(member.playerUuid())),
                                Placeholder.unparsed(
                                        "role",
                                        messages.named(
                                                player,
                                                "roles",
                                                member.role().id(),
                                                member.role().displayName())));
                    }
                }));
    }

    private int executeRole(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "player");
        String role = StringArgumentType.getString(ctx, "role");
        return withService(
                ctx,
                (player, service, actor) -> schedulerPort.async(() -> {
                    Optional<ProfileId> optTarget = resolveProfile(target);
                    if (optTarget.isEmpty()) {
                        send(player, "member.unknown_player", Placeholder.unparsed("player", target));
                        return;
                    }
                    switch (service.setRole(actor, optTarget.get(), role)) {
                        case IslandMembershipService.RoleOutcome.Changed changed -> {
                            islandLocationService
                                    .findIslandId(actor)
                                    .ifPresent(islandId -> activityLog.recordForMembers(
                                            islandId,
                                            actor,
                                            ActivityEventType.ROLE_CHANGED,
                                            "activity.role_changed",
                                            Map.of(
                                                    "player",
                                                    target,
                                                    "role",
                                                    messages.stored("roles", changed.roleId(), changed.roleId()))));
                            tellOrLeave(
                                    optTarget.get(),
                                    "member.role_changed_you",
                                    NotificationCategory.ROLE_CHANGED,
                                    "notification.role_changed",
                                    Map.of(
                                            "player",
                                            player.getName(),
                                            "role",
                                            messages.stored("roles", changed.roleId(), changed.roleId())));
                            send(
                                    player,
                                    "member.role_changed",
                                    Placeholder.unparsed("player", target),
                                    Placeholder.unparsed(
                                            "role",
                                            messages.named(player, "roles", changed.roleId(), changed.roleId())));
                        }
                        case IslandMembershipService.RoleOutcome.NotAllowed ignored ->
                            send(player, "member.role_no_permission");
                        case IslandMembershipService.RoleOutcome.NoIsland ignored -> send(player, "error.no_island");
                        case IslandMembershipService.RoleOutcome.NotAMember ignored ->
                            send(player, "member.not_a_member", Placeholder.unparsed("player", target));
                        case IslandMembershipService.RoleOutcome.UnknownRole unknown ->
                            send(
                                    player,
                                    "member.unknown_role",
                                    Placeholder.unparsed("role", unknown.roleId()),
                                    Placeholder.unparsed("roles", unknown.available()));
                        case IslandMembershipService.RoleOutcome.CannotChangeOwner ignored ->
                            send(player, "member.owner_stays");
                    }
                }));
    }

    /** What a membership command needs: a player, the service, and their own profile. */
    @FunctionalInterface
    private interface MembershipAction {
        void run(Player player, IslandMembershipService service, ProfileId actor);
    }

    private int withService(CommandContext<CommandSourceStack> ctx, MembershipAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandMembershipService service = membershipServiceProvider.get();
        if (service == null) {
            send(player, "member.disabled");
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
     * Whether this player left an island too recently to be recruited into another.
     *
     * <p>Leaving one island and being taken straight into the next is how a bank and a vault get
     * emptied by somebody who was never going to stay. The file names how long that waits and the
     * permission that lets staff past it, and both were read nowhere.
     *
     * <p>The refusal is sent here, so a caller that gets true has nothing left to say.
     */
    private boolean heldByTheCoopLock(Player player, PlayerUuid playerUuid) {
        IslandAntiAbuseService lock = this.coopHoppingLock;
        if (lock == null) {
            return false;
        }
        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules = this.antiAbuseRules;
        String node = rules == null
                ? com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.DEFAULT_COOP_BYPASS_PERMISSION
                : rules.coopBypassPermission();
        boolean bypass = player.hasPermission(node) || player.isOp();

        if (lock.checkCoopJoinAllowed(playerUuid, bypass)
                instanceof com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult.CooldownActive held) {
            send(player, "member.coop_cooldown", Placeholder.unparsed("remaining", formatDuration(held.remaining())));
            return true;
        }
        return false;
    }

    /** Starts the wait that stops this player being recruited into another island straight away. */
    private void lockOutOfTheNextIsland(PlayerUuid playerUuid) {
        IslandAntiAbuseService lock = this.coopHoppingLock;
        if (lock != null) {
            var unused = lock.recordCoopDeparture(playerUuid, java.time.Instant.now());
        }
    }

    /** How long is left, the way every other wait in this plugin reads. */
    private static String formatDuration(java.time.Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long seconds = duration.toSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%dh %dm %ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format(java.util.Locale.ROOT, "%dm %ds", minutes, secs);
        }
        return String.format(java.util.Locale.ROOT, "%ds", secs);
    }

    /** The player behind a name, for the rules that are about a player rather than a profile. */
    private Optional<PlayerUuid> resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new PlayerUuid(online.getUniqueId()));
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return Optional.of(new PlayerUuid(offline.getUniqueId()));
        }
        return Optional.empty();
    }

    /**
     * The profile of the player the caller named, online or not.
     *
     * <p>An invite that only works while the target is standing there is barely an invite, and a
     * kick that only works while they are online is a member nobody can remove.
     */
    private Optional<ProfileId> resolveProfile(String name) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return sessionCoordinator.activeProfile(online.getUniqueId());
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return sessionCoordinator.findDurableActiveProfile(offline.getUniqueId());
        }
        return Optional.empty();
    }

    /** The name a player reads, which is the offline profile's when the player is away. */
    private static String nameOf(PlayerUuid playerUuid) {
        String name = Bukkit.getOfflinePlayer(playerUuid.value()).getName();
        return name == null ? playerUuid.value().toString() : name;
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
