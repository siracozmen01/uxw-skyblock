package com.uxplima.uxmskyblock.core.domain.permission;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, namespaced string identifier for a domain permission.
 * Format: "namespace:action" (e.g. "uxm:block.break", "uxm:container.chest.open").
 * Used exclusively for configuration, public API, and persistent storage.
 */
public record PermissionKey(String namespace, String value) implements Comparable<PermissionKey> {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z0-9_.-]+$");
    private static final Pattern VALUE_PATTERN = Pattern.compile("^[a-z0-9_.-]+$");

    public PermissionKey {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(value, "value must not be null");

        String normNs = namespace.trim().toLowerCase(Locale.ROOT);
        String normVal = value.trim().toLowerCase(Locale.ROOT);

        if (normNs.isEmpty() || !NAMESPACE_PATTERN.matcher(normNs).matches()) {
            throw new IllegalArgumentException("Invalid permission namespace: " + namespace);
        }
        if (normVal.isEmpty() || !VALUE_PATTERN.matcher(normVal).matches()) {
            throw new IllegalArgumentException("Invalid permission value: " + value);
        }

        namespace = normNs;
        value = normVal;
    }

    public static PermissionKey of(String qualifiedName) {
        Objects.requireNonNull(qualifiedName, "qualifiedName must not be null");
        int colon = qualifiedName.indexOf(':');
        if (colon <= 0 || colon == qualifiedName.length() - 1) {
            throw new IllegalArgumentException(
                    "Permission key must follow namespace:value format, got: " + qualifiedName);
        }
        String ns = qualifiedName.substring(0, colon);
        String val = qualifiedName.substring(colon + 1);
        return new PermissionKey(ns, val);
    }

    public static PermissionKey uxm(String value) {
        return new PermissionKey("uxm", value);
    }

    public String qualifiedName() {
        return namespace + ":" + value;
    }

    @Override
    public String toString() {
        return qualifiedName();
    }

    @Override
    public int compareTo(PermissionKey other) {
        int cmp = this.namespace.compareTo(other.namespace);
        return cmp != 0 ? cmp : this.value.compareTo(other.value);
    }
}
