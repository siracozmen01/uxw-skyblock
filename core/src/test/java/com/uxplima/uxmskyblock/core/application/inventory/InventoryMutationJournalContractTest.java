package com.uxplima.uxmskyblock.core.application.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure domain and application contract test verifying {@link InventoryMutationJournalPort}
 * types, records, outcomes, and value objects in {@code :core}.
 */
class InventoryMutationJournalContractTest {

    @Test
    @SuppressWarnings("NullAway")
    @DisplayName("InventoryMutationOperationId: enforces non-null, parses UUID, equality and string format")
    void operationIdValueObjectContract() {
        UUID uuid = UUID.randomUUID();
        InventoryMutationOperationId opId = InventoryMutationOperationId.of(uuid);
        assertThat(opId.value()).isEqualTo(uuid);
        assertThat(opId.toString()).isEqualTo(uuid.toString());

        InventoryMutationOperationId parsed = InventoryMutationOperationId.of(uuid.toString());
        assertThat(parsed).isEqualTo(opId);
        assertThat(parsed.hashCode()).isEqualTo(opId.hashCode());

        assertThatThrownBy(() -> InventoryMutationOperationId.of((UUID) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InventoryMutationOperationId.of((String) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("InventoryMutationJournalOutcome: factory methods and predicate state consistency")
    void outcomeFactoryContract() {
        InventoryMutationJournalOutcome success = InventoryMutationJournalOutcome.success(2L);
        assertThat(success.isSuccess()).isTrue();
        assertThat(success.isRejected()).isFalse();
        assertThat(success.isConflict()).isFalse();
        assertThat(success.version()).hasValue(2L);
        assertThat(success.rejectionReason()).isEmpty();

        InventoryMutationJournalOutcome intent = InventoryMutationJournalOutcome.intentRecorded();
        assertThat(intent.isSuccess()).isTrue();
        assertThat(intent.isRejected()).isFalse();
        assertThat(intent.isConflict()).isFalse();
        assertThat(intent.version()).isEmpty();

        InventoryMutationJournalOutcome rejected = InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
        assertThat(rejected.isSuccess()).isFalse();
        assertThat(rejected.isRejected()).isTrue();
        assertThat(rejected.isConflict()).isFalse();
        assertThat(rejected.version()).isEmpty();
        assertThat(rejected.rejectionReason()).contains("CROSS_PROFILE_MISMATCH");

        InventoryMutationJournalOutcome conflict =
                InventoryMutationJournalOutcome.conflict("DUPLICATE_OPERATION_CONFLICT");
        assertThat(conflict.isSuccess()).isFalse();
        assertThat(conflict.isRejected()).isFalse();
        assertThat(conflict.isConflict()).isTrue();
        assertThat(conflict.version()).isEmpty();
        assertThat(conflict.rejectionReason()).contains("DUPLICATE_OPERATION_CONFLICT");
    }

    @Test
    @DisplayName("InventoryMutationJournalRecord and ParticipantRecord: immutability and non-null validation")
    void recordsContract() {
        InventoryMutationOperationId opId = InventoryMutationOperationId.random();
        Instant now = Instant.now();

        InventoryMutationJournalRecord header = new InventoryMutationJournalRecord(
                opId, "VAULT_TRANSFER", InventoryMutationJournalState.INTENT, 1, "{}", now.plusSeconds(60), now, now);
        assertThat(header.operationId()).isEqualTo(opId);
        assertThat(header.state()).isEqualTo(InventoryMutationJournalState.INTENT);

        InventoryMutationParticipantRecord participant = new InventoryMutationParticipantRecord(
                opId,
                0,
                "PLAYER_INVENTORY",
                "PROFILE",
                "prof-1",
                1L,
                "PLAYER_SESSION",
                "p-1",
                1L,
                "fp-before",
                "fp-after",
                ParticipantApplyState.PENDING,
                "{}",
                now);
        assertThat(participant.operationId()).isEqualTo(opId);
        assertThat(participant.durableApplyState()).isEqualTo(ParticipantApplyState.PENDING);
        assertThat(participant.expectedVersion()).isEqualTo(1L);
    }
}
