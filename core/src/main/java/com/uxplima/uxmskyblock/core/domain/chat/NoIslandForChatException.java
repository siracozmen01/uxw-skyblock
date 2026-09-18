package com.uxplima.uxmskyblock.core.domain.chat;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

public final class NoIslandForChatException extends RuntimeException {

    private final ProfileId profileId;

    public NoIslandForChatException(ProfileId profileId) {
        super("Profile " + profileId + " does not belong to an island");
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
    }

    public ProfileId profileId() {
        return profileId;
    }
}
