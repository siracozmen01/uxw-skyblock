package com.uxplima.uxmskyblock.core.application.border;

import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Moves an island's edge to wherever its size upgrade says it should be.
 *
 * <p>The island size upgrade had five tiers, a cost for each and a radius on each, and nothing ever
 * read the radius. An island that paid a million for the top tier reached exactly as far as one that
 * had paid nothing: the bounds written when the island was created were the bounds it had for ever,
 * and the protection index, the boundary warning and every block check read those bounds.
 *
 * <p>The bounds stay the one place the answer lives, because everything already reads them. This
 * writes them, once, when the tier moves.
 */
public final class IslandBorderService {

    private final IslandStoragePort islandStoragePort;
    private final ToIntFunction<IslandId> allowance;
    private final IslandMutationLock mutationLock;

    public IslandBorderService(
            IslandStoragePort islandStoragePort, ToIntFunction<IslandId> allowance, IslandMutationLock mutationLock) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.allowance = Objects.requireNonNull(allowance, "allowance must not be null");
        this.mutationLock = Objects.requireNonNull(mutationLock, "mutationLock must not be null");
    }

    /**
     * Puts the island's edge where its tier says, and says whether it moved.
     *
     * <p>Held under the island's mutation lock, because it reads the island and writes it back and
     * every other writer of the aggregate is held the same way.
     *
     * @return the island and its place as they now stand, or empty when the edge was already right
     */
    public Optional<Moved> applyAllowance(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return mutationLock.inside(islandId, () -> moveInside(islandId));
    }

    /** What the border moved from and to, for a caller that has to reindex or redraw it. */
    public record Moved(Island island, IslandLocation location, int fromRadius, int toRadius) {}

    private Optional<Moved> moveInside(IslandId islandId) {
        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        Optional<IslandLocation> optLocation = islandStoragePort.findLocationByIslandId(islandId);
        if (optIsland.isEmpty() || optLocation.isEmpty()) {
            return Optional.empty();
        }
        Island island = optIsland.get();
        IslandLocation location = optLocation.get();

        int allowed = allowance.applyAsInt(islandId);
        int current = location.bounds().radius();
        if (allowed == current) {
            return Optional.empty();
        }

        IslandBounds grown = IslandBounds.fromCenterAndRadius(
                location.bounds().centerX(), location.bounds().centerZ(), allowed);
        IslandLocation movedLocation = new IslandLocation(
                location.islandId(),
                location.worldName(),
                grown,
                location.spawnX(),
                location.spawnY(),
                location.spawnZ(),
                location.spawnYaw(),
                location.spawnPitch());
        Island movedIsland = island.withBounds(grown);
        islandStoragePort.saveIsland(movedIsland, movedLocation);
        return Optional.of(new Moved(movedIsland, movedLocation, current, allowed));
    }
}
