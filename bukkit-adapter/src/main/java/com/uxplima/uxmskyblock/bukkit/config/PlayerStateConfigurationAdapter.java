package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmlib.common.Durations;
import com.uxplima.uxmskyblock.core.domain.durability.DurabilityMode;
import com.uxplima.uxmskyblock.core.domain.durability.PlayerStateDurabilityConfig;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Adapter responsible for translating external HOCON / Configurate nodes into
 * pure, immutable {@link PlayerStateDurabilityConfig} domain policy records.
 *
 * <p>Preserves strict architectural boundary separation: Configurate and physical
 * configuration syntax remain confined to this adapter layer. Physical file lifecycle
 * and bootstrapping remain deferred to future bootstrap integration.
 */
public final class PlayerStateConfigurationAdapter {

    private PlayerStateConfigurationAdapter() {}

    /**
     * Translates a {@link ConfigurationNode} into a pure {@link PlayerStateDurabilityConfig}.
     *
     * <p>Only the exact canonical token {@code "HYBRID"} is accepted for durability-mode;
     * unknown or unnormalized tokens fail fast.
     *
     * @param rootNode the root configuration node
     * @return the strongly typed player-state durability policy
     * @throws NullPointerException if {@code rootNode} is null
     * @throws IllegalArgumentException if configured values are malformed or violate domain invariants
     */
    public static PlayerStateDurabilityConfig load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode playerStateNode = rootNode.node("player-state");
        if (playerStateNode.virtual() || playerStateNode.empty()) {
            return PlayerStateDurabilityConfig.defaultPolicy();
        }

        String modeRaw = playerStateNode.node("durability-mode").getString();
        DurabilityMode mode;
        if (modeRaw == null) {
            mode = PlayerStateDurabilityConfig.DEFAULT_DURABILITY_MODE;
        } else if ("HYBRID".equals(modeRaw)) {
            mode = DurabilityMode.HYBRID;
        } else if (modeRaw.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid durability-mode: value must not be blank. Supported modes: [HYBRID]");
        } else {
            throw new IllegalArgumentException("Invalid durability-mode: '" + modeRaw + "'. Supported modes: [HYBRID]");
        }

        String intervalRaw = playerStateNode.node("ambient-checkpoint-interval").getString();
        Duration interval;
        if (intervalRaw == null) {
            interval = PlayerStateDurabilityConfig.DEFAULT_AMBIENT_CHECKPOINT_INTERVAL;
        } else if (intervalRaw.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid duration format for player-state.ambient-checkpoint-interval: value must not be blank");
        } else {
            try {
                interval = Durations.parse(intervalRaw);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "Invalid duration format for player-state.ambient-checkpoint-interval: '" + intervalRaw + "'",
                        failure);
            }
        }

        return PlayerStateDurabilityConfig.of(mode, interval);
    }
}
