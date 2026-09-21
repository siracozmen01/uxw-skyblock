package com.uxplima.uxmskyblock.core.application.chat;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Which of these profiles are playing on this node right now.
 *
 * <p>It used to be asked for an island and answer by reading that island out of the database and
 * then walking every player on the server. That is a query and a full scan for every line of chat,
 * on an island the caller had already read, and the scan touched the live player list from whatever
 * thread the message arrived on.
 *
 * <p>The caller holds the island, so it asks about the profiles it already has.
 */
@FunctionalInterface
public interface IslandOnlineMemberProvider {

    /**
     * The profiles among {@code candidates} that are online on this node.
     *
     * @param candidates the profiles to ask about, usually one island's members
     * @return those of them that are here
     */
    Set<ProfileId> onlineAmong(Set<ProfileId> candidates);
}
