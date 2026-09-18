package com.uxplima.uxmskyblock.core.domain.permission;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable mapping of role identifiers to precompiled {@link PermissionSet}s
 * for ultra-low latency hot-path authorization.
 */
public final class CompiledRolePolicy {

    private final Map<String, PermissionSet> rolePolicies;

    public CompiledRolePolicy(Map<String, PermissionSet> rolePolicies) {
        this.rolePolicies = Map.copyOf(rolePolicies);
    }

    public PermissionSet permissionsFor(String roleId) {
        Objects.requireNonNull(roleId, "roleId must not be null");
        return rolePolicies.getOrDefault(roleId, PermissionSet.empty());
    }

    public boolean allows(String roleId, PermissionId permissionId) {
        return permissionsFor(roleId).has(permissionId);
    }
}
