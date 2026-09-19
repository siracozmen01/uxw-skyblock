package com.uxplima.uxmskyblock.core.domain.activity;

/**
 * Event types recorded in the user-facing activity feed (Section 2.19 & 2.42).
 */
public enum ActivityEventType {
    MEMBER_JOINED,
    MEMBER_LEFT,
    ROLE_CHANGED,
    TRUST_GRANTED,
    TRUST_REVOKED,
    BANK_DEPOSIT,
    BANK_WITHDRAW,
    UPGRADE_PURCHASED,
    BOOSTER_ACTIVATED,
    WARP_CREATED,
    TEMPLATE_APPLIED,
    MISSION_COMPLETED
}
