package com.uxplima.uxmskyblock.core.application.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who belongs to an island.
 *
 * <p>This is a skyblock and a team is the point of one. The domain carried addMember and
 * removeMember, the roles carried MEMBER_INVITE and MEMBER_KICK, and nothing called any of it:
 * every island on every server was a solo island.
 */
class IslandMembershipServiceTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final PlayerUuid OWNER_UUID = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());
    private static final ProfileId MATE = new ProfileId(UUID.randomUUID());
    private static final PlayerUuid MATE_UUID = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId STRANGER = new ProfileId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    private IslandStoragePort storage;
    private Island island;
    private int allowance;
    private IslandMembershipService service;

    private static IslandLocation location() {
        return new IslandLocation(
                ISLAND, "skyblock_world", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100.0, 0.5, 0.0f, 0.0f);
    }

    private Island savedIsland() {
        ArgumentCaptor<Island> captor = ArgumentCaptor.forClass(Island.class);
        verify(storage).saveIsland(captor.capture(), any());
        return captor.getValue();
    }

    @BeforeEach
    void setUp() {
        island = Island.create(ISLAND, IslandBounds.fromCenterAndRadius(0, 0, 100), OWNER_UUID, OWNER, NOW);
        allowance = 4;

        storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(OWNER)).thenReturn(Optional.of(ISLAND));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.empty());
        when(storage.findIslandIdByProfileId(STRANGER)).thenReturn(Optional.empty());
        when(storage.findIslandById(ISLAND)).thenAnswer(invocation -> Optional.of(island));
        when(storage.findLocationByIslandId(ISLAND)).thenReturn(Optional.of(location()));

        service = new IslandMembershipService(
                storage,
                new com.uxplima.uxmskyblock.core.application.island.IslandMutationLock(),
                id -> allowance,
                Duration.ofMinutes(5),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("An owner may invite, and the invite stands for the timeout")
    void anOwnerMayInvite() {
        IslandMembershipService.InviteOutcome outcome = service.invite(OWNER, MATE);

        assertThat(outcome).isInstanceOf(IslandMembershipService.InviteOutcome.Sent.class);
        assertThat(service.pendingInvite(MATE)).isPresent();
        assertThat(service.pendingInvite(MATE).orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("An invite nobody answered in time is gone, rather than good for ever")
    void anExpiredInviteIsGone() {
        IslandMembershipService shortLived = new IslandMembershipService(
                storage,
                new com.uxplima.uxmskyblock.core.application.island.IslandMutationLock(),
                id -> allowance,
                Duration.ofMinutes(5),
                Clock.fixed(NOW, ZoneOffset.UTC));
        shortLived.invite(OWNER, MATE);

        IslandMembershipService later = new IslandMembershipService(
                storage,
                new com.uxplima.uxmskyblock.core.application.island.IslandMutationLock(),
                id -> allowance,
                Duration.ofMinutes(5),
                Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC));

        assertThat(shortLived.pendingInvite(MATE))
                .describedAs("the same service, later")
                .isPresent();
        assertThat(later.pendingInvite(MATE))
                .describedAs("a service whose clock has moved on")
                .isEmpty();
    }

    @Test
    @DisplayName("A member whose role does not carry MEMBER_INVITE is refused")
    void aMemberWithoutThePermissionIsRefused() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.VISITOR, NOW));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));

        assertThat(service.invite(MATE, STRANGER)).isInstanceOf(IslandMembershipService.InviteOutcome.NotAllowed.class);
    }

    @Test
    @DisplayName("Somebody who already belongs to another island is not invited away from it")
    void somebodyOnAnotherIslandIsNotInvited() {
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(IslandId.of(UUID.randomUUID())));

        assertThat(service.invite(OWNER, MATE))
                .isInstanceOf(IslandMembershipService.InviteOutcome.AlreadyOnAnotherIsland.class);
    }

    @Test
    @DisplayName("An island as full as its upgrade allows takes nobody else")
    void aFullIslandTakesNobody() {
        allowance = 1;

        assertThat(service.invite(OWNER, MATE)).isInstanceOf(IslandMembershipService.InviteOutcome.IslandFull.class);
    }

    @Test
    @DisplayName("Accepting puts the player on the island as a member, not as a visitor")
    void acceptingAddsThemAsAMember() {
        service.invite(OWNER, MATE);

        IslandMembershipService.JoinOutcome outcome = service.accept(MATE, MATE_UUID);

        assertThat(outcome).isInstanceOf(IslandMembershipService.JoinOutcome.Joined.class);
        Island saved = savedIsland();
        assertThat(saved.isMember(MATE)).isTrue();
        IslandMember joinedMember =
                java.util.Objects.requireNonNull(saved.members().get(MATE));
        assertThat(joinedMember.role().id()).isEqualTo(IslandRole.MEMBER.id());
        assertThat(joinedMember.playerUuid()).isEqualTo(MATE_UUID);
    }

    @Test
    @DisplayName("Accepting without an invite joins nothing")
    void acceptingWithoutAnInviteJoinsNothing() {
        assertThat(service.accept(MATE, MATE_UUID)).isInstanceOf(IslandMembershipService.JoinOutcome.NoInvite.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("An invite is spent once, so two answers do not make two memberships")
    void anInviteIsSpentOnce() {
        service.invite(OWNER, MATE);
        service.accept(MATE, MATE_UUID);
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));

        assertThat(service.accept(MATE, MATE_UUID)).isInstanceOf(IslandMembershipService.JoinOutcome.NoInvite.class);
        verify(storage).saveIsland(any(), any());
    }

    @Test
    @DisplayName("Declining throws the invite away and joins nothing")
    void decliningThrowsTheInviteAway() {
        service.invite(OWNER, MATE);

        assertThat(service.decline(MATE)).isTrue();
        assertThat(service.pendingInvite(MATE)).isEmpty();
        assertThat(service.accept(MATE, MATE_UUID)).isInstanceOf(IslandMembershipService.JoinOutcome.NoInvite.class);
    }

    @Test
    @DisplayName("An owner may remove a member, and the member is really gone")
    void anOwnerMayRemoveAMember() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.MEMBER, NOW));

        assertThat(service.kick(OWNER, MATE)).isInstanceOf(IslandMembershipService.RemovalOutcome.Removed.class);
        assertThat(savedIsland().isMember(MATE)).isFalse();
    }

    @Test
    @DisplayName("The owner cannot be removed, by anybody, including themselves")
    void theOwnerCannotBeRemoved() {
        assertThat(service.kick(OWNER, OWNER))
                .isInstanceOf(IslandMembershipService.RemovalOutcome.CannotRemoveOwner.class);
        assertThat(service.leave(OWNER)).isInstanceOf(IslandMembershipService.RemovalOutcome.CannotRemoveOwner.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("A member may leave without anybody's permission")
    void aMemberMayLeave() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.VISITOR, NOW));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));

        assertThat(service.leave(MATE)).isInstanceOf(IslandMembershipService.RemovalOutcome.Removed.class);
        assertThat(savedIsland().isMember(MATE)).isFalse();
    }

    @Test
    @DisplayName("A role whose permissions do not carry MEMBER_KICK removes nobody")
    void aRoleWithoutKickRemovesNobody() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.VISITOR, NOW))
                .addMember(new IslandMember(PlayerUuid.of(UUID.randomUUID()), STRANGER, IslandRole.MEMBER, NOW));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));

        assertThat(service.kick(MATE, STRANGER)).isInstanceOf(IslandMembershipService.RemovalOutcome.NotAllowed.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("Promoting asks MEMBER_PROMOTE and keeps the day they joined")
    void promotingKeepsTheJoiningDay() {
        Instant joined = NOW.minusSeconds(86_400);
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.MEMBER, joined));

        assertThat(service.setRole(OWNER, MATE, "moderator"))
                .isInstanceOf(IslandMembershipService.RoleOutcome.Changed.class);
        Island saved = savedIsland();
        IslandMember promoted = java.util.Objects.requireNonNull(saved.members().get(MATE));
        assertThat(promoted.role().id()).isEqualTo(IslandRole.MODERATOR.id());
        assertThat(promoted.joinedAt())
                .describedAs("a promotion is not a new membership")
                .isEqualTo(joined);
    }

    @Test
    @DisplayName("A role the island does not have is refused by name, with the ones it does have")
    void anUnknownRoleIsRefusedByName() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.MEMBER, NOW));

        IslandMembershipService.RoleOutcome outcome = service.setRole(OWNER, MATE, "archduke");

        assertThat(outcome).isInstanceOf(IslandMembershipService.RoleOutcome.UnknownRole.class);
        IslandMembershipService.RoleOutcome.UnknownRole unknown =
                (IslandMembershipService.RoleOutcome.UnknownRole) outcome;
        assertThat(unknown.available()).contains("member").contains("moderator");
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("The owner's own role is not something a role command moves")
    void theOwnersRoleIsNotMoved() {
        assertThat(service.setRole(OWNER, OWNER, "member"))
                .isInstanceOf(IslandMembershipService.RoleOutcome.CannotChangeOwner.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("Somebody who does not belong to the island is not given a role on it")
    void aStrangerIsNotGivenARole() {
        assertThat(service.setRole(OWNER, STRANGER, "member"))
                .isInstanceOf(IslandMembershipService.RoleOutcome.NotAMember.class);
    }

    @Test
    @DisplayName("The member list puts the owner first")
    void theOwnerIsListedFirst() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.MEMBER, NOW.minusSeconds(86_400)));

        assertThat(service.members(ISLAND))
                .first()
                .satisfies(member -> assertThat(member.profileId()).isEqualTo(OWNER));
    }

    @Test
    @DisplayName("A caller with no island invites nobody and removes nobody")
    void aCallerWithNoIslandDoesNothing() {
        assertThat(service.invite(STRANGER, MATE)).isInstanceOf(IslandMembershipService.InviteOutcome.NoIsland.class);
        assertThat(service.kick(STRANGER, MATE)).isInstanceOf(IslandMembershipService.RemovalOutcome.NoIsland.class);
        assertThat(service.leave(STRANGER)).isInstanceOf(IslandMembershipService.RemovalOutcome.NoIsland.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("A permission granted to a role is written, and everybody holding it moves with it")
    void grantingAPermissionMovesTheMembersToo() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.VISITOR, NOW));

        IslandMembershipService.PermissionOutcome outcome =
                service.setRolePermission(OWNER, "visitor", "block_place", true);

        assertThat(outcome).isInstanceOf(IslandMembershipService.PermissionOutcome.Changed.class);
        Island saved = savedIsland();
        assertThat(java.util.Objects.requireNonNull(saved.roles().get("VISITOR"))
                        .permissions())
                .contains(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_PLACE);
        assertThat(java.util.Objects.requireNonNull(saved.members().get(MATE))
                        .role()
                        .permissions())
                .describedAs("a member carries their role rather than pointing at it, so they move with it")
                .contains(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_PLACE);
    }

    @Test
    @DisplayName("A permission taken off a role is really off it")
    void revokingAPermissionTakesItOff() {
        service.setRolePermission(OWNER, "member", "block_place", false);

        assertThat(java.util.Objects.requireNonNull(savedIsland().roles().get("MEMBER"))
                        .permissions())
                .doesNotContain(com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_PLACE);
    }

    @Test
    @DisplayName("The owner's role is left alone, or an owner could lock themselves out")
    void theOwnersRoleIsLeftAlone() {
        assertThat(service.setRolePermission(OWNER, "owner", "block_place", false))
                .isInstanceOf(IslandMembershipService.PermissionOutcome.CannotChangeOwnerRole.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("A permission nobody declares is refused by name, with the ones that are declared")
    void anUnknownPermissionIsRefusedByName() {
        IslandMembershipService.PermissionOutcome outcome = service.setRolePermission(OWNER, "member", "fly", true);

        assertThat(outcome).isInstanceOf(IslandMembershipService.PermissionOutcome.UnknownPermission.class);
        assertThat(((IslandMembershipService.PermissionOutcome.UnknownPermission) outcome).available())
                .contains("block_place");
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("A role whose permissions do not carry MEMBER_PROMOTE may not edit a role")
    void aRoleWithoutPromoteMayNotEditARole() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.VISITOR, NOW));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));

        assertThat(service.setRolePermission(MATE, "member", "block_place", true))
                .isInstanceOf(IslandMembershipService.PermissionOutcome.NotAllowed.class);
    }

    @Test
    @DisplayName("Accepting an invite waits for the player's own island being made, and then refuses")
    void acceptingWaitsForACreationUnderWay() throws Exception {
        com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock<ProfileId> profiles =
                new com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock<>();
        service.shareProfileLock(profiles);
        service.invite(OWNER, MATE);
        java.util.concurrent.atomic.AtomicBoolean created = new java.util.concurrent.atomic.AtomicBoolean();
        when(storage.findIslandIdByProfileId(MATE))
                .thenAnswer(ask -> created.get() ? Optional.of(IslandId.of(UUID.randomUUID())) : Optional.empty());
        java.util.concurrent.CountDownLatch creating = new java.util.concurrent.CountDownLatch(1);

        Thread creation = new Thread(() -> profiles.inside(MATE, () -> {
            creating.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            created.set(true);
        }));
        creation.start();
        creating.await();

        IslandMembershipService.JoinOutcome outcome = service.accept(MATE, MATE_UUID);
        creation.join();

        assertThat(outcome)
                .describedAs("a player whose own island was being made does not also join another")
                .isInstanceOf(IslandMembershipService.JoinOutcome.AlreadyOnAnIsland.class);
    }
}
