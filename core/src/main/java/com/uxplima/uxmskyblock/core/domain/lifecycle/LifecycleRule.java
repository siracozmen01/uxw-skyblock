package com.uxplima.uxmskyblock.core.domain.lifecycle;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.jspecify.annotations.Nullable;

/**
 * One rule the operator wrote: for an event, on an island of a mode, for a profile of a ruleset, these
 * effects are switched on or off. A rule that names no mode holds for every mode, and one that names no
 * ruleset for every ruleset. An effect the rule does not mention is left to the rules beneath it.
 */
public record LifecycleRule(
        LifecycleEvent event,
        @Nullable GameModeType mode,
        @Nullable ProfileType ruleset,
        Map<LifecycleEffect, Boolean> effects) {

    public LifecycleRule {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(effects, "effects must not be null");
        for (LifecycleEffect effect : effects.keySet()) {
            if (!effect.appliesTo(event)) {
                throw new IllegalArgumentException(effect.key() + " means nothing on " + event);
            }
        }
        effects = effects.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(effects));
    }

    /** Whether this rule speaks for an event on an island of this mode for a profile of this ruleset. */
    boolean matches(LifecycleEvent at, GameModeType onMode, ProfileType ofRuleset) {
        return event == at && (mode == null || mode == onMode) && (ruleset == null || ruleset == ofRuleset);
    }

    /**
     * How specific the rule is, which decides the order rules are laid over each other: a rule for
     * every island and profile first, then one for a mode, then one for a ruleset, then one for both.
     * A ruleset beats a mode because it is the player's own restriction: a Hardcore profile keeps its
     * rules on whatever island it plays.
     */
    int specificity() {
        return (mode != null ? 1 : 0) + (ruleset != null ? 2 : 0);
    }
}
