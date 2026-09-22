package com.uxplima.uxmskyblock.bukkit.effect;

import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmlib.condition.action.ActionList;

/**
 * What every interaction fires, as the operator wrote it.
 *
 * <p>An interaction is a word in the file and a list of action lines under it. The lines are the
 * family's own grammar, {@code [verb] payload}, read by the library's action engine: one engine,
 * one vocabulary, and a line copied from any other plugin's file means here what it means there.
 *
 * <p>An interaction the file does not name fires nothing, and so does one written as an empty list.
 * The first is a server that never asked for it and the second is one that turned it off.
 */
public record InteractionEffects(Map<String, ActionList> byInteraction) {

    public InteractionEffects {
        Objects.requireNonNull(byInteraction, "byInteraction must not be null");
        byInteraction = Map.copyOf(byInteraction);
    }

    /** Nothing happens anywhere, which is what a node built without a file gets. */
    public static InteractionEffects none() {
        return new InteractionEffects(Map.of());
    }

    /** What this interaction fires, in order, or an empty list when the operator named none. */
    public ActionList of(String interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        return byInteraction.getOrDefault(interaction, ActionList.of(java.util.List.of()));
    }

    /** Whether this interaction fires anything at all. */
    public boolean names(String interaction) {
        Objects.requireNonNull(interaction, "interaction must not be null");
        return !of(interaction).actions().isEmpty();
    }
}
