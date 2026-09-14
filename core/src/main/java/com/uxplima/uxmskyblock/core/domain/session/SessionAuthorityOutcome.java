package com.uxplima.uxmskyblock.core.domain.session;

/**
 * Pure domain outcome representing the atomic result of a player session authority transition.
 */
public sealed interface SessionAuthorityOutcome {

    static SessionAuthorityOutcome success(long epoch) {
        return new Success(epoch);
    }

    static SessionAuthorityOutcome rejected() {
        return Rejected.INSTANCE;
    }

    boolean isSuccess();

    default boolean isRejected() {
        return !isSuccess();
    }

    /**
     * Successful authority transition outcome carrying the canonical session epoch.
     *
     * @param epoch the session epoch guaranteed by the database transaction
     */
    record Success(long epoch) implements SessionAuthorityOutcome {

        public Success {
            if (epoch < 1) {
                throw new IllegalArgumentException("epoch must be positive: " + epoch);
            }
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Rejection outcome indicating the atomic fencing predicate was not satisfied (0 rows affected).
     */
    enum Rejected implements SessionAuthorityOutcome {
        INSTANCE;

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
