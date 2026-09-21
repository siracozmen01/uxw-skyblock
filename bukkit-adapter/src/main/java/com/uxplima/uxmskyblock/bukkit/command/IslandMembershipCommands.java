package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
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
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
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

    private final Supplier<@Nullable IslandMembershipService> membershipServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

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
                        case IslandMembershipService.InviteOutcome.Sent sent ->
                            send(player, "member.invited", Placeholder.unparsed("player", target));
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
                    switch (service.accept(actor, new PlayerUuid(player.getUniqueId()))) {
                        case IslandMembershipService.JoinOutcome.Joined joined -> send(player, "member.joined");
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
                    reportRemoval(player, service.kick(actor, optTarget.get()), target);
                }));
    }

    private int executeLeave(CommandContext<CommandSourceStack> ctx) {
        return withService(
                ctx,
                (player, service, actor) ->
                        schedulerPort.async(() -> reportRemoval(player, service.leave(actor), player.getName())));
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
                                Placeholder.unparsed("role", member.role().displayName()));
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
                        case IslandMembershipService.RoleOutcome.Changed changed ->
                            send(
                                    player,
                                    "member.role_changed",
                                    Placeholder.unparsed("player", target),
                                    Placeholder.unparsed("role", changed.roleId()));
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
