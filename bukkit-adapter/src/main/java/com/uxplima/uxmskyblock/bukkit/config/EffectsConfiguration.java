package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffect;
import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffects;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Reads what every interaction fires off the operator's file.
 *
 * <p>The sounds and the particles were two lines of Java each, so a server that wanted a different
 * note, or no note, or a title and a bar as well, had nowhere to say so. Each interaction is a name
 * in the file and a list of effect lines under it.
 */
public final class EffectsConfiguration {

    private EffectsConfiguration() {
        throw new UnsupportedOperationException("EffectsConfiguration is a way of reading a file, not a thing to hold");
    }

    /**
     * What this plugin ships with, which is what it used to do with the effects written into it.
     *
     * <p>A node whose file is missing or empty behaves as it always did rather than falling silent.
     */
    public static InteractionEffects defaultConfiguration() {
        Map<String, List<InteractionEffect>> defaults = new LinkedHashMap<>();
        defaults.put(
                "kinetic-ward",
                parseAll(List.of("sound:ENTITY_PLAYER_ATTACK_SWEEP 1.0 1.2", "particle:CRIT 20 0.8 0.2 0.8 0.1")));
        defaults.put(
                "obsidian-recovery",
                parseAll(List.of(
                        "sound:ITEM_BUCKET_FILL_LAVA 1.0 1.0", "particle:CAMPFIRE_COSY_SMOKE 6 0.2 0.2 0.2 0.02")));
        defaults.put("limit-refused", parseAll(List.of("sound:BLOCK_NOTE_BLOCK_BASS 1.0 0.5")));
        defaults.put("mission-completed", parseAll(List.of("sound:UI_TOAST_CHALLENGE_COMPLETE 1.0 1.0")));
        return new InteractionEffects(defaults);
    }

    public static InteractionEffects load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");
        ConfigurationNode node = rootNode.node("effects");
        if (node.virtual() || !node.isMap()) {
            return defaultConfiguration();
        }

        Map<String, List<InteractionEffect>> byInteraction = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry :
                node.childrenMap().entrySet()) {
            String interaction = String.valueOf(entry.getKey());
            List<String> written = new ArrayList<>();
            for (ConfigurationNode line : entry.getValue().childrenList()) {
                String text = line.getString();
                if (text != null && !text.isBlank()) {
                    written.add(text);
                }
            }
            // An interaction written as an empty list is an interaction the operator switched off,
            // which is not the same as one they never mentioned, and both fire nothing.
            byInteraction.put(interaction, parseAll(written));
        }
        return new InteractionEffects(byInteraction);
    }

    private static List<InteractionEffect> parseAll(List<String> lines) {
        List<InteractionEffect> effects = new ArrayList<>();
        for (String line : lines) {
            InteractionEffect.parse(line).ifPresent(effects::add);
        }
        return List.copyOf(effects);
    }
}
