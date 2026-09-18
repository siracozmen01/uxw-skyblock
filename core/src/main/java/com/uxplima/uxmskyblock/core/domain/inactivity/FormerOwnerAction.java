package com.uxplima.uxmskyblock.core.domain.inactivity;

/**
 * Action applied to a deposed island owner during an automated inactivity succession event.
 */
public enum FormerOwnerAction {
    DEMOTE_TO_CO_OWNER,
    DEMOTE_TO_MEMBER,
    KICK_FROM_ISLAND
}
