package com.uxplima.uxmskyblock.core.application.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An island's owner makes roles of their own, and takes them away, and nobody else does. */
class IslandRoleShaperTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());
    private static final ProfileId MATE = new ProfileId(UUID.randomUUID());
    private static final PlayerUuid MATE_UUID = PlayerUuid.of(UUID.randomUUID());
    private static final ProfileId STRANGER = new ProfileId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private IslandStoragePort storage;
    private Island island;
    private final List<IslandId> heard = new ArrayList<>();
    private IslandRoleShaper shaper;

    private IslandRole role(String id) {
        return java.util.Objects.requireNonNull(island.roles().get(id), id);
    }

    @BeforeEach
    void setUp() {
        island = Island.create(
                ISLAND, IslandBounds.fromCenterAndRadius(0, 0, 100), PlayerUuid.of(UUID.randomUUID()), OWNER, NOW);
        storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(OWNER)).thenReturn(Optional.of(ISLAND));
        when(storage.findIslandIdByProfileId(MATE)).thenReturn(Optional.of(ISLAND));
        when(storage.findIslandIdByProfileId(STRANGER)).thenReturn(Optional.empty());
        when(storage.findIslandById(ISLAND)).thenAnswer(invocation -> Optional.of(island));
        when(storage.findLocationByIslandId(ISLAND))
                .thenReturn(Optional.of(new IslandLocation(
                        ISLAND, "w", IslandBounds.fromCenterAndRadius(0, 0, 100), 0.5, 100.0, 0.5, 0f, 0f)));
        doAnswer(invocation -> {
                    island = invocation.getArgument(0);
                    return null;
                })
                .when(storage)
                .saveIsland(any(), any());
        shaper = new IslandRoleShaper(storage, new IslandMutationLock(), heard::add);
    }

    @Test
    @DisplayName("A role the owner makes starts with what members may do, between the members and the moderators")
    void aNewRole() {
        island = island.withRole(
                new IslandRole("MEMBER", 400, "Member", java.util.EnumSet.of(IslandPermission.BLOCK_PLACE), false));

        assertThat(shaper.create(OWNER, "Builder", 3)).isEqualTo(new IslandRoleShaper.Outcome.Created("builder"));

        IslandRole builder = role("BUILDER");
        assertThat(builder.permissions()).containsExactly(IslandPermission.BLOCK_PLACE);
        assertThat(builder.weight())
                .isGreaterThan(IslandRole.MEMBER.weight())
                .isLessThan(IslandRole.MODERATOR.weight());
        assertThat(builder.isSystem()).isFalse();
        assertThat(heard).containsExactly(ISLAND);
    }

    @Test
    @DisplayName("Each role made after another sits above it, so the newest outranks none it did not mean to")
    void rolesStackInOrder() {
        shaper.create(OWNER, "builder", 3);
        shaper.create(OWNER, "farmer", 3);

        assertThat(role("FARMER").weight()).isEqualTo(role("BUILDER").weight() + 1);
    }

    @Test
    @DisplayName("Only as many roles as the owner may make, and never a name taken, reserved or unreadable")
    void whatIsRefused() {
        assertThat(shaper.create(OWNER, "builder", 1)).isInstanceOf(IslandRoleShaper.Outcome.Created.class);
        assertThat(shaper.create(OWNER, "farmer", 1)).isEqualTo(new IslandRoleShaper.Outcome.LimitReached(1));
        assertThat(shaper.create(OWNER, "builder", 5)).isEqualTo(new IslandRoleShaper.Outcome.Taken("builder"));
        assertThat(shaper.create(OWNER, "moderator", 5)).isEqualTo(new IslandRoleShaper.Outcome.Taken("moderator"));
        assertThat(shaper.create(OWNER, "mod", 5)).isEqualTo(new IslandRoleShaper.Outcome.Taken("mod"));
        assertThat(shaper.create(OWNER, "list", 5)).isEqualTo(new IslandRoleShaper.Outcome.BadName("list"));
        assertThat(shaper.create(OWNER, "two words", 5)).isInstanceOf(IslandRoleShaper.Outcome.BadName.class);
        assertThat(shaper.create(OWNER, "x", 5)).isInstanceOf(IslandRoleShaper.Outcome.BadName.class);
        assertThat(heard).containsExactly(ISLAND);
    }

    @Test
    @DisplayName("A member, even a co-owner, makes and takes away no role, and a player with no island is told so")
    void onlyTheOwner() {
        island = island.addMember(new IslandMember(MATE_UUID, MATE, IslandRole.CO_OWNER, NOW));

        assertThat(shaper.create(MATE, "builder", 5)).isInstanceOf(IslandRoleShaper.Outcome.NotOwner.class);
        assertThat(shaper.delete(MATE, "builder")).isInstanceOf(IslandRoleShaper.Outcome.NotOwner.class);
        assertThat(shaper.create(STRANGER, "builder", 5)).isInstanceOf(IslandRoleShaper.Outcome.NoIsland.class);
        verify(storage, never()).saveIsland(any(), any());
    }

    @Test
    @DisplayName("Taking a role away makes whoever held it a member again, and the four every island has stay")
    void takingARoleAway() {
        shaper.create(OWNER, "builder", 3);
        island = island.addMember(new IslandMember(MATE_UUID, MATE, role("BUILDER"), NOW));

        assertThat(shaper.delete(OWNER, "Builder")).isEqualTo(new IslandRoleShaper.Outcome.Deleted("builder", 1));

        assertThat(island.roles()).doesNotContainKey("BUILDER");
        assertThat(java.util.Objects.requireNonNull(island.members().get(MATE))
                        .role()
                        .id())
                .isEqualTo("MEMBER");
        assertThat(shaper.delete(OWNER, "member")).isEqualTo(new IslandRoleShaper.Outcome.Shipped("member"));
        assertThat(shaper.delete(OWNER, "farmer")).isEqualTo(new IslandRoleShaper.Outcome.UnknownRole("farmer", "-"));
        assertThat(heard).containsExactly(ISLAND, ISLAND);
    }
}
