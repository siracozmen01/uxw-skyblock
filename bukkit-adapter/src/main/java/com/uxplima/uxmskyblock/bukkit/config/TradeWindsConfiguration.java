package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;
import java.util.logging.Logger;

import org.spongepowered.configurate.ConfigurationNode;

/**
 * The TradeWinds game mode, as {@code modules/tradewinds.conf} writes it: islands that are trading vessels
 * on a sea of their own.
 *
 * @param enabled whether islands can be made as TradeWinds vessels
 * @param sea the sea a new vessel is launched on
 * @param holdRows how many rows of nine slots a vessel's cargo hold has, from 1 to 6
 */
public record TradeWindsConfiguration(boolean enabled, Sea sea, int holdRows) {

    /** The rows of a hold when the file names none, or none that fits a chest. */
    public static final int SHIPPED_HOLD_ROWS = 3;

    private static final Logger LOGGER = Logger.getLogger(TradeWindsConfiguration.class.getName());

    public TradeWindsConfiguration {
        Objects.requireNonNull(sea, "sea must not be null");
        if (holdRows < 1 || holdRows > 6) {
            throw new IllegalArgumentException("a hold has from 1 to 6 rows");
        }
    }

    /**
     * The sea under a new vessel.
     *
     * @param radius how far the sea reaches from the vessel, each way
     * @param depth how deep the sea is under its surface
     * @param floor the block the sea lies on; one that does not fall
     */
    public record Sea(int radius, int depth, String floor) {

        /** The widest sea a vessel is launched on. */
        public static final int MAX_RADIUS = 128;

        /** The deepest sea. */
        public static final int MAX_DEPTH = 64;

        public static final Sea SHIPPED = new Sea(48, 12, "SANDSTONE");

        public Sea {
            Objects.requireNonNull(floor, "floor must not be null");
            if (radius < 8 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("the radius must be from 8 to " + MAX_RADIUS);
            }
            if (depth < 2 || depth > MAX_DEPTH) {
                throw new IllegalArgumentException("the depth must be from 2 to " + MAX_DEPTH);
            }
        }
    }

    public static TradeWindsConfiguration defaultConfiguration() {
        return new TradeWindsConfiguration(true, Sea.SHIPPED, SHIPPED_HOLD_ROWS);
    }

    public static TradeWindsConfiguration load(ConfigurationNode root) {
        ConfigurationNode seaNode = root.node("sea");
        Sea sea;
        try {
            sea = new Sea(
                    seaNode.node("radius").getInt(Sea.SHIPPED.radius()),
                    seaNode.node("depth").getInt(Sea.SHIPPED.depth()),
                    seaNode.node("floor").getString(Sea.SHIPPED.floor()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/tradewinds.conf sea: " + e.getMessage() + ". The shipped sea is used.");
            sea = Sea.SHIPPED;
        }
        int written = root.node("hold", "rows").getInt(SHIPPED_HOLD_ROWS);
        int rows = written;
        if (rows < 1 || rows > 6) {
            LOGGER.warning(() -> "modules/tradewinds.conf hold.rows is " + written + ", not from 1 to 6. The hold has "
                    + SHIPPED_HOLD_ROWS + " rows.");
            rows = SHIPPED_HOLD_ROWS;
        }
        return new TradeWindsConfiguration(root.node("enabled").getBoolean(true), sea, rows);
    }
}
