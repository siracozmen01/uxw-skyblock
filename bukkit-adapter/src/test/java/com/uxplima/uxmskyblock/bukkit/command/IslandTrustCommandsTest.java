package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.command.CommandSender;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * {@code /is trust}, end to end through Brigadier.
 *
 * <p>The grant subsystem was complete underneath and nothing could make a grant: issueGrant and
 * revokeGrant had no caller anywhere, so the check that runs on every block a non member touches
 * asked about grants that could not exist.
 */
class IslandTrustCommandsTest {

    private ServerMock server;
    private PlayerMock owner;
    private PlayerMock guest;
    private TemporaryAccessService accessService;
    private IslandLocationService locationService;
    private PlayerSessionCoordinator sessions;
    private com.uxplima.uxmskyblock.core.application.notification.NotificationService notifications;

    /** What ruleset each profile plays under, so the Ironman barrier has something to refuse. */
    private final java.util.Map<ProfileId, ProfileType> profileTypes = new java.util.HashMap<>();

    private CommandDispatcher<CommandSourceStack> dispatcher;

    private final List<Runnable> deferred = new ArrayList<>();

    private IslandId islandId;
    private ProfileId ownerProfile;
    private ProfileId guestProfile;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        owner = server.addPlayer("Owner");
        guest = server.addPlayer("Guest");

        islandId = IslandId.of(UUID.randomUUID());
        ownerProfile = new ProfileId(owner.getUniqueId());
        guestProfile = new ProfileId(guest.getUniqueId());

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> deferred.add(invocation.getArgument(0, Runnable.class)))
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));

        accessService = mock(TemporaryAccessService.class);
        locationService = mock(IslandLocationService.class);
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(owner.getUniqueId())).thenReturn(Optional.of(ownerProfile));
        when(sessions.activeProfile(guest.getUniqueId())).thenReturn(Optional.of(guestProfile));
        when(sessions.getActiveSession(guest.getUniqueId()))
                .thenReturn(new ActiveSession(new PlayerUuid(guest.getUniqueId()), guestProfile, 7L, 1L));
        profileTypes.clear();
        profileTypes.put(ownerProfile, ProfileType.CLASSIC);
        profileTypes.put(guestProfile, ProfileType.CLASSIC);
        islandOwnedBy(ownerProfile);

        notifications = mock(com.uxplima.uxmskyblock.core.application.notification.NotificationService.class);
        IslandTrustCommands commands = new IslandTrustCommands(
                () -> accessService,
                locationService,
                scheduler,
                TemporaryAccessConfiguration::defaultConfiguration,
                () -> new CurrentNodeProcessIdentity("node-alpha", "boot-1"),
                profileTypes::get,
                Messages.bundled(),
                sessions);

        commands.useNotifications(notifications);

        dispatcher = new CommandDispatcher<>();
        dispatcher.register(commands.buildTrust());
        dispatcher.register(commands.buildUntrust());
        dispatcher.register(commands.buildTrusted());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void islandOwnedBy(ProfileId ownerOfIsland) {
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                new PlayerUuid(owner.getUniqueId()),
                ownerOfIsland,
                Instant.now());
        when(locationService.findIslandId(any())).thenReturn(Optional.of(islandId));
        when(locationService.findIsland(islandId)).thenReturn(Optional.of(island));
    }

    private void islandWhereOwnerIsOnly(IslandRole role) {
        Island island = Island.create(
                        islandId,
                        IslandBounds.fromCenterAndRadius(0, 0, 100),
                        new PlayerUuid(UUID.randomUUID()),
                        new ProfileId(UUID.randomUUID()),
                        Instant.now())
                .addMember(new IslandMember(new PlayerUuid(owner.getUniqueId()), ownerProfile, role, Instant.now()));
        when(locationService.findIsland(islandId)).thenReturn(Optional.of(island));
    }

    private void run(CommandSender sender, String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
    }

    private void runDeferred() {
        List<Runnable> queued = List.copyOf(deferred);
        deferred.clear();
        queued.forEach(Runnable::run);
    }

    private TemporaryAccessGrant grantFor(ProfileId grantee, TerminationPolicy policy, @Nullable Instant expiresAt) {
        return new TemporaryAccessGrant(
                GrantId.random(),
                islandId.value().toString(),
                IslandTrustCommands.ISLAND_ROOT_TYPE,
                islandId.value().toString(),
                grantee,
                ownerProfile,
                policy,
                null,
                null,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(StandardPermissions.BLOCK_BREAK),
                Instant.now(),
                expiresAt,
                Instant.now());
    }

    @Test
    @DisplayName("Nothing is read on the thread the command arrives on")
    void nothingIsReadOnTheCommandThread() throws Exception {
        run(owner, "trust Guest");

        verify(locationService, never()).findIslandId(any());
        verify(accessService, never())
                .issueGrant(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any());
        assertThat(deferred).hasSize(1);
    }

    @Test
    @DisplayName("Trusting a player with no length named uses the operator's default")
    void theDefaultDurationIsTheOperators() throws Exception {
        Instant before = Instant.now();
        run(owner, "trust Guest");
        runDeferred();

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(accessService)
                .issueGrant(
                        org.mockito.ArgumentMatchers.eq(islandId.value().toString()),
                        org.mockito.ArgumentMatchers.eq(IslandTrustCommands.ISLAND_ROOT_TYPE),
                        org.mockito.ArgumentMatchers.eq(islandId.value().toString()),
                        org.mockito.ArgumentMatchers.eq(guestProfile),
                        org.mockito.ArgumentMatchers.eq(new PlayerUuid(guest.getUniqueId())),
                        org.mockito.ArgumentMatchers.eq(ProfileType.CLASSIC),
                        org.mockito.ArgumentMatchers.eq(ownerProfile),
                        org.mockito.ArgumentMatchers.eq(TerminationPolicy.UNTIL_TIMESTAMP),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq(TemporaryAccessConfiguration.DEFAULT_TRUST_PERMISSIONS),
                        expiry.capture());

        assertThat(expiry.getValue())
                .describedAs("an hour out, which is what the shipped configuration says")
                .isBetween(before.plus(Duration.ofMinutes(59)), Instant.now().plus(Duration.ofMinutes(61)));
    }

    @Test
    @DisplayName("A length longer than the operator allows is cut down to it")
    void alongerLengthIsCapped() throws Exception {
        Instant before = Instant.now();
        run(owner, "trust Guest 7d");
        runDeferred();

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(accessService)
                .issueGrant(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        expiry.capture());

        assertThat(expiry.getValue())
                .describedAs("twenty four hours, the shipped ceiling, not seven days")
                .isBefore(before.plus(Duration.ofHours(25)));
    }

    @Test
    @DisplayName("The word session anchors the grant to the guest's live session")
    void sessionAnchorsToTheLiveSession() throws Exception {
        run(owner, "trust Guest session");
        runDeferred();

        verify(accessService)
                .issueGrant(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(TerminationPolicy.UNTIL_SESSION_END),
                        org.mockito.ArgumentMatchers.eq(new PlayerUuid(guest.getUniqueId())),
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        any(),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("The word restart anchors the grant to this boot of this node")
    void restartAnchorsToThisBoot() throws Exception {
        run(owner, "trust Guest restart");
        runDeferred();

        verify(accessService)
                .issueGrant(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(TerminationPolicy.NODE_PROCESS_RESTART),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.eq("node-alpha"),
                        org.mockito.ArgumentMatchers.eq("boot-1"),
                        any(),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("The word forever makes a grant that only a revocation ends")
    void foreverIsUntilRevoked() throws Exception {
        run(owner, "trust Guest forever");
        runDeferred();

        verify(accessService)
                .issueGrant(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(TerminationPolicy.UNTIL_REVOKED),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("A length that is not a length grants nothing and says so")
    void nonsenseGrantsNothing() throws Exception {
        run(owner, "trust Guest nextTuesday");
        runDeferred();

        verify(accessService, never())
                .issueGrant(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any());
        assertThat(owner.nextMessage()).describedAs("the refusal").isNotNull();
    }

    @Test
    @DisplayName("A member who may not invite may not trust either")
    void anOrdinaryMemberMayNotTrust() throws Exception {
        islandWhereOwnerIsOnly(IslandRole.MEMBER);

        run(owner, "trust Guest");
        runDeferred();

        verify(accessService, never())
                .issueGrant(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any());
        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Trusting somebody who is not here grants nothing")
    void anOfflinePlayerIsRefused() throws Exception {
        run(owner, "trust Nobody");

        assertThat(deferred)
                .describedAs("nothing was even handed to the scheduler")
                .isEmpty();
        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Trusting yourself is refused before anything is written")
    void trustingYourselfIsRefused() throws Exception {
        run(owner, "trust Owner");

        assertThat(deferred).isEmpty();
        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Untrusting a player takes back every grant they hold on the island")
    void untrustingTakesBackEveryGrant() throws Exception {
        TemporaryAccessGrant first = grantFor(guestProfile, TerminationPolicy.UNTIL_REVOKED, null);
        TemporaryAccessGrant second = grantFor(
                guestProfile, TerminationPolicy.UNTIL_TIMESTAMP, Instant.now().plusSeconds(600));
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of(first, second));

        run(owner, "untrust Guest");
        runDeferred();

        verify(accessService).revokeGrant(first.grantId(), ownerProfile);
        verify(accessService).revokeGrant(second.grantId(), ownerProfile);
    }

    @Test
    @DisplayName("Untrusting by grant id takes back that one and no other")
    void untrustingByIdTakesBackOne() throws Exception {
        TemporaryAccessGrant first = grantFor(guestProfile, TerminationPolicy.UNTIL_REVOKED, null);
        TemporaryAccessGrant second = grantFor(guestProfile, TerminationPolicy.UNTIL_REVOKED, null);
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of(first, second));

        run(owner, "untrust " + second.grantId().value());
        runDeferred();

        verify(accessService).revokeGrant(second.grantId(), ownerProfile);
        verify(accessService, never()).revokeGrant(first.grantId(), ownerProfile);
    }

    @Test
    @DisplayName("Untrusting somebody who holds nothing revokes nothing")
    void untrustingNobodyRevokesNothing() throws Exception {
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of());

        run(owner, "untrust Guest");
        runDeferred();

        verify(accessService, never()).revokeGrant(any(), any());
        assertThat(owner.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("A player who is away when their trust is taken back is told on their next join")
    void anAbsentPlayerIsToldLater() throws Exception {
        ProfileId absent = new ProfileId(UUID.randomUUID());
        TemporaryAccessGrant held = grantFor(absent, TerminationPolicy.UNTIL_REVOKED, null);
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of(held));

        run(owner, "untrust " + held.grantId().value());
        runDeferred();

        verify(notifications)
                .notify(
                        org.mockito.ArgumentMatchers.eq(absent),
                        org.mockito.ArgumentMatchers.eq(
                                com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory.TRUST_REVOKED),
                        org.mockito.ArgumentMatchers.eq("notification.trust_revoked"),
                        org.mockito.ArgumentMatchers.eq(java.util.Map.of("player", "Owner")),
                        org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("A player who is here is told to their face and left no notice")
    void apresentPlayerIsToldNow() throws Exception {
        TemporaryAccessGrant held = grantFor(guestProfile, TerminationPolicy.UNTIL_REVOKED, null);
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of(held));

        run(owner, "untrust Guest");
        runDeferred();

        verify(notifications, never()).notify(any(), any(), anyString(), any(), any());
        assertThat(guest.nextMessage()).describedAs("told where they stand").isNotNull();
    }

    @Test
    @DisplayName("Two grants held by one player are one line, not two")
    void twoGrantsAreOneLine() throws Exception {
        when(accessService.getActiveGrantsForRoot(anyString(), anyString()))
                .thenReturn(List.of(
                        grantFor(guestProfile, TerminationPolicy.UNTIL_REVOKED, null),
                        grantFor(
                                guestProfile,
                                TerminationPolicy.UNTIL_TIMESTAMP,
                                Instant.now().plusSeconds(600))));

        run(owner, "untrust Guest");
        runDeferred();

        assertThat(guest.nextMessage()).describedAs("told once").isNotNull();
        assertThat(guest.nextMessage())
                .describedAs("and not once per grant they happened to hold")
                .isNull();
    }

    @Test
    @DisplayName("An Ironman guest is refused the grant the ruleset forbids, and told so")
    void anIronmanGuestIsRefused() throws Exception {
        profileTypes.put(guestProfile, ProfileType.IRONMAN);
        org.mockito.Mockito.doThrow(new com.uxplima.uxmskyblock.core.domain.access.RulesetAccessViolationException(
                        "Ironman ruleset boundary forbids economic temporary access permissions"))
                .when(accessService)
                .issueGrant(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any());

        run(owner, "trust Guest");
        runDeferred();

        assertThat(owner.nextMessage()).describedAs("the refusal").isNotNull();
    }

    @Test
    @DisplayName("The guest's own ruleset is what reaches the barrier, not a guess")
    void theguestsRulesetReachesTheBarrier() throws Exception {
        profileTypes.put(guestProfile, ProfileType.HARDCORE);

        run(owner, "trust Guest");
        runDeferred();

        verify(accessService)
                .issueGrant(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(ProfileType.HARDCORE),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    @DisplayName("The trusted list names one line per grant")
    void thelistNamesEveryGrant() throws Exception {
        when(accessService.getActiveGrantsForRoot(anyString(), anyString()))
                .thenReturn(List.of(
                        grantFor(
                                guestProfile,
                                TerminationPolicy.UNTIL_TIMESTAMP,
                                Instant.now().plusSeconds(600)),
                        grantFor(new ProfileId(UUID.randomUUID()), TerminationPolicy.UNTIL_REVOKED, null)));

        run(owner, "trusted");
        runDeferred();

        assertThat(owner.nextMessage()).describedAs("the header").isNotNull();
        assertThat(owner.nextMessage()).describedAs("the first grant").contains("Guest");
        assertThat(owner.nextMessage()).describedAs("the second grant").isNotNull();
        assertThat(owner.nextMessage())
                .describedAs("nothing after the last one")
                .isNull();
    }

    @Test
    @DisplayName("An island with nobody trusted says so rather than printing a header over nothing")
    void anEmptyListSaysSo() throws Exception {
        when(accessService.getActiveGrantsForRoot(anyString(), anyString())).thenReturn(List.of());

        run(owner, "trusted");
        runDeferred();

        assertThat(owner.nextMessage()).isNotNull();
        assertThat(owner.nextMessage()).isNull();
    }

    @Test
    @DisplayName("The permissions a grant carries are the operator's list, not a list written in the code")
    void theOperatorNamesThePermissions() throws Exception {
        assertThat(TemporaryAccessConfiguration.DEFAULT_TRUST_PERMISSIONS)
                .describedAs("a trusted visitor is never a member")
                .doesNotContain(
                        StandardPermissions.SETTINGS_MODIFY,
                        StandardPermissions.MEMBER_INVITE,
                        StandardPermissions.MEMBER_KICK,
                        StandardPermissions.BANK_WITHDRAW,
                        StandardPermissions.BANK_DEPOSIT);

        Set<PermissionKey> onlyBreaking = Set.of(StandardPermissions.BLOCK_BREAK);
        TemporaryAccessConfiguration defaults = TemporaryAccessConfiguration.defaultConfiguration();
        TemporaryAccessConfiguration narrowed = new TemporaryAccessConfiguration(
                defaults.enabled(),
                defaults.defaultDuration(),
                defaults.maxDuration(),
                defaults.purgeInterval(),
                defaults.enforceRulesetIsolation(),
                defaults.grantLookupTtl(),
                onlyBreaking);

        assertThat(narrowed.trustPermissions()).isEqualTo(onlyBreaking);
    }
}
