package com.uxplima.uxmskyblock.core.domain.vault;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * Thrown when a profile attempts a vault action without the required island permission.
 */
public final class VaultPermissionDeniedException extends RuntimeException {

    private final IslandId islandId;
    private final ProfileId profileId;
    private final IslandPermission requiredPermission;

    public VaultPermissionDeniedException(IslandId islandId, ProfileId profileId, IslandPermission requiredPermission) {
        super("Profile " + profileId + " does not have permission " + requiredPermission + " on island " + islandId);
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        this.requiredPermission = Objects.requireNonNull(requiredPermission, "requiredPermission must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }

    public ProfileId profileId() {
        return profileId;
    }

    public IslandPermission requiredPermission() {
        return requiredPermission;
    }
}
