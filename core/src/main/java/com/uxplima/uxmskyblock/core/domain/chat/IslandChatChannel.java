package com.uxplima.uxmskyblock.core.domain.chat;

/**
 * Represents the active chat dispatch channel for a player.
 */
public enum IslandChatChannel {
    /**
     * Standard server public or global chat.
     */
    GLOBAL,

    /**
     * Private island team chat routed exclusively to fellow island members.
     */
    ISLAND
}
