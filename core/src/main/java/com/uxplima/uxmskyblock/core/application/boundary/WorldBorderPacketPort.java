package com.uxplima.uxmskyblock.core.application.boundary;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Outbound port for clientbound virtual world border manipulation.
 */
public interface WorldBorderPacketPort {

    /**
     * Sends or animates a virtual world border for a specific player.
     *
     * @param playerUuid recipient player
     * @param centerX border center X coordinate
     * @param centerZ border center Z coordinate
     * @param radius target bounding radius (half diameter)
     * @param oldRadius prior bounding radius (0 if immediate)
     * @param transitionDurationMs duration of expansion transition in milliseconds (0 if immediate)
     */
    void sendWorldBorder(
            PlayerUuid playerUuid,
            int centerX,
            int centerZ,
            double radius,
            double oldRadius,
            long transitionDurationMs);

    /**
     * Resets any virtual world border for the player, reverting to default server world border.
     *
     * @param playerUuid recipient player
     */
    void resetWorldBorder(PlayerUuid playerUuid);
}
