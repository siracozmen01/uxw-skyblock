package com.uxplima.uxmskyblock.bukkit.chat;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.chat.IslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Which profiles are playing on this node, kept as they arrive and leave.
 *
 * <p>This used to answer by reading the island out of the database and then walking every player on
 * the server. That is a query and a full scan for every line of chat, on an island the caller had
 * already read, and the scan touched the live player list from whatever thread the message arrived
 * on: the scheduler pool locally, a Redis subscriber thread across a cluster. Neither owns that
 * list, and on Folia nothing off the owning thread does.
 *
 * <p>A set kept by the join and the quit answers the same question without asking the server
 * anything, and the answer is an intersection rather than a walk.
 */
public final class BukkitIslandOnlineMemberProvider implements IslandOnlineMemberProvider {

    private final Set<ProfileId> present = ConcurrentHashMap.newKeySet();

    /** Records that this profile is playing here. Called from the join, on the owning thread. */
    public void arrived(ProfileId profileId) {
        present.add(Objects.requireNonNull(profileId, "profileId must not be null"));
    }

    /** Records that this profile is no longer playing here. Called from the quit. */
    public void left(ProfileId profileId) {
        present.remove(Objects.requireNonNull(profileId, "profileId must not be null"));
    }

    /** How many profiles are here, for a test to assert on. */
    public int count() {
        return present.size();
    }

    @Override
    public Set<ProfileId> onlineAmong(Set<ProfileId> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        Set<ProfileId> here = new HashSet<>();
        for (ProfileId candidate : candidates) {
            if (present.contains(candidate)) {
                here.add(candidate);
            }
        }
        return here;
    }
}
