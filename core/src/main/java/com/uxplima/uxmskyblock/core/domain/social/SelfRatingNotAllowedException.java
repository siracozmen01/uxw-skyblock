package com.uxplima.uxmskyblock.core.domain.social;

/**
 * Thrown when an island owner or member attempts to rate their own island or subject.
 */
public class SelfRatingNotAllowedException extends RuntimeException {

    public SelfRatingNotAllowedException(String message) {
        super(message);
    }
}
