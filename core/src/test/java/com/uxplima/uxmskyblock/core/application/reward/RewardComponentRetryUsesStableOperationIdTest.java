package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 20 (GAMEMODE_ARCHITECTURE.md Section 11.3 & 12):
 * Asserts that retrying a failed or uncommitted reward component delivery uses the identical
 * deterministic RewardComponentOperationId (grantId + componentIndex), ensuring true idempotency
 * without generating duplicate operations.
 */
class RewardComponentRetryUsesStableOperationIdTest {

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    @DisplayName("20. Retrying failed component delivery produces identical deterministic operation ID across attempts")
    void retryUsesIdenticalDeterministicOperationId() {
        RewardGrantId grantId = RewardGrantId.random();
        int componentIndex = 0;

        // First attempt derivation
        RewardComponentOperationId attempt1OpId = RewardComponentOperationId.derive(grantId, componentIndex);

        Instant t1 = Instant.now();
        RewardGrantComponent compInitial = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                componentIndex,
                attempt1OpId,
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{}",
                RewardComponentState.PENDING,
                null,
                t1);

        RewardGrant grant = new RewardGrant(
                grantId,
                recipientProfile,
                "ADMIN_PAYOUT",
                "manual-1",
                RewardGrantState.PENDING,
                List.of(compInitial),
                null,
                null,
                t1,
                t1);

        // Component delivery fails
        Instant t2 = t1.plusSeconds(5);
        RewardGrantComponent compFailed = compInitial.withFailed(t2);
        RewardGrant grantFailed = grant.withComponent(compFailed, t2).withState(RewardGrantState.RECOVERY_REQUIRED, t2);

        assertThat(compFailed.state()).isEqualTo(RewardComponentState.FAILED);
        assertThat(compFailed.componentOperationId()).isEqualTo(attempt1OpId);

        // Second attempt / retry derivation
        RewardComponentOperationId retryOpId = RewardComponentOperationId.derive(grantId, componentIndex);

        // Invariant: stable operation identity across retries
        assertThat(retryOpId).isEqualTo(attempt1OpId);

        // Retry succeeds
        Instant t3 = t2.plusSeconds(10);
        RewardGrantComponent compCommitted = compFailed.withCommitted(retryOpId.value(), t3);
        RewardGrant grantCommitted =
                grantFailed.withComponent(compCommitted, t3).withClaimed(t3, t3);

        assertThat(compCommitted.state()).isEqualTo(RewardComponentState.COMMITTED);
        assertThat(compCommitted.componentOperationId()).isEqualTo(attempt1OpId);
        assertThat(compCommitted.optJournalOperationId()).contains(attempt1OpId.value());
        assertThat(grantCommitted.state()).isEqualTo(RewardGrantState.CLAIMED);
    }
}
