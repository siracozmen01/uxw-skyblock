package com.uxplima.uxmskyblock.api;

import java.util.Objects;

/**
 * Immutable value object representing a platform-neutral namespaced identifier.
 *
 * <p>Wraps a logical namespaced identifier string without platform dependencies.
 */
public final class NamespacedId {

    private final String value;

    private NamespacedId(String value) {
        this.value = value;
    }

    /**
     * Creates a namespaced identifier from a logical identifier string.
     *
     * @param namespacedString the logical namespaced identifier string
     * @return the namespaced identifier
     * @throws NullPointerException if {@code namespacedString} is null
     */
    public static NamespacedId of(String namespacedString) {
        Objects.requireNonNull(namespacedString, "namespacedString must not be null");
        return new NamespacedId(namespacedString);
    }

    /**
     * Returns the logical string representation of this identifier.
     *
     * @return the logical string representation
     */
    public String asString() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NamespacedId other)) {
            return false;
        }
        return this.value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
