package com.uxplima.uxmskyblock.bukkit.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecyclePolicy;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleRule;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The lifecycle rules {@code modules/lifecycle.conf} writes.
 *
 * <p>A rule that cannot be read is left out with a warning naming it, and the rest still hold: one
 * mistyped event must not take every other rule with it.
 */
public record LifecycleConfiguration(LifecyclePolicy policy, int rules) {

    private static final Logger LOGGER = Logger.getLogger(LifecycleConfiguration.class.getName());

    public LifecycleConfiguration {
        Objects.requireNonNull(policy, "policy must not be null");
    }

    /** No rule: every event is the server's own. */
    public static LifecycleConfiguration defaultConfiguration() {
        return new LifecycleConfiguration(LifecyclePolicy.none(), 0);
    }

    public static LifecycleConfiguration load(ConfigurationNode root) {
        Objects.requireNonNull(root, "root must not be null");
        List<LifecycleRule> rules = new ArrayList<>();
        int index = 0;
        for (ConfigurationNode entry : root.node("rules").childrenList()) {
            index++;
            try {
                rules.add(rule(entry));
            } catch (IllegalArgumentException unreadable) {
                int number = index;
                LOGGER.warning(() -> "Lifecycle rule " + number + " in modules/lifecycle.conf is left out: "
                        + unreadable.getMessage());
            }
        }
        return new LifecycleConfiguration(new LifecyclePolicy(rules), rules.size());
    }

    private static LifecycleRule rule(ConfigurationNode entry) {
        LifecycleEvent event = named(LifecycleEvent.class, entry.node("event").getString(), "event");
        if (event == null) {
            throw new IllegalArgumentException("it names no event");
        }
        GameModeType mode = named(GameModeType.class, entry.node("mode").getString(), "mode");
        ProfileType ruleset = named(ProfileType.class, entry.node("ruleset").getString(), "ruleset");
        Map<LifecycleEffect, Boolean> effects = new EnumMap<>(LifecycleEffect.class);
        for (Map.Entry<Object, ? extends ConfigurationNode> field :
                entry.childrenMap().entrySet()) {
            String key = String.valueOf(field.getKey());
            if (key.equals("event") || key.equals("mode") || key.equals("ruleset")) {
                continue;
            }
            LifecycleEffect effect = LifecycleEffect.ofKey(key)
                    .orElseThrow(() -> new IllegalArgumentException("there is no effect called " + key));
            effects.put(effect, field.getValue().getBoolean());
        }
        return new LifecycleRule(event, mode, ruleset, effects);
    }

    private static <E extends Enum<E>> @Nullable E named(Class<E> type, @Nullable String written, String what) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("there is no " + what + " called " + written, unknown);
        }
    }
}
