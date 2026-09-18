package com.uxplima.uxmskyblock.core.domain.access;

/**
 * Thrown when an access grant attempts to circumvent immutable ruleset boundaries
 * (such as external trade or bank transfers on Ironman profiles).
 */
public class RulesetAccessViolationException extends RuntimeException {

    public RulesetAccessViolationException(String message) {
        super(message);
    }
}
