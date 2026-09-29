package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * {@code modules/chunkblock.conf}: whether islands can be ChunkBlock islands, the level each chunk
 * beyond the first needs, and the node that walks through a closed chunk.
 *
 * @param bypassPermission a node whose holder is not stopped at a closed chunk, empty for nobody
 */
public record ChunkBlockConfiguration(boolean enabled, ChunkUnlockRules rules, String bypassPermission) {

    private static final Logger LOGGER = Logger.getLogger(ChunkBlockConfiguration.class.getName());

    public ChunkBlockConfiguration {
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(bypassPermission, "bypassPermission must not be null");
    }

    public static ChunkBlockConfiguration defaultConfiguration() {
        return new ChunkBlockConfiguration(true, ChunkUnlockRules.shipped(), "");
    }

    public static ChunkBlockConfiguration load(ConfigurationNode root) {
        ChunkUnlockRules shipped = ChunkUnlockRules.shipped();
        List<Long> levels = new ArrayList<>();
        ConfigurationNode written = root.node("unlock-levels");
        if (written.virtual()) {
            levels.addAll(shipped.levels());
        } else {
            for (ConfigurationNode level : written.childrenList()) {
                levels.add(level.getLong(0));
            }
        }
        ChunkUnlockRules rules;
        try {
            rules = new ChunkUnlockRules(levels, root.node("then-every").getLong(shipped.thenEvery()));
        } catch (IllegalArgumentException e) {
            LOGGER.warning(() -> "modules/chunkblock.conf: " + e.getMessage() + ". The shipped levels are used.");
            rules = shipped;
        }
        return new ChunkBlockConfiguration(
                root.node("enabled").getBoolean(true),
                rules,
                root.node("bypass-permission").getString("").trim());
    }
}
