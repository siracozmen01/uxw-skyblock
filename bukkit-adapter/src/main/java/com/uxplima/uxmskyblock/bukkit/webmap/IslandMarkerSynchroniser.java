package com.uxplima.uxmskyblock.bukkit.webmap;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Puts islands on the web map, and takes them off again.
 *
 * <p>Three web map integrations shipped with this plugin: Dynmap, BlueMap and Pl3xMap. All three
 * were built, all three were wired into a composite, and nothing ever called one. Every server
 * running this plugin with Dynmap installed had a map with no islands on it, and no way to tell
 * whether that was a bug or a setting.
 *
 * <p>Nothing here runs on an event thread. A marker is a call into another plugin and a read of an
 * island, and neither belongs under a player's cursor.
 */
public final class IslandMarkerSynchroniser {

    private static final Logger LOGGER = Logger.getLogger(IslandMarkerSynchroniser.class.getName());

    private final WebMapAdapter webMap;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;

    public IslandMarkerSynchroniser(
            WebMapAdapter webMap, IslandLocationService islandLocationService, SchedulerPort schedulerPort) {
        this.webMap = Objects.requireNonNull(webMap, "webMap must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    /** Draws an island that has just been made, off the thread that made it. */
    public void onIslandCreated(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        schedulerPort.async(() -> draw(islandId, false));
    }

    /** Redraws an island whose name or shape has changed. */
    public void onIslandChanged(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        schedulerPort.async(() -> draw(islandId, true));
    }

    /** Takes an island off the map, for a delete or a reset. */
    public void onIslandRemoved(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        schedulerPort.async(() -> {
            try {
                webMap.removeIslandMarker(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "A web map refused to remove the marker for " + islandId);
            }
        });
    }

    /**
     * Draws every island the world holds, once, at startup.
     *
     * <p>A map that only learns about islands made since the last restart is a map with holes in it,
     * and the holes are the oldest and largest islands.
     */
    public void drawAll(List<Island> islands) {
        Objects.requireNonNull(islands, "islands must not be null");
        if (!webMap.isAvailable()) {
            return;
        }
        schedulerPort.async(() -> {
            int drawn = 0;
            for (Island island : islands) {
                if (drawMarker(island, false)) {
                    drawn++;
                }
            }
            int total = drawn;
            LOGGER.info(() -> "Drew " + total + " island(s) on " + webMap.providerName() + ".");
        });
    }

    private void draw(IslandId islandId, boolean update) {
        if (!webMap.isAvailable()) {
            return;
        }
        Optional<Island> optIsland = islandLocationService.findIsland(islandId);
        if (optIsland.isEmpty()) {
            return;
        }
        var unused = drawMarker(optIsland.get(), update);
    }

    /**
     * Sends one island to the map.
     *
     * <p>A web map that throws is a web map that is not there, and a plugin of ours does not stop
     * because another plugin does. The failure is logged at a level an operator can turn on when
     * they are looking for it, and not at one that fills the console when they are not.
     */
    private boolean drawMarker(Island island, boolean update) {
        Optional<IslandLocation> optLocation = islandLocationService.findLocation(island.id());
        if (optLocation.isEmpty()) {
            return false;
        }
        IslandLocation location = optLocation.get();
        // The island record carries no display name: the custom name lives in its own table, read by
        // IslandNameService. A marker labelled by id is still a marker, and labelling it by a name
        // this class would have to fetch per island would turn a redraw into a query storm.
        String name = "Island " + island.id().value().toString().substring(0, 8);
        try {
            if (update) {
                webMap.updateIslandMarker(
                        island.id(),
                        name,
                        location.worldName(),
                        location.spawnX(),
                        location.spawnY(),
                        location.spawnZ());
            } else {
                webMap.registerIslandMarker(
                        island.id(),
                        name,
                        location.worldName(),
                        location.spawnX(),
                        location.spawnY(),
                        location.spawnZ());
            }
            return true;
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> "A web map refused the marker for " + island.id());
            return false;
        }
    }
}
