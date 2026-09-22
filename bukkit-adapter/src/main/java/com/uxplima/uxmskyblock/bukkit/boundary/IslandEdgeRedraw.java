package com.uxplima.uxmskyblock.bukkit.boundary;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.border.IslandBorderService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;

/**
 * Shows the new edge to the members standing on an island that just grew.
 *
 * <p>Buying the size upgrade moved the edge in the database and told the protection index, and told
 * no client: a member standing on their own island when the upgrade landed kept the old wall until
 * they walked off it and back. The boundary service has had a method for exactly this since the
 * boundary work and nothing called it, which is what the record it takes says in its own
 * description: "for a caller that has to reindex or redraw it".
 *
 * <p>Only the members who are here and standing inside the new edge are shown one. A member on
 * another island has somebody else's wall on their screen and this is not the moment to replace it.
 */
public final class IslandEdgeRedraw {

    /** Where a member is right now, or nothing when they are away. */
    @FunctionalInterface
    public interface WhereTheyStand {
        Optional<StandingAt> of(PlayerUuid playerUuid);
    }

    /** A place on the server, in the only terms this rule asks about. */
    public record StandingAt(String worldName, int blockX, int blockZ) {
        public StandingAt {
            Objects.requireNonNull(worldName, "worldName must not be null");
        }
    }

    private final IslandBoundaryService boundary;
    private final WhereTheyStand players;

    public IslandEdgeRedraw(IslandBoundaryService boundary, WhereTheyStand players) {
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
        this.players = Objects.requireNonNull(players, "players must not be null");
    }

    /**
     * Shows the edge to everybody it moved for, and gives back how many were shown.
     *
     * <p>It is sent at once, with no transition, because every other border this plugin sends is
     * sent at once and a grow is not the place to invent a different rule.
     */
    public int show(IslandBorderService.Moved moved) {
        Objects.requireNonNull(moved, "moved must not be null");
        IslandBounds after = moved.location().bounds();
        IslandBounds before = IslandBounds.fromCenterAndRadius(after.centerX(), after.centerZ(), moved.fromRadius());

        int shown = 0;
        for (IslandMember member : moved.island().members().values()) {
            Optional<StandingAt> standing = players.of(member.playerUuid());
            if (standing.isEmpty()) {
                continue;
            }
            StandingAt at = standing.get();
            if (!at.worldName().equals(moved.location().worldName()) || !after.contains(at.blockX(), at.blockZ())) {
                continue;
            }
            boundary.handleIslandExpand(member.playerUuid(), before, after, 0L);
            shown++;
        }
        return shown;
    }
}
