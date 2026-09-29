package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The OneBlock game mode as the operator wrote it in {@code modules/oneblock.conf}.
 *
 * <p>Every phase, its length, what the block turns into and what may appear on it are the file's.
 * A name the server does not know as a block or a creature is left out with a warning, so a typo
 * costs one entry and not the mode; a phase left with no block at all is left out the same way.
 *
 * @param enabled whether islands can be made as OneBlock islands at all
 * @param phases the phases, in order
 */
public record OneBlockConfiguration(boolean enabled, OneBlockPhases phases) {

    private static final Logger LOGGER = Logger.getLogger(OneBlockConfiguration.class.getName());

    public OneBlockConfiguration {
        Objects.requireNonNull(phases, "phases");
    }

    /** One phase of plain blocks, for a server whose file holds nothing usable. */
    public static OneBlockConfiguration defaultConfiguration() {
        Map<String, Double> blocks = new LinkedHashMap<>();
        blocks.put(Material.GRASS_BLOCK.name(), 5.0);
        blocks.put(Material.DIRT.name(), 3.0);
        blocks.put(Material.OAK_LOG.name(), 2.0);
        return new OneBlockConfiguration(
                true,
                new OneBlockPhases(
                        List.of(new OneBlockPhase("plains", 500, new WeightedPool(blocks), WeightedPool.empty(), 0)),
                        OneBlockPhases.AfterTheLast.STAY));
    }

    public static OneBlockConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root");
        if (root.virtual() || root.empty()) {
            return defaultConfiguration();
        }
        boolean enabled = root.node("enabled").getBoolean(true);
        OneBlockPhases.AfterTheLast after =
                afterTheLast(root.node("after-last-phase").getString("stay"));
        List<OneBlockPhase> phases = new ArrayList<>();
        // A list, because the phases are gone through in order and a HOCON object keeps none.
        for (ConfigurationNode phase : root.node("phases").childrenList()) {
            String key = phase.node("name").getString("");
            Map<String, Double> blocks = pool(key, phase.node("blocks"), true);
            if (blocks.isEmpty()) {
                LOGGER.warning(() -> "OneBlock phase '" + key + "' has no block the server knows and is left out.");
                continue;
            }
            try {
                phases.add(new OneBlockPhase(
                        key,
                        phase.node("length").getLong(0),
                        new WeightedPool(blocks),
                        new WeightedPool(pool(key, phase.node("creatures"), false)),
                        phase.node("creature-chance").getDouble(0)));
            } catch (IllegalArgumentException unusable) {
                LOGGER.warning(() -> "OneBlock phase '" + key + "' is left out: " + unusable.getMessage());
            }
        }
        if (phases.isEmpty()) {
            LOGGER.warning("modules/oneblock.conf holds no usable phase, so the built in one is used.");
            return new OneBlockConfiguration(enabled, defaultConfiguration().phases());
        }
        return new OneBlockConfiguration(enabled, new OneBlockPhases(phases, after));
    }

    private static OneBlockPhases.AfterTheLast afterTheLast(String written) {
        try {
            return OneBlockPhases.AfterTheLast.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            LOGGER.warning(
                    () -> "after-last-phase '" + written + "' is neither 'stay' nor 'repeat', so 'stay' is used.");
            return OneBlockPhases.AfterTheLast.STAY;
        }
    }

    /** The weights under {@code node}, keyed by the server's own names, keeping only names it knows. */
    private static Map<String, Double> pool(String phase, ConfigurationNode node, boolean blocks) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String written = String.valueOf(entry.getKey());
            String known = blocks ? blockName(written) : creatureName(written);
            if (known == null) {
                LOGGER.warning(() -> "OneBlock phase '" + phase + "' names '" + written + "', which is not a "
                        + (blocks ? "block" : "creature that can be spawned") + ", and it is left out.");
                continue;
            }
            weights.merge(known, entry.getValue().getDouble(0), Double::sum);
        }
        return weights;
    }

    private static @org.jspecify.annotations.Nullable String blockName(String written) {
        Material material = Material.matchMaterial(written);
        return material != null && material.isBlock() && !material.isAir() ? material.name() : null;
    }

    private static @org.jspecify.annotations.Nullable String creatureName(String written) {
        NamespacedKey key = NamespacedKey.fromString(written.toLowerCase(Locale.ROOT));
        EntityType type = key == null ? null : Registry.ENTITY_TYPE.get(key);
        return type != null && type.isSpawnable() && type.isAlive() ? type.name() : null;
    }
}
