package com.uxplima.uxmskyblock.core.domain.chat;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

public final class IslandChatPermissionDeniedException extends RuntimeException {

    private final ProfileId profileId;
    private final IslandId islandId;
    private final IslandPermission permission;

    public IslandChatPermissionDeniedException(ProfileId profileId, IslandId islandId, IslandPermission permission) {
        super("Profile " + profileId + " denied permission " + permission + " in island " + islandId);
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.permission = Objects.requireNonNull(permission, "permission must not be null");
    }

    public ProfileId profileId() {
        return profileId;
    }

    public IslandId islandId() {
        return islandId;
    }

    public IslandPermission permission() {
        return permission;
    }
}
