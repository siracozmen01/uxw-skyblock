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
    ISLAND,

    /**
     * Chat routed to this island's members and to every island allied with it.
     *
     * <p>The alliance service has carried an {@code alliance-chat} switch since it was written and
     * nothing asked it, because there was no channel to switch on.
     */
    ALLIANCE
}
