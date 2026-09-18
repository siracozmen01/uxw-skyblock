package com.uxplima.uxmskyblock.core.domain.module;

import java.util.Objects;

/**
 * Declarative dependency requirement specifying target module ID and acceptable SemVer range.
 */
public record DependencyRequirement(String moduleId, String rangeExpression) {

    public DependencyRequirement {
        Objects.requireNonNull(moduleId, "moduleId cannot be null");
        Objects.requireNonNull(rangeExpression, "rangeExpression cannot be null");
        if (moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId cannot be blank");
        }
    }

    public boolean isSatisfiedBy(SemVer version) {
        Objects.requireNonNull(version, "version cannot be null");
        return version.satisfies(rangeExpression);
    }
}
