package com.uxplima.uxmskyblock.core.domain.bank;

import java.util.Objects;
import java.util.UUID;

/**
 * Sealed algebraic data type representing the exhaustive outcome of an island bank transaction.
 */
public sealed interface BankTransactionOutcome {

    /** Transaction completed successfully, updating balance and appending audit trail. */
    record Success(IslandBank updatedBank, BankTransaction transaction) implements BankTransactionOutcome {
        public Success {
            Objects.requireNonNull(updatedBank, "updatedBank");
            Objects.requireNonNull(transaction, "transaction");
        }
    }

    /** Transaction rejected due to insufficient funds (overdraft prevented). */
    record InsufficientFunds(long currentBalance, long requestedDelta) implements BankTransactionOutcome {}

    /** Transaction rejected due to optimistic concurrency version mismatch. */
    record StaleVersion(long expectedVersion, long actualVersion) implements BankTransactionOutcome {}

    /** Transaction rejected because island authority lease is invalid, expired, or held by another node. */
    record AuthorityRejected(String reason) implements BankTransactionOutcome {
        public AuthorityRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Transaction was already applied previously with the same operation ID / idempotency key. */
    record DuplicateOperation(UUID operationId, String message) implements BankTransactionOutcome {
        public DuplicateOperation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(message, "message");
        }
    }

    /** Island bank account was not found. */
    record BankNotFound(String message) implements BankTransactionOutcome {
        public BankNotFound {
            Objects.requireNonNull(message, "message");
        }
    }
}
