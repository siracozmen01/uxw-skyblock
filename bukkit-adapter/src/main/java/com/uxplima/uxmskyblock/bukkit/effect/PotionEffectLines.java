package com.uxplima.uxmskyblock.bukkit.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import org.jspecify.annotations.Nullable;

/** Potion effects as an operator writes them: a name, or {@code name:amplifier:seconds}. */
public final class PotionEffectLines {

    private static final Logger LOGGER = Logger.getLogger(PotionEffectLines.class.getName());

    private PotionEffectLines() {
        throw new UnsupportedOperationException("PotionEffectLines is a way of reading, not a thing to hold");
    }

    /** The effect of that name, or null when the server knows none. */
    public static @Nullable PotionEffectType named(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.MOB_EFFECT.get(key);
    }

    /** An effect written {@code name:amplifier:seconds}, or null when it cannot be read. */
    public static @Nullable PotionEffect written(String written) {
        String[] parts = written.trim().split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        PotionEffectType type = named(parts[0]);
        if (type == null) {
            return null;
        }
        try {
            int amplifier = Integer.parseInt(parts[1].trim());
            int seconds = Integer.parseInt(parts[2].trim());
            if (amplifier < 0 || seconds < 1) {
                return null;
            }
            return new PotionEffect(type, seconds * 20, amplifier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every line that reads as an effect. Each one that does not is logged against {@code where}. */
    public static List<PotionEffect> allWritten(String where, List<String> lines) {
        List<PotionEffect> read = new ArrayList<>();
        for (String line : lines) {
            PotionEffect effect = written(line);
            if (effect == null) {
                LOGGER.warning(
                        () -> where + ": " + line + " is not name:amplifier:seconds with an effect of that name.");
            } else {
                read.add(effect);
            }
        }
        return read;
    }
}
