package com.uxplima.uxmskyblock.core.domain.inventory;

/**
 * Pure domain outcome representing the atomic result of an authoritative inventory mutation.
 */
public sealed interface ProfileInventoryMutationOutcome {

    static ProfileInventoryMutationOutcome success(long newVersion) {
        return new Success(newVersion);
    }

    static ProfileInventoryMutationOutcome rejected() {
        return Rejected.INSTANCE;
    }

    boolean isSuccess();

    default boolean isRejected() {
        return !isSuccess();
    }

    /**
     * Successful mutation outcome carrying the updated durable version (expectedVersion + 1).
     *
     * @param newVersion the durable OCC version confirmed by the transaction
     */
    record Success(long newVersion) implements ProfileInventoryMutationOutcome {

        public Success {
            if (newVersion < 1) {
                throw new IllegalArgumentException("newVersion must be positive: " + newVersion);
            }
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    /**
     * Rejection outcome indicating authority failure, stale lease, invalid session state,
     * or OCC version mismatch (0 rows affected).
     */
    enum Rejected implements ProfileInventoryMutationOutcome {
        INSTANCE;

        @Override
        public boolean isSuccess() {
            return false;
        }
    }
}
