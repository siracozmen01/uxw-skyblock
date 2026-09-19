package com.uxplima.uxmskyblock.bukkit.webmap;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Common outbound adapter port for rendering dynamic web map markers and island territories (Section 2.42).
 *
 * <p>Supports multi-provider installations (Dynmap, BlueMap, Pl3xMap) via a composite delegator.
 */
public interface WebMapAdapter {

    /**
     * Registers or creates a new island marker on the active web map provider.
     *
     * @param islandId unique island identifier
     * @param islandName display name or identifier for the island
     * @param worldName target Bukkit world name
     * @param x island center x coordinate
     * @param y island center y coordinate
     * @param z island center z coordinate
     */
    void registerIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z);

    /**
     * Updates an existing island marker location and label on the web map provider.
     *
     * @param islandId unique island identifier
     * @param islandName display name or identifier for the island
     * @param worldName target Bukkit world name
     * @param x island center x coordinate
     * @param y island center y coordinate
     * @param z island center z coordinate
     */
    void updateIslandMarker(IslandId islandId, String islandName, String worldName, double x, double y, double z);

    /**
     * Removes an island marker from the web map provider.
     *
     * @param islandId unique island identifier
     */
    void removeIslandMarker(IslandId islandId);

    /**
     * Returns the human-readable identifier of this map provider (e.g. DYNMAP, BLUEMAP, PL3XMAP).
     */
    String providerName();

    /**
     * Checks if the underlying web map plugin is installed and available.
     */
    boolean isAvailable();
}
