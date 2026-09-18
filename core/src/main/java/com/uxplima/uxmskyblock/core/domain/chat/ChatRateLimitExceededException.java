package com.uxplima.uxmskyblock.core.domain.chat;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

public final class ChatRateLimitExceededException extends RuntimeException {

    private final ProfileId profileId;

    public ChatRateLimitExceededException(ProfileId profileId) {
        super("Profile " + profileId + " exceeded chat message rate limit");
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
    }

    public ProfileId profileId() {
        return profileId;
    }
}
