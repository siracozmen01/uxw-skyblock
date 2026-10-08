package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * Whether a player crews the vessel they stand on: aboard it, of its island's team, and allowed by their
 * role on the island what they ask to do.
 *
 * <p>Asked on the player's own thread, from the island as it stands, every time a player works a vessel:
 * a window stays open after its player left the crew or the vessel.
 */
public final class Crew {

    private final VesselService vessels;
    private final Function<Location, Optional<Island>> islandAt;
    private final CargoHolds.Sessions sessions;

    public Crew(VesselService vessels, Function<Location, Optional<Island>> islandAt, CargoHolds.Sessions sessions) {
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * The vessel {@code player} crews and stands on, while their role on its island grants every one of
     * {@code needs}, or the key of why not. The island's owner is granted everything.
     */
    public Result<IslandId, String> aboard(Player player, IslandPermission... needs) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islandAt.apply(at);
        if (island.isEmpty() || !vessels.isVessel(island.get().id())) {
            return Result.err("tradewinds.hold.not_vessel");
        }
        ActiveSession session = sessions.session(player.getUniqueId());
        if (session == null) {
            return Result.err("tradewinds.hold.busy");
        }
        ProfileId profile = session.activeProfileId();
        if (island.get().isOwner(profile)) {
            return Result.ok(island.get().id());
        }
        if (!island.get().isMember(profile)) {
            return Result.err("tradewinds.hold.not_crew");
        }
        for (IslandPermission need : needs) {
            if (!island.get().hasPermission(profile, need)) {
                return Result.err("tradewinds.hold.not_allowed");
            }
        }
        return Result.ok(island.get().id());
    }

    /** As {@link #aboard(Player, IslandPermission...)}, on {@code vessel} and no other. */
    public Result<IslandId, String> aboard(Player player, IslandId vessel, IslandPermission... needs) {
        Result<IslandId, String> found = aboard(player, needs);
        return !found.isOk() || found.orElseThrow().equals(vessel) ? found : Result.err("tradewinds.hold.not_vessel");
    }
}
