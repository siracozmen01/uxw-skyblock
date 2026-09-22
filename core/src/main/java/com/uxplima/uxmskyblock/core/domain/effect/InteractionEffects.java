package com.uxplima.uxmskyblock.core.domain.effect;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What every interaction fires, as the operator wrote it.
 *
 * <p>An interaction is named by a word, and against that word is a list of effects in the order
 * they happen. An interaction the file does not name fires nothing, which is a server that has
 * turned it off rather than a server that is broken.
 */
public record InteractionEffects(Map<String, List<InteractionEffect>> byInteraction) {

    public InteractionEffects {
        Objects.requireNonNull(byInteraction, "byInteraction must not be null");
        byInteraction = Map.copyOf(byInteraction);
    }

    /** Nothing happens anywhere. What a node with no file gets, which is a node with no effects. */
    public static InteractionEffects none() {
        return new InteractionEffects(Map.of());
    }

    /** What this interaction fires, in order, or nothing when the operator named none. */
    public List<InteractionEffect> of(String interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        return byInteraction.getOrDefault(interaction, List.of());
    }

    /** Whether this interaction fires anything at all. */
    public boolean names(String interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        return !of(interaction).isEmpty();
    }
}
