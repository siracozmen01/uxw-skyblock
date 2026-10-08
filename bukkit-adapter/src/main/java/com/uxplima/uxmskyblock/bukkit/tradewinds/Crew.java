package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.result.Result;

/**
 * Whether a player crews the vessel they stand on: aboard it, and of its island's team.
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

    /** The vessel {@code player} crews and stands on, or the key of why they do not. */
    public Result<IslandId, String> aboard(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islandAt.apply(at);
        if (island.isEmpty() || !vessels.isVessel(island.get().id())) {
            return Result.err("tradewinds.hold.not_vessel");
        }
        ActiveSession session = sessions.session(player.getUniqueId());
        if (session == null) {
            return Result.err("tradewinds.hold.busy");
        }
        if (!island.get().isOwner(session.activeProfileId()) && !island.get().isMember(session.activeProfileId())) {
            return Result.err("tradewinds.hold.not_crew");
        }
        return Result.ok(island.get().id());
    }

    /** Whether {@code player} still crews {@code vessel} and stands on it, or the key of why not. */
    public Result<IslandId, String> aboard(Player player, IslandId vessel) {
        Result<IslandId, String> found = aboard(player);
        return !found.isOk() || found.orElseThrow().equals(vessel) ? found : Result.err("tradewinds.hold.not_vessel");
    }
}
