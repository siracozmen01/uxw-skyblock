package com.uxplima.uxmskyblock.core.domain.preset;

import java.util.List;
import java.util.Objects;

/**
 * How a new island is started in one dimension: the creation actions that build it, in order, and the
 * height of the block its players arrive standing on.
 */
public record StartTemplate(List<String> actions, int height) {

    public StartTemplate {
        Objects.requireNonNull(actions, "actions must not be null");
        actions = List.copyOf(actions);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("A start template builds nothing: it names no action");
        }
    }
}
