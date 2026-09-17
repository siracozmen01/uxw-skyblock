package com.uxplima.uxmskyblock.core.domain.island;

/**
 * Pure domain outcome representing the atomic result of an island authority transition.
 */
public sealed interface IslandAuthorityOutcome {

    static IslandAuthorityOutcome success(long epoch) {
        return new Success(epoch);
    }

    static IslandAuthorityOutcome rejected() {
        return Rejected.INSTANCE;
    }

    boolean isSuccess();

    default boolean isRejected() {
        return !isSuccess();
    }

    /**
     * Successful authority transition outcome carrying the canonical authority epoch.
     *
     * @param epoch the authority epoch guaranteed by the database transaction
     */
    record Success(long epoch) implements IslandAuthorityOutcome {

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
    enum Rejected implements IslandAuthorityOutcome {
        INSTANCE;

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
