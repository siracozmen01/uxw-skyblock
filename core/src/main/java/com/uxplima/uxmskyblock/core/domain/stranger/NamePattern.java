package com.uxplima.uxmskyblock.core.domain.stranger;

import java.util.Locale;
import java.util.Objects;

/**
 * A block or creature name as an operator writes it to match: a whole name, or a name with one
 * {@code *} in it, such as {@code *_LEAVES}, which matches every name with that start and end.
 */
public record NamePattern(String written) {

    public NamePattern {
        Objects.requireNonNull(written, "written must not be null");
        written = written.trim().toUpperCase(Locale.ROOT);
        if (written.isEmpty() || written.indexOf('*') != written.lastIndexOf('*')) {
            throw new IllegalArgumentException("a pattern is a name with at most one *");
        }
    }

    /** Whether the upper case name matches. */
    public boolean matches(String name) {
        int star = written.indexOf('*');
        if (star < 0) {
            return written.equals(name);
        }
        String start = written.substring(0, star);
        String end = written.substring(star + 1);
        return name.length() >= start.length() + end.length() && name.startsWith(start) && name.endsWith(end);
    }
}
