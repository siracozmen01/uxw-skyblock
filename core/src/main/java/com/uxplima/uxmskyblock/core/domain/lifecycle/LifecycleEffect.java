package com.uxplima.uxmskyblock.core.domain.lifecycle;

import java.util.Locale;
import java.util.Optional;

/** One thing a lifecycle event may do to a player. */
public enum LifecycleEffect {
    CLEAR_INVENTORY,
    CLEAR_ENDER_CHEST,
    RESET_EXPERIENCE,
    SEND_TO_SPAWN,
    /** On a death only: the player keeps what they carried. */
    KEEP_INVENTORY,
    /** On a death only: the player keeps their experience. */
    KEEP_EXPERIENCE;

    /** Whether this effect means anything for {@code event}. Keeping belongs to a death alone. */
    public boolean appliesTo(LifecycleEvent event) {
        return switch (this) {
            case KEEP_INVENTORY, KEEP_EXPERIENCE -> event == LifecycleEvent.DEATH;
            default -> true;
        };
    }

    /** The key a rule writes for this effect, such as {@code clear-inventory}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The effect a rule's key names, if any. */
    public static Optional<LifecycleEffect> ofKey(String key) {
        for (LifecycleEffect effect : values()) {
            if (effect.key().equals(key.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(effect);
            }
        }
        return Optional.empty();
    }
}
