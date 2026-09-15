package com.uxplima.uxmskyblock.core.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileInventoryMutationOutcomeTest {

    @Test
    @DisplayName("success outcome holds valid incremented version")
    void successHoldsVersion() {
        ProfileInventoryMutationOutcome outcome = ProfileInventoryMutationOutcome.success(2L);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.isRejected()).isFalse();
        assertThat(outcome).isInstanceOf(ProfileInventoryMutationOutcome.Success.class);

        ProfileInventoryMutationOutcome.Success success = (ProfileInventoryMutationOutcome.Success) outcome;
        assertThat(success.newVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("success rejects non-positive version")
    void successRejectsNonPositiveVersion() {
        assertThatThrownBy(() -> new ProfileInventoryMutationOutcome.Success(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newVersion must be positive");
    }

    @Test
    @DisplayName("rejected outcome indicates failure")
    void rejectedOutcome() {
        ProfileInventoryMutationOutcome outcome = ProfileInventoryMutationOutcome.rejected();

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.isRejected()).isTrue();
        assertThat(outcome).isSameAs(ProfileInventoryMutationOutcome.Rejected.INSTANCE);
    }
}
