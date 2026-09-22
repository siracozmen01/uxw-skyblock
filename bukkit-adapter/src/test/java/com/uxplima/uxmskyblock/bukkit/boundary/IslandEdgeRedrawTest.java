package com.uxplima.uxmskyblock.bukkit.boundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.border.IslandBorderService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The members standing on an island that just grew are shown the new edge.
 *
 * <p>Buying the size upgrade moved the edge in the database and told the protection index, and told
 * no client. A member standing on their own island when the upgrade landed kept the old wall until
 * they walked off it and back. The boundary service has had a method for exactly this since the
 * boundary work and nothing called it.
 */
class IslandEdgeRedrawTest {

    private static final ProfileId OWNER = ProfileId.of(UUID.randomUUID());
    private static final ProfileId MATE = ProfileId.of(UUID.randomUUID());

    private static IslandBorderService.Moved grownTo(int fromRadius, int toRadius) {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds after = IslandBounds.fromCenterAndRadius(0, 0, toRadius);
        Island island = Island.create(islandId, after, PlayerUuid.of(OWNER.value()), OWNER, Instant.now())
                .addMember(new IslandMember(PlayerUuid.of(MATE.value()), MATE, IslandRole.MEMBER, Instant.now()));
        IslandLocation location = new IslandLocation(islandId, "skyblock_world", after, 0, 100, 0, 0, 0);
        return new IslandBorderService.Moved(island, location, fromRadius, toRadius);
    }

    private static IslandEdgeRedraw.WhereTheyStand standing(Map<ProfileId, IslandEdgeRedraw.StandingAt> places) {
        return playerUuid -> places.entrySet().stream()
                .filter(entry -> entry.getKey().value().equals(playerUuid.value()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @Test
    @DisplayName("A member standing on the island is shown the edge it grew to")
    void amemberOnTheIslandSeesTheNewEdge() {
        IslandBoundaryService boundary = mock(IslandBoundaryService.class);
        IslandBorderService.Moved moved = grownTo(50, 100);

        int shown = new IslandEdgeRedraw(
                        boundary, standing(Map.of(OWNER, new IslandEdgeRedraw.StandingAt("skyblock_world", 10, 10))))
                .show(moved);

        assertThat(shown).isEqualTo(1);
        verify(boundary)
                .handleIslandExpand(
                        eq(PlayerUuid.of(OWNER.value())),
                        eq(IslandBounds.fromCenterAndRadius(0, 0, 50)),
                        eq(IslandBounds.fromCenterAndRadius(0, 0, 100)),
                        anyLong());
    }

    @Test
    @DisplayName("A member who is away is shown nothing, because there is nobody there to show")
    void anabsentMemberIsNotShownAnything() {
        IslandBoundaryService boundary = mock(IslandBoundaryService.class);

        int shown = new IslandEdgeRedraw(boundary, standing(Map.of())).show(grownTo(50, 100));

        assertThat(shown).isZero();
        verify(boundary, never()).handleIslandExpand(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("A member standing on somebody else's island keeps the wall they are inside")
    void amemberElsewhereKeepsTheirOwnWall() {
        IslandBoundaryService boundary = mock(IslandBoundaryService.class);

        int shown = new IslandEdgeRedraw(
                        boundary,
                        standing(Map.of(
                                OWNER, new IslandEdgeRedraw.StandingAt("another_world", 10, 10),
                                MATE, new IslandEdgeRedraw.StandingAt("skyblock_world", 5000, 5000))))
                .show(grownTo(50, 100));

        assertThat(shown)
                .describedAs("one in another world, one far outside the island in this one")
                .isZero();
    }

    @Test
    @DisplayName("Everybody on the island is shown it, not just the one who paid")
    void everybodyOnTheIslandIsShownIt() {
        IslandBoundaryService boundary = mock(IslandBoundaryService.class);

        int shown = new IslandEdgeRedraw(
                        boundary,
                        standing(Map.of(
                                OWNER, new IslandEdgeRedraw.StandingAt("skyblock_world", 0, 0),
                                MATE, new IslandEdgeRedraw.StandingAt("skyblock_world", 80, 80))))
                .show(grownTo(50, 100));

        assertThat(shown).isEqualTo(2);
    }
}
