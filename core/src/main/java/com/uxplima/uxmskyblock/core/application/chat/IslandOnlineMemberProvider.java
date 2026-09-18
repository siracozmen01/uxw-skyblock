package com.uxplima.uxmskyblock.core.application.chat;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Provider interface to resolve currently online local profiles for an island.
 */
@FunctionalInterface
public interface IslandOnlineMemberProvider {

    /**
     * Returns the set of profile IDs for island members currently online on the local node.
     *
     * @param islandId target island
     * @return set of online member profile IDs
     */
    Set<ProfileId> getOnlineMembers(IslandId islandId);
}
