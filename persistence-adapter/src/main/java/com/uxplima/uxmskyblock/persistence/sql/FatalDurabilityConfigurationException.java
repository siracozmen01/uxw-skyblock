package com.uxplima.uxmskyblock.persistence.sql;

/**
 * Thrown at startup when the database would not keep a commit through a power loss and the operator
 * asked for {@link DurabilityCheck.Profile#PRODUCTION_STRICT}.
 */
public final class FatalDurabilityConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FatalDurabilityConfigurationException(String message) {
        super(message);
    }
}
