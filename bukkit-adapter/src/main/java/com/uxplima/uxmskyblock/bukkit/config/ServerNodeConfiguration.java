package com.uxplima.uxmskyblock.bukkit.config;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration record specifying server cluster node identity and default world parameters.
 *
 * <p>Fails fast at bootstrap if server node identity is missing or unconfigured, preventing
 * split-brain or node collision issues in multi-server distributed deployments.
 */
public record ServerNodeConfiguration(ServerNodeId nodeId, String worldName) {

    public static final String DEFAULT_WORLD_NAME = "world";

    public ServerNodeConfiguration {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("world-name must not be blank");
        }
    }

    public static ServerNodeConfiguration of(ServerNodeId nodeId, String worldName) {
        return new ServerNodeConfiguration(nodeId, worldName);
    }

    public static ServerNodeConfiguration of(String nodeId, String worldName) {
        return new ServerNodeConfiguration(ServerNodeId.of(nodeId), worldName);
    }

    /**
     * Translates a {@link ConfigurationNode} into a validated {@link ServerNodeConfiguration}.
     *
     * @param rootNode the root configuration node
     * @return validated server node configuration
     * @throws IllegalStateException if the node identity is missing or blank
     * @throws IllegalArgumentException if configured attributes violate invariants
     */
    public static ServerNodeConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");

        ConfigurationNode nodeConfig = rootNode.node("server-node");
        String rawNodeId = nodeConfig.node("id").getString();
        if (rawNodeId == null || rawNodeId.isBlank()) {
            rawNodeId = rootNode.node("node-id").getString();
        }

        if (rawNodeId == null || rawNodeId.isBlank()) {
            throw new IllegalStateException(
                    "Server node identity is missing or blank! A non-empty 'server-node.id' must be configured in configuration.");
        }

        String rawWorldName = nodeConfig.node("world-name").getString();
        if (rawWorldName == null) {
            rawWorldName = rootNode.node("world-name").getString();
        }

        String worldName;
        if (rawWorldName == null) {
            worldName = DEFAULT_WORLD_NAME;
        } else if (rawWorldName.isBlank()) {
            throw new IllegalArgumentException("world-name must not be blank");
        } else {
            worldName = rawWorldName.trim();
        }

        return new ServerNodeConfiguration(ServerNodeId.of(rawNodeId.trim()), worldName);
    }
}
