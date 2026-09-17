package com.uxplima.uxmskyblock.core.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxEventRecordTest {

    @Test
    @DisplayName("rejects null required fields in constructor")
    @SuppressWarnings("NullAway")
    void rejectsNullRequiredFields() {
        EventId id = EventId.random();
        Instant now = Instant.now();

        assertThatThrownBy(() -> new OutboxEventRecord(
                        null, "TYPE", "AGG-1", "{}", OutboxStatus.PENDING, null, null, null, 0, null, null, now, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OutboxEventRecord(
                        id, null, "AGG-1", "{}", OutboxStatus.PENDING, null, null, null, 0, null, null, now, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OutboxEventRecord(
                        id, "TYPE", null, "{}", OutboxStatus.PENDING, null, null, null, 0, null, null, now, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OutboxEventRecord(
                        id, "TYPE", "AGG-1", null, OutboxStatus.PENDING, null, null, null, 0, null, null, now, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OutboxEventRecord(
                        id, "TYPE", "AGG-1", "{}", null, null, null, null, 0, null, null, now, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new OutboxEventRecord(
                        id, "TYPE", "AGG-1", "{}", OutboxStatus.PENDING, null, null, null, 0, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("OutboxStatus isTerminal logic")
    void outboxStatusTerminal() {
        assertThat(OutboxStatus.PENDING.isTerminal()).isFalse();
        assertThat(OutboxStatus.CLAIMED.isTerminal()).isFalse();
        assertThat(OutboxStatus.PROCESSED.isTerminal()).isTrue();
        assertThat(OutboxStatus.DEAD_LETTER.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("OutboxClaim constructs immutable claim records")
    void outboxClaimConstruction() {
        Instant expires = Instant.now().plusSeconds(30);
        OutboxClaim empty = OutboxClaim.empty("w-1", "tok-1", expires);
        assertThat(empty.workerId()).isEqualTo("w-1");
        assertThat(empty.claimToken()).isEqualTo("tok-1");
        assertThat(empty.claimExpiresAt()).isEqualTo(expires);
        assertThat(empty.claimedEvents()).isEmpty();

        OutboxEventRecord rec = new OutboxEventRecord(
                EventId.random(),
                "BANK_DEPOSIT",
                "isl-1",
                "{\"amount\":100}",
                OutboxStatus.CLAIMED,
                "w-1",
                "tok-1",
                expires,
                1,
                null,
                null,
                Instant.now(),
                null);

        OutboxClaim withEvents = new OutboxClaim("w-1", "tok-1", expires, List.of(rec));
        assertThat(withEvents.claimedEvents()).hasSize(1);
    }
}
