package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Which StrangerRealms island a spot belongs to, and on which side of it. The Upside Down mirrors the
 * island's land at the same place, so a spot in it belongs to the island at that place in the island's
 * own world. Memory only.
 */
public final class Realms {

    /**
     * A spot on a StrangerRealms island.
     *
     * @param landWorld the world the island's land is in
     * @param upsideDown whether the spot is in the Upside Down rather than on the land
     */
    public record Place(IslandId island, String landWorld, boolean upsideDown) {

        /** The world on the other side of the veil from this spot. */
        public String otherWorld(String upsideDownWorld) {
            return upsideDown ? landWorld : upsideDownWorld;
        }
    }

    private final StrangerRealmsService service;
    private final IslandProtectionListener islands;
    private final Supplier<String> upsideDownWorld;
    private final Supplier<List<String>> landWorlds;

    /** @param landWorlds the worlds islands are made in */
    public Realms(
            StrangerRealmsService service,
            IslandProtectionListener islands,
            Supplier<String> upsideDownWorld,
            Supplier<List<String>> landWorlds) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.upsideDownWorld = Objects.requireNonNull(upsideDownWorld, "upsideDownWorld must not be null");
        this.landWorlds = Objects.requireNonNull(landWorlds, "landWorlds must not be null");
    }

    public String upsideDownWorld() {
        return upsideDownWorld.get();
    }

    /** The StrangerRealms island the spot belongs to and its side, or empty for anywhere else. */
    public Optional<Place> placeOf(String world, int x, int z) {
        boolean upsideDown = world.equals(upsideDownWorld.get());
        if (upsideDown) {
            for (String land : landWorlds.get()) {
                Optional<Island> island = islands.spatialIndex().findIslandAt(land, x, z);
                if (island.isPresent()) {
                    return stranger(island.get(), land, true);
                }
            }
            return Optional.empty();
        }
        if (!landWorlds.get().contains(world)) {
            return Optional.empty();
        }
        return islands.spatialIndex().findIslandAt(world, x, z).flatMap(island -> stranger(island, world, false));
    }

    private Optional<Place> stranger(Island island, String land, boolean upsideDown) {
        return service.isStranger(island.id())
                ? Optional.of(new Place(island.id(), land, upsideDown))
                : Optional.empty();
    }
}
