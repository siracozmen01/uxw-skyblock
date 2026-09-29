package com.uxplima.uxmskyblock.core.application.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The placement strategies a node may be told to use, by name.
 *
 * <p>The plugin ships four. Another plugin may add its own under a name of its own, and the operator
 * picks one in {@code server-node.placement.strategy}. A name is taken once: a second strategy under
 * a name already held is refused, so no plugin quietly replaces another's.
 */
public final class PlacementStrategies {

    public static final String ROUND_ROBIN = "round-robin";
    public static final String LEAST_LOADED = "least-loaded";
    public static final String MSPT_AWARE = "mspt-aware";
    public static final String CAPACITY_WEIGHTED = "capacity-weighted";

    private final Map<String, PlacementStrategy> byName = new LinkedHashMap<>();

    /** The four the plugin ships, with the tick time above which {@code mspt-aware} avoids a node. */
    public static PlacementStrategies shipped(double msptCeiling) {
        PlacementStrategies strategies = new PlacementStrategies();
        strategies.register(ROUND_ROBIN, PlacementStrategy.roundRobin());
        strategies.register(LEAST_LOADED, PlacementStrategy.leastLoaded());
        strategies.register(MSPT_AWARE, PlacementStrategy.msptAware(msptCeiling));
        strategies.register(CAPACITY_WEIGHTED, PlacementStrategy.capacityWeighted());
        return strategies;
    }

    /**
     * Adds a strategy under a name.
     *
     * @throws IllegalArgumentException if the name is blank or already taken
     */
    public synchronized void register(String name, PlacementStrategy strategy) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        String key = name.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A placement strategy needs a name");
        }
        if (byName.putIfAbsent(key, strategy) != null) {
            throw new IllegalArgumentException("The placement strategy " + key + " is already registered");
        }
    }

    public synchronized Optional<PlacementStrategy> named(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return Optional.ofNullable(byName.get(name.trim()));
    }

    public synchronized Set<String> names() {
        return Set.copyOf(byName.keySet());
    }
}
