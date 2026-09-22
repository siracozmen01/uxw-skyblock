package com.uxplima.uxmskyblock.core.domain.name;

import java.util.Objects;

/**
 * A name that cannot be an island's name, and why.
 *
 * <p>The message is English for the log. The {@link Reason} is what a player is told, out of the
 * catalogue in their own language: the message used to reach them as it stood, with the name and a
 * regular expression's worth of rules in it.
 */
public final class IslandNameRefusedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Why a name was refused. */
    public enum Reason {
        /** Shorter or longer than a name may be. */
        LENGTH,
        /** Holds a character a name may not. */
        CHARACTERS,
        /** A word the server keeps for itself. */
        RESERVED,
        /** Holds a word the language filter refuses. */
        UNSAFE
    }

    private final Reason reason;

    public IslandNameRefusedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
