package com.uxplima.uxmskyblock.core.domain.session;

/**
 * Pure domain outcome representing the atomic result of a player session authority transition.
 */
public sealed interface SessionAuthorityOutcome {

    static SessionAuthorityOutcome success(long epoch) {
        return new Success(epoch, false);
    }

    static SessionAuthorityOutcome success(long epoch, boolean recovering) {
        return new Success(epoch, recovering);
    }

    static SessionAuthorityOutcome rejected() {
        return Rejected.INSTANCE;
    }

    boolean isSuccess();

    default boolean isRejected() {
        return !isSuccess();
    }

    default boolean isRecovering() {
        return false;
    }

    /**
     * Successful authority transition outcome carrying the canonical session epoch.
     *
     * @param epoch the session epoch guaranteed by the database transaction
     * @param recovering whether the session was acquired into RECOVERING state
     */
    record Success(long epoch, boolean recovering) implements SessionAuthorityOutcome {

        public Success(long epoch) {
            this(epoch, false);
        }

        public Success {
            if (epoch < 1) {
                throw new IllegalArgumentException("epoch must be positive: " + epoch);
            }
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isRecovering() {
            return recovering;
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
