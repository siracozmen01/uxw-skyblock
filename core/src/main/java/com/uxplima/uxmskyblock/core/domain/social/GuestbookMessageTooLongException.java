package com.uxplima.uxmskyblock.core.domain.social;

/**
 * Thrown when a guestbook message is longer than the operator allows.
 *
 * <p>It carries the limit, so the refusal a player reads can say how long a message may be.
 */
public class GuestbookMessageTooLongException extends IllegalArgumentException {

    private final int maxLength;

    public GuestbookMessageTooLongException(int length, int maxLength) {
        super("Guestbook message length (" + length + ") exceeds maximum limit (" + maxLength + ")");
        this.maxLength = maxLength;
    }

    public int maxLength() {
        return maxLength;
    }
}
