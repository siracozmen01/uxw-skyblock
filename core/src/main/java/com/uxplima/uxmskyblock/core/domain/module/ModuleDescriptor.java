package com.uxplima.uxmskyblock.core.domain.module;

import java.util.List;
import java.util.Objects;

/**
 * Declarative specification of an internal feature module's identity, version,
 * dependencies, capabilities, and fail-fast criticality tier.
 */
public record ModuleDescriptor(
        String id,
        String version,
        List<String> requires,
        List<String> optional,
        List<String> provides,
        String apiCompatibility,
        boolean coreRequired) {

    public ModuleDescriptor {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(version, "version cannot be null");
        requires = requires == null ? List.of() : List.copyOf(requires);
        optional = optional == null ? List.of() : List.copyOf(optional);
        provides = provides == null ? List.of() : List.copyOf(provides);
        apiCompatibility = (apiCompatibility == null || apiCompatibility.isBlank()) ? "*" : apiCompatibility;
        if (id.isBlank()) {
            throw new IllegalArgumentException("Module id cannot be blank");
        }
    }

    public SemVer semVer() {
        return SemVer.parse(version);
    }
}
