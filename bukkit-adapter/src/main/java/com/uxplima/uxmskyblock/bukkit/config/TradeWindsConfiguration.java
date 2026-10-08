package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Material;

import com.uxplima.uxmskyblock.core.application.tradewinds.Port;
import com.uxplima.uxmskyblock.core.application.tradewinds.Standing;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The TradeWinds game mode, as {@code modules/tradewinds.conf} writes it: islands that are trading vessels
 * on a sea of their own.
 *
 * @param enabled whether islands can be made as TradeWinds vessels
 * @param sea the sea a new vessel is launched on
 * @param holdRows how many rows of nine slots a vessel's cargo hold has, from 1 to 6
 * @param ports the ports vessels sail between, in the order the file writes them
 * @param icons the item each port is shown as, by its key
 * @param standing how a vessel's standing in a port moves its prices there
 */
public record TradeWindsConfiguration(
        boolean enabled, Sea sea, int holdRows, List<Port> ports, Map<String, String> icons, Standing standing) {

    /** The standing a file that writes none has: one percent for every 500.00 of trade, up to ten. */
    public static final Standing SHIPPED_STANDING = new Standing(50_000, 1, 10);

    /** The ports a file that writes none has, with trade routes between them. */
    public static final List<Port> SHIPPED_PORTS = List.of(
            new Port(
                    "emerald-bay",
                    60,
                    List.of(
                            new Port.Good("WHEAT", 16, 150, 250),
                            new Port.Good("PUMPKIN", 8, 400, 700),
                            new Port.Good("EMERALD", 4, 8_000, 12_000))),
            new Port(
                    "saltmarsh",
                    90,
                    List.of(
                            new Port.Good("WHEAT", 16, 400, 600),
                            new Port.Good("SUGAR_CANE", 32, 100, 180),
                            new Port.Good("GLASS", 16, 500, 800))),
            new Port(
                    "ironhaven",
                    120,
                    List.of(
                            new Port.Good("SUGAR_CANE", 32, 260, 350),
                            new Port.Good("PUMPKIN", 8, 900, 1_200),
                            new Port.Good("IRON_INGOT", 8, 1_800, 2_600),
                            new Port.Good("EMERALD", 4, 14_000, 18_000))));

    /** The items the shipped ports are shown as. */
    public static final Map<String, String> SHIPPED_ICONS =
            Map.of("emerald-bay", "EMERALD", "saltmarsh", "PRISMARINE_SHARD", "ironhaven", "IRON_INGOT");

    /** The rows of a hold when the file names none, or none that fits a chest. */
    public static final int SHIPPED_HOLD_ROWS = 3;

    private static final Logger LOGGER = Logger.getLogger(TradeWindsConfiguration.class.getName());

    public TradeWindsConfiguration {
        Objects.requireNonNull(sea, "sea must not be null");
        Objects.requireNonNull(standing, "standing must not be null");
        if (holdRows < 1 || holdRows > 6) {
            throw new IllegalArgumentException("a hold has from 1 to 6 rows");
        }
        ports = List.copyOf(ports);
        icons = Map.copyOf(icons);
    }

    /** The port the file writes under {@code id}, if it writes one. */
    public Optional<Port> port(String id) {
        return ports.stream().filter(port -> port.id().equals(id)).findFirst();
    }

    /** The item {@code port} is shown as. */
    public String icon(Port port) {
        return icons.getOrDefault(port.id(), "OAK_BOAT");
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
        return new TradeWindsConfiguration(
                true, Sea.SHIPPED, SHIPPED_HOLD_ROWS, SHIPPED_PORTS, SHIPPED_ICONS, SHIPPED_STANDING);
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
        Map<String, String> icons = new LinkedHashMap<>();
        List<Port> ports = ports(root.node("ports"), icons);
        return new TradeWindsConfiguration(
                root.node("enabled").getBoolean(true), sea, rows, ports, icons, standing(root.node("standing")));
    }

    /** The standing the file writes, or the shipped one when it writes one that cannot be. */
    private static Standing standing(ConfigurationNode node) {
        try {
            return new Standing(
                    node.node("step").getLong(SHIPPED_STANDING.step()),
                    node.node("percent").getInt(SHIPPED_STANDING.percent()),
                    node.node("max-steps").getInt(SHIPPED_STANDING.maxSteps()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(
                    () -> "modules/tradewinds.conf standing: " + e.getMessage() + ". The shipped standing is used.");
            return SHIPPED_STANDING;
        }
    }

    /**
     * The ports the file writes. A port or a good that cannot be is left out with a warning, and the rest
     * are kept; a file that writes no ports block at all has the shipped ports.
     */
    private static List<Port> ports(ConfigurationNode node, Map<String, String> icons) {
        if (node.virtual()) {
            icons.putAll(SHIPPED_ICONS);
            return SHIPPED_PORTS;
        }
        List<Port> ports = new ArrayList<>();
        for (ConfigurationNode written : node.childrenList()) {
            String id = written.node("id").getString("");
            List<Port.Good> goods = new ArrayList<>();
            for (ConfigurationNode good : written.node("market").childrenList()) {
                String item = good.node("item").getString("").toUpperCase(java.util.Locale.ROOT);
                Material material = Material.matchMaterial(item);
                if (material == null || material.isAir()) {
                    LOGGER.warning(() -> "modules/tradewinds.conf port " + id + " trades '" + item
                            + "', which is no item. It is left out.");
                    continue;
                }
                try {
                    goods.add(new Port.Good(
                            material.name(),
                            good.node("lot").getInt(1),
                            good.node("pays").getLong(0),
                            good.node("asks").getLong(0)));
                } catch (IllegalArgumentException e) {
                    LOGGER.warning(() -> "modules/tradewinds.conf port " + id + ", " + item + ": " + e.getMessage()
                            + ". It is left out.");
                }
            }
            try {
                Port port = new Port(id, written.node("voyage-seconds").getInt(60), goods);
                if (ports.stream().anyMatch(other -> other.id().equals(id))) {
                    throw new IllegalArgumentException("another port is written under the same id");
                }
                ports.add(port);
                Material shown = Material.matchMaterial(written.node("icon").getString("OAK_BOAT"));
                icons.put(id, shown == null || shown.isAir() ? "OAK_BOAT" : shown.name());
            } catch (IllegalArgumentException e) {
                LOGGER.warning(
                        () -> "modules/tradewinds.conf port '" + id + "': " + e.getMessage() + ". It is left out.");
            }
        }
        return List.copyOf(ports);
    }
}
