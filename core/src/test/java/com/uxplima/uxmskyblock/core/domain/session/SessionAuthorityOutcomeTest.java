package com.uxplima.uxmskyblock.core.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionAuthorityOutcomeTest {

    @Test
    @DisplayName("Success outcome carries positive session epoch")
    void successOutcomeCarriesEpoch() {
        SessionAuthorityOutcome outcome = SessionAuthorityOutcome.success(42L);
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.isRejected()).isFalse();

        assertThat(outcome).isInstanceOf(SessionAuthorityOutcome.Success.class);
        SessionAuthorityOutcome.Success success = (SessionAuthorityOutcome.Success) outcome;
        assertThat(success.epoch()).isEqualTo(42L);
    }

    @Test
    @DisplayName("Success outcome rejects non-positive epoch")
    void successRejectsNonPositiveEpoch() {
        assertThatThrownBy(() -> new SessionAuthorityOutcome.Success(0L)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SessionAuthorityOutcome.Success(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejected outcome indicates unsuccessful atomic transition")
    void rejectedOutcomeIndicatesUnsuccessful() {
        SessionAuthorityOutcome outcome = SessionAuthorityOutcome.rejected();
        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.isRejected()).isTrue();
        assertThat(outcome).isInstanceOf(SessionAuthorityOutcome.Rejected.class);
    }
}
