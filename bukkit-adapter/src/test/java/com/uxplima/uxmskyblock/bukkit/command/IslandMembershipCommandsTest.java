package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.LanguageConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is invite}, {@code /is accept}, {@code /is deny}, {@code /is kick}, {@code /is leave},
 * {@code /is members} and {@code /is role}, end to end through Brigadier.
 *
 * <p>The profile the service is given is the part worth pinning. A command that hands the service
 * the caller's own profile where the target's belongs is a player kicking themselves, and nothing
 * about it would look wrong in the source.
 */
class IslandMembershipCommandsTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());
    private static final ProfileId MATE = new ProfileId(UUID.randomUUID());

    private ServerMock server;
    private PlayerMock owner;
    private PlayerMock mate;
    private IslandMembershipService membership;
    private NotificationService notifications;
    private IslandMembershipCommands commands;
    private com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService activity;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        owner = server.addPlayer("Owner");
        mate = server.addPlayer("Mate");

        membership = mock(IslandMembershipService.class);
        when(membership.invite(any(), any()))
                .thenReturn(new IslandMembershipService.InviteOutcome.Sent(
                        MATE, Instant.now().plusSeconds(300)));
        when(membership.accept(any(), any())).thenReturn(new IslandMembershipService.JoinOutcome.Joined(ISLAND));
        when(membership.decline(any())).thenReturn(true);
        when(membership.kick(any(), any()))
                .thenReturn(new IslandMembershipService.RemovalOutcome.Removed(ISLAND, MATE));
        when(membership.leave(any())).thenReturn(new IslandMembershipService.RemovalOutcome.Removed(ISLAND, MATE));
        when(membership.setRole(any(), any(), anyString()))
                .thenReturn(new IslandMembershipService.RoleOutcome.Changed(MATE, "moderator"));
        when(membership.members(ISLAND)).thenReturn(List.of());
        when(membership.setRolePermission(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new IslandMembershipService.PermissionOutcome.Changed("member", "block_place", true));

        IslandLocationService locations = mock(IslandLocationService.class);
        when(locations.findIslandId(OWNER)).thenReturn(Optional.of(ISLAND));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(owner.getUniqueId())).thenReturn(Optional.of(OWNER));
        when(sessions.activeProfile(mate.getUniqueId())).thenReturn(Optional.of(MATE));
        // Mate's profile as it is found while they are away.
        when(sessions.findDurableActiveProfile(mate.getUniqueId())).thenReturn(Optional.of(MATE));

        commands = new IslandMembershipCommands(
                () -> membership,
                locations,
                inlineScheduler(),
                Messages.of(new MessageProvider("en"), LanguageConfiguration.defaults()),
                sessions);
        notifications = mock(NotificationService.class);
        commands.useNotifications(notifications);
        activity = mock(com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService.class);
        commands.useActivityFeed(activity);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildInvite());
        dispatcher.register(commands.buildAccept());
        dispatcher.register(commands.buildDeny());
        dispatcher.register(commands.buildKick());
        dispatcher.register(commands.buildLeave());
        dispatcher.register(commands.buildMembers());
        dispatcher.register(commands.buildRole());
        dispatcher.register(commands.buildPermissions());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A role command that names a player and no role lists the roles that player could be given")
    void aRoleWithoutARoleListsTheChoice() throws Exception {
        when(membership.assignableRoles(OWNER)).thenReturn(Optional.of("co_owner, member, moderator"));

        run("role Mate", owner);

        assertThat(heardBy(owner)).contains("member.role_choose");
        verify(membership, never()).setRole(any(), any(), anyString());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A move out of the caller's reach is refused in words, not reported as done")
    void anOutOfReachMoveIsRefused() throws Exception {
        when(membership.setRole(any(), any(), anyString()))
                .thenReturn(new IslandMembershipService.RoleOutcome.OutOfReach());
        when(membership.kick(any(), any())).thenReturn(new IslandMembershipService.RemovalOutcome.OutOfReach());

        run("role Mate co_owner", owner);
        run("kick Mate", owner);

        assertThat(heardBy(owner)).contains("member.role_out_of_reach").contains("member.kick_out_of_reach");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A member on the server is told at once that they were invited, promoted or removed")
    void aMemberOnTheServerIsToldAtOnce() throws Exception {
        run("invite Mate", owner);
        assertThat(heardBy(mate)).contains("member.invite_received");

        run("role Mate moderator", owner);
        assertThat(heardBy(mate)).contains("member.role_changed_you");

        run("kick Mate", owner);
        assertThat(heardBy(mate)).contains("member.kicked_you");

        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.never())
                .notify(any(), any(), anyString(), any(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("The island's members on the server hear that someone joined, and the one who joined does not")
    void theMembersHearAJoin() throws Exception {
        when(membership.members(ISLAND))
                .thenReturn(List.of(
                        new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                new PlayerUuid(owner.getUniqueId()),
                                OWNER,
                                com.uxplima.uxmskyblock.core.domain.island.IslandRole.OWNER,
                                Instant.now()),
                        new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                                new PlayerUuid(mate.getUniqueId()),
                                MATE,
                                com.uxplima.uxmskyblock.core.domain.island.IslandRole.MEMBER,
                                Instant.now())));

        run("accept", mate);

        assertThat(heardBy(owner)).contains("member.joined_your_island");
        assertThat(heardBy(mate)).contains("member.joined").doesNotContain("member.joined_your_island");
    }

    /** Everything {@code player} was told since last asked, one line each. */
    private static java.util.List<String> heardBy(org.mockbukkit.mockbukkit.entity.PlayerMock player) {
        java.util.List<String> heard = new java.util.ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            heard.add(line);
        }
        return heard;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A member who is kicked is told on their next join, not left to find out")
    void akickedMemberIsToldLater() throws Exception {
        // Away: a member on the server is told at once, which the tests below hold.
        mate.disconnect();
        run("kick Mate", owner);

        org.mockito.Mockito.verify(notifications)
                .notify(
                        org.mockito.ArgumentMatchers.eq(MATE),
                        org.mockito.ArgumentMatchers.eq(NotificationCategory.KICK),
                        org.mockito.ArgumentMatchers.eq("notification.kicked"),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.of("player", "Owner")),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A kick that was refused leaves no notice behind")
    void arefusedKickLeavesNothing() throws Exception {
        when(membership.kick(any(), any())).thenReturn(new IslandMembershipService.RemovalOutcome.NotAllowed());

        run("kick Mate", owner);

        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.never())
                .notify(any(), any(), org.mockito.ArgumentMatchers.anyString(), any(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A member whose role changed is told which role they now hold")
    void arolechangeIsToldToo() throws Exception {
        mate.disconnect();
        run("role Mate moderator", owner);

        org.mockito.Mockito.verify(notifications)
                .notify(
                        org.mockito.ArgumentMatchers.eq(MATE),
                        org.mockito.ArgumentMatchers.eq(NotificationCategory.ROLE_CHANGED),
                        org.mockito.ArgumentMatchers.eq("notification.role_changed"),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.of("player", "Owner", "role", "moderator")),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A kick is written into the island's feed, for the members who were not there")
    void akickIsWrittenDown() throws Exception {
        run("kick Mate", owner);

        org.mockito.Mockito.verify(activity)
                .record(
                        org.mockito.ArgumentMatchers.eq(ISLAND.value().toString()),
                        org.mockito.ArgumentMatchers.eq(OWNER),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType.MEMBER_LEFT),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility.MEMBERS_ONLY),
                        org.mockito.ArgumentMatchers.eq("activity.member_kicked"),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.of("player", "Mate", "actor", "Owner")));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A kick that was refused writes nothing into the feed")
    void arefusedKickWritesNothing() throws Exception {
        when(membership.kick(any(), any())).thenReturn(new IslandMembershipService.RemovalOutcome.NotAllowed());

        run("kick Mate", owner);

        org.mockito.Mockito.verify(activity, org.mockito.Mockito.never())
                .record(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyString(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Joining and leaving are written down too")
    void joiningAndLeavingAreWrittenDown() throws Exception {
        run("accept", mate);
        run("leave", mate);

        org.mockito.Mockito.verify(activity)
                .record(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType.MEMBER_JOINED),
                        any(),
                        org.mockito.ArgumentMatchers.eq("activity.member_joined"),
                        any());
        org.mockito.Mockito.verify(activity)
                .record(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType.MEMBER_LEFT),
                        any(),
                        org.mockito.ArgumentMatchers.eq("activity.member_left"),
                        any());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void run(String line, CommandSender sender) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    /** An anti abuse service that says this player left an island this recently. */
    private com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService lockHolding(
            java.time.Duration remaining) {
        var lock = mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(lock.checkCoopJoinAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult.CooldownActive(
                        Instant.now().plus(remaining), remaining));
        return lock;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A player who just left an island cannot be recruited into the next one")
    void acooldownHoldsThePlayerBack() throws Exception {
        var lock = lockHolding(java.time.Duration.ofHours(3));
        commands.useCoopHoppingLock(lock, null);

        run("accept", mate);

        verify(membership, never()).accept(any(), any());
        assertThat(mate.nextMessage())
                .describedAs("the file names how long this waits and nothing read it")
                .isNotNull();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A player the lock has nothing against joins")
    void anunheldPlayerJoins() throws Exception {
        var lock = mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        when(lock.checkCoopJoinAllowed(any(), anyBoolean()))
                .thenReturn(new com.uxplima.uxmskyblock.core.domain.antiabuse.CoopJoinCheckResult.Allowed());
        commands.useCoopHoppingLock(lock, null);

        run("accept", mate);

        verify(membership).accept(any(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Leaving an island starts the wait before the next one")
    void leavingStartsTheWait() throws Exception {
        var lock = mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        commands.useCoopHoppingLock(lock, null);

        run("leave", mate);

        verify(lock).recordCoopDeparture(any(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Being kicked starts it too, because that is the half somebody else decides")
    void beingKickedStartsItToo() throws Exception {
        var lock = mock(com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService.class);
        commands.useCoopHoppingLock(lock, null);

        run("kick " + mate.getName(), owner);

        verify(lock).recordCoopDeparture(any(), any());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("A node with no lock lets everybody through, the way it always did")
    void nolockMeansNoWait() throws Exception {
        run("accept", mate);

        verify(membership).accept(any(), any());
    }

    @Test
    @DisplayName("An invite names the caller as the inviter and the other player as the target")
    void anInviteNamesBothProfiles() throws Exception {
        run("invite Mate", owner);

        verify(membership).invite(OWNER, MATE);
    }

    @Test
    @DisplayName("A kick names the target's profile, not the caller's own")
    void aKickNamesTheTarget() throws Exception {
        run("kick Mate", owner);

        verify(membership).kick(OWNER, MATE);
    }

    @Test
    @DisplayName("Leaving names only the caller, because leaving needs nobody else")
    void leavingNamesOnlyTheCaller() throws Exception {
        run("leave", owner);

        verify(membership).leave(OWNER);
        verify(membership, never()).kick(any(), any());
    }

    @Test
    @DisplayName("Accepting carries the caller's own player uuid, which is what the membership records")
    void acceptingCarriesThePlayerUuid() throws Exception {
        run("accept", mate);

        verify(membership).accept(eq(MATE), eq(new PlayerUuid(mate.getUniqueId())));
    }

    @Test
    @DisplayName("Declining throws away the caller's own invitation")
    void decliningNamesTheCaller() throws Exception {
        run("deny", mate);

        verify(membership).decline(MATE);
    }

    @Test
    @DisplayName("A role change carries the target and the role the caller typed")
    void aRoleChangeCarriesBoth() throws Exception {
        run("role Mate moderator", owner);

        verify(membership).setRole(OWNER, MATE, "moderator");
    }

    @Test
    @DisplayName("The member list is read for the caller's own island")
    void theListIsReadForTheCallersIsland() throws Exception {
        run("members", owner);

        verify(membership).members(ISLAND);
    }

    @Test
    @DisplayName("Every member the island holds is put in front of the caller")
    void everyMemberIsShown() throws Exception {
        when(membership.members(ISLAND))
                .thenReturn(List.of(
                        new IslandMember(new PlayerUuid(owner.getUniqueId()), OWNER, IslandRole.OWNER, Instant.now()),
                        new IslandMember(new PlayerUuid(mate.getUniqueId()), MATE, IslandRole.MEMBER, Instant.now())));

        run("members", owner);

        // A header and two rows.
        for (int line = 0; line < 3; line++) {
            assertThat(owner.nextMessage())
                    .describedAs("line %d of the list", line)
                    .isNotNull();
        }
        assertThat(owner.nextMessage()).isNull();
    }

    @Test
    @DisplayName("A name nobody on this server answers to never reaches the service")
    void anUnknownNameNeverReachesTheService() throws Exception {
        run("invite Nobody", owner);
        run("kick Nobody", owner);
        run("role Nobody member", owner);

        verify(membership, never()).invite(any(), any());
        verify(membership, never()).kick(any(), any());
        verify(membership, never()).setRole(any(), any(), anyString());
    }

    @Test
    @DisplayName("Every verb that names a player is refused without one, and a role without a role moves nobody")
    void everyTargetedVerbNeedsAName() throws Exception {
        for (String line : new String[] {"invite", "kick", "role"}) {
            assertThatThrownBy(() -> run(line, owner))
                    .describedAs("%s must be refused", line)
                    .isInstanceOf(Exception.class);
        }
        // Named but given no role, it answers with the roles on offer rather than an unknown command.
        run("role Mate", owner);
        verify(membership, never()).invite(any(), any());
        verify(membership, never()).kick(any(), any());
        verify(membership, never()).setRole(any(), any(), anyString());
    }

    @Test
    @DisplayName("The console is told to be a player rather than joining nobody's island")
    void theConsoleIsRefused() throws Exception {
        run("accept", server.getConsoleSender());
        run("leave", server.getConsoleSender());

        verify(membership, never()).accept(any(), any());
        verify(membership, never()).leave(any());
    }

    @Test
    @DisplayName("A refusal from the service is an answer the caller reads")
    void aRefusalIsAnAnswer() throws Exception {
        when(membership.invite(any(), any())).thenReturn(new IslandMembershipService.InviteOutcome.NotAllowed());

        run("invite Mate", owner);

        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Turning a permission on carries the role, the permission and the direction")
    void turningAPermissionOnCarriesAllThree() throws Exception {
        run("permissions member block_place on", owner);

        verify(membership).setRolePermission(OWNER, "member", "block_place", true);
    }

    @Test
    @DisplayName("Turning it off is the same command the other way")
    void turningItOffIsTheSameCommand() throws Exception {
        run("permissions member block_place off", owner);

        verify(membership).setRolePermission(OWNER, "member", "block_place", false);
    }

    @Test
    @DisplayName("A word that is neither on nor off never reaches the service")
    void aWordThatIsNeitherNeverReachesTheService() throws Exception {
        run("permissions member block_place maybe", owner);

        verify(membership, never()).setRolePermission(any(), anyString(), anyString(), anyBoolean());
        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("A bare /is permissions changes nothing, it only reads")
    void aBarePermissionsCommandChangesNothing() throws Exception {
        run("permissions", owner);

        verify(membership, never()).setRolePermission(any(), anyString(), anyString(), anyBoolean());
    }
}
