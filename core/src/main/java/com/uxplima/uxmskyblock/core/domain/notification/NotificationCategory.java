package com.uxplima.uxmskyblock.core.domain.notification;

/**
 * Categories of durable offline player notifications (Section 2.18 & 2.42).
 */
public enum NotificationCategory {
    INVITE,
    KICK,
    ROLE_CHANGED,
    TRUST_GRANTED,
    TRUST_REVOKED,
    REWARD_AVAILABLE,
    BANK_ACTIVITY,
    MODERATION,
    RESET,
    SYSTEM
}
