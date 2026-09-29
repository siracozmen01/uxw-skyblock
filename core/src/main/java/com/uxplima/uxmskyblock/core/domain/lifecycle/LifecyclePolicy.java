package com.uxplima.uxmskyblock.core.domain.lifecycle;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;

/**
 * What each lifecycle event does to a player, composed from the operator's rules.
 *
 * <p>The rules that match an event, a mode and a ruleset are laid over each other from the least
 * specific to the most, and among rules as specific as each other in the order they were written. Each
 * one switches on or off only the effects it names. The same rules and the same three facts give the
 * same effects every time.
 */
public final class LifecyclePolicy {

    private final List<LifecycleRule> rules;

    public LifecyclePolicy(List<LifecycleRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        // A stable sort, so rules as specific as each other keep the order they were written in.
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(LifecycleRule::specificity))
                .toList();
    }

    /** A policy that does nothing on any event. */
    public static LifecyclePolicy none() {
        return new LifecyclePolicy(List.of());
    }

    /** The effects {@code event} has for a profile of {@code ruleset} whose island plays {@code mode}. */
    public Set<LifecycleEffect> effects(LifecycleEvent event, GameModeType mode, ProfileType ruleset) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(ruleset, "ruleset must not be null");
        EnumSet<LifecycleEffect> on = EnumSet.noneOf(LifecycleEffect.class);
        for (LifecycleRule rule : rules) {
            if (!rule.matches(event, mode, ruleset)) {
                continue;
            }
            for (Map.Entry<LifecycleEffect, Boolean> effect : rule.effects().entrySet()) {
                if (effect.getValue()) {
                    on.add(effect.getKey());
                } else {
                    on.remove(effect.getKey());
                }
            }
        }
        return Set.copyOf(on);
    }
}
