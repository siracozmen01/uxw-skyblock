package com.uxplima.uxmskyblock.core.domain.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandDomainTest {

    private final IslandId islandId = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final PlayerUuid ownerUuid = PlayerUuid.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final ProfileId ownerProfileId = ProfileId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final IslandBounds defaultBounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
    private final Instant now = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    @DisplayName("creates island with owner as initial member having OWNER role")
    void createsIslandWithOwner() {
        Island island = Island.create(islandId, defaultBounds, ownerUuid, ownerProfileId, now);

        assertThat(island.id()).isEqualTo(islandId);
        assertThat(island.isOwner(ownerProfileId)).isTrue();
        assertThat(island.isMember(ownerProfileId)).isTrue();
        assertThat(island.roleOf(ownerProfileId)).isEqualTo(IslandRole.OWNER);
        assertThat(island.hasPermission(ownerProfileId, IslandPermission.BLOCK_BREAK))
                .isTrue();
        assertThat(island.hasPermission(ownerProfileId, IslandPermission.SETTINGS_MODIFY))
                .isTrue();
    }

    @Test
    @DisplayName("unknown profile resolves to VISITOR role with visitor permissions")
    void unknownProfileResolvesToVisitor() {
        Island island = Island.create(islandId, defaultBounds, ownerUuid, ownerProfileId, now);
        ProfileId visitorProfile = ProfileId.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));

        assertThat(island.isMember(visitorProfile)).isFalse();
        assertThat(island.roleOf(visitorProfile)).isEqualTo(IslandRole.VISITOR);
        assertThat(island.hasPermission(visitorProfile, IslandPermission.BLOCK_BREAK))
                .isFalse();
    }

    @Test
    @DisplayName("adding, updating and removing island members")
    void memberLifecycle() {
        Island island = Island.create(islandId, defaultBounds, ownerUuid, ownerProfileId, now);
        PlayerUuid memberUuid = PlayerUuid.of(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        ProfileId memberProfile = ProfileId.of(UUID.fromString("66666666-6666-6666-6666-666666666666"));

        IslandMember member = new IslandMember(memberUuid, memberProfile, IslandRole.MEMBER, now);
        Island withMember = island.addMember(member);

        assertThat(withMember.isMember(memberProfile)).isTrue();
        assertThat(withMember.roleOf(memberProfile)).isEqualTo(IslandRole.MEMBER);
        assertThat(withMember.hasPermission(memberProfile, IslandPermission.BLOCK_BREAK))
                .isTrue();
        assertThat(withMember.hasPermission(memberProfile, IslandPermission.MEMBER_KICK))
                .isFalse();

        // Promote member to MODERATOR
        IslandMember promoted = member.withRole(IslandRole.MODERATOR);
        Island withPromoted = withMember.addMember(promoted);
        assertThat(withPromoted.roleOf(memberProfile)).isEqualTo(IslandRole.MODERATOR);
        assertThat(withPromoted.hasPermission(memberProfile, IslandPermission.MEMBER_KICK))
                .isTrue();

        // Remove member
        Island removed = withPromoted.removeMember(memberProfile);
        assertThat(removed.isMember(memberProfile)).isFalse();
        assertThat(removed.roleOf(memberProfile)).isEqualTo(IslandRole.VISITOR);

        // Cannot remove owner
        assertThatThrownBy(() -> removed.removeMember(ownerProfileId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot remove island owner");
    }

    @Test
    @DisplayName("transfer ownership promotes new owner and demotes former owner to CO_OWNER")
    void transferOwnership() {
        Island island = Island.create(islandId, defaultBounds, ownerUuid, ownerProfileId, now);
        PlayerUuid newOwnerUuid = PlayerUuid.of(UUID.fromString("77777777-7777-7777-7777-777777777777"));
        ProfileId newOwnerProfile = ProfileId.of(UUID.fromString("88888888-8888-8888-8888-888888888888"));

        Island transferred = island.transferOwnership(newOwnerUuid, newOwnerProfile);

        assertThat(transferred.ownerProfileId()).isEqualTo(newOwnerProfile);
        assertThat(transferred.ownerPlayerUuid()).isEqualTo(newOwnerUuid);
        assertThat(transferred.roleOf(newOwnerProfile)).isEqualTo(IslandRole.OWNER);
        assertThat(transferred.roleOf(ownerProfileId)).isEqualTo(IslandRole.CO_OWNER);
    }

    @Test
    @DisplayName("island flags defaults and modification")
    void islandFlags() {
        Island island = Island.create(islandId, defaultBounds, ownerUuid, ownerProfileId, now);
        assertThat(island.flags().isEnabled(IslandFlags.PVP)).isFalse();
        assertThat(island.flags().isEnabled(IslandFlags.LEAF_DECAY)).isTrue();

        Island updated = island.withFlags(island.flags().withFlag(IslandFlags.PVP, true));
        assertThat(updated.flags().isEnabled(IslandFlags.PVP)).isTrue();
    }

    @Test
    @DisplayName("island bounds containment and expansion")
    void islandBounds() {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(1000, 2000, 50);
        assertThat(bounds.contains(1000, 2000)).isTrue();
        assertThat(bounds.contains(950, 1950)).isTrue();
        assertThat(bounds.contains(1050, 2050)).isTrue();
        assertThat(bounds.contains(949, 2000)).isFalse();
        assertThat(bounds.contains(1000, 2051)).isFalse();

        IslandBounds expanded = bounds.expand(50);
        assertThat(expanded.radius()).isEqualTo(100);
        assertThat(expanded.contains(949, 2000)).isTrue();
        assertThat(expanded.contains(900, 2000)).isTrue();
        assertThat(expanded.contains(899, 2000)).isFalse();
    }

    @Test
    @DisplayName("role management and weight hierarchy")
    void roleHierarchy() {
        assertThat(IslandRole.OWNER.canManage(IslandRole.CO_OWNER)).isTrue();
        assertThat(IslandRole.CO_OWNER.canManage(IslandRole.MODERATOR)).isTrue();
        assertThat(IslandRole.MODERATOR.canManage(IslandRole.MEMBER)).isTrue();
        assertThat(IslandRole.MEMBER.canManage(IslandRole.VISITOR)).isTrue();

        assertThat(IslandRole.MEMBER.canManage(IslandRole.OWNER)).isFalse();
        assertThat(IslandRole.MEMBER.canManage(IslandRole.MEMBER)).isFalse();
    }
}
