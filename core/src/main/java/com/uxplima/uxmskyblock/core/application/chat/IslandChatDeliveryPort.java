package com.uxplima.uxmskyblock.core.application.chat;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Platform delivery port responsible for dispatching rendered messages to local players.
 */
public interface IslandChatDeliveryPort {

    /**
     * Delivers an island chat frame to local recipient members.
     *
     * @param recipients profile IDs of local online members who should receive the message
     * @param frame the message frame
     */
    void deliverToMembers(Set<ProfileId> recipients, IslandChatFrame frame);

    /**
     * Delivers an island chat frame to staff spies who are listening.
     *
     * @param spies profile IDs of staff members with spy enabled
     * @param frame the message frame
     * @param islandName custom or default name of the island
     */
    void deliverToSpies(Set<ProfileId> spies, IslandChatFrame frame, String islandName);
}
