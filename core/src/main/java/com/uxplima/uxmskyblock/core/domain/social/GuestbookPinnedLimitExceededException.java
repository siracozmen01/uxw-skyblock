package com.uxplima.uxmskyblock.core.domain.social;

/**
 * Thrown when an island owner attempts to pin more guestbook entries than the maximum allowed limit.
 */
public class GuestbookPinnedLimitExceededException extends RuntimeException {

    public GuestbookPinnedLimitExceededException(String message) {
        super(message);
    }
}
