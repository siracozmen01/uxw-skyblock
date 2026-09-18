package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Architectural Contract Test 19 (GAMEMODE_ARCHITECTURE.md Section 11.3 & 12):
 * Asserts that different components within the same RewardGrant receive distinct
 * RewardComponentOperationIds, preventing operation ID collisions in InventoryMutationJournal
 * and processed_operations.
 */
class RewardComponentOperationIdentityIsolationTest {

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    @DisplayName("19. Different components within the same grant receive strictly distinct operation IDs")
    void differentComponentsReceiveDistinctOperationIds() {
        RewardGrantId grantId = RewardGrantId.random();

        RewardComponentOperationId op0 = RewardComponentOperationId.derive(grantId, 0);
        RewardComponentOperationId op1 = RewardComponentOperationId.derive(grantId, 1);
        RewardComponentOperationId op2 = RewardComponentOperationId.derive(grantId, 2);

        assertThat(op0).isNotEqualTo(op1);
        assertThat(op1).isNotEqualTo(op2);
        assertThat(op0).isNotEqualTo(op2);

        Instant now = Instant.now();
        RewardGrantComponent comp0 = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                op0,
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{}",
                RewardComponentState.PENDING,
                null,
                now);

        RewardGrantComponent comp1 = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                1,
                op1,
                RewardComponentType.SQL_CURRENCY,
                "uxm:currency_deposit",
                1,
                "{\"amount\":1000}",
                RewardComponentState.PENDING,
                null,
                now);

        RewardGrant grant = new RewardGrant(
                grantId,
                recipientProfile,
                "SEASON_PAYOUT",
                "season-1-rank-1",
                RewardGrantState.PENDING,
                List.of(comp0, comp1),
                null,
                null,
                now,
                now);

        assertThat(grant.components().get(0).componentOperationId())
                .isNotEqualTo(grant.components().get(1).componentOperationId());
    }

    @Test
    @DisplayName("19. Identical components across different grants receive distinct operation IDs")
    void identicalComponentsAcrossDifferentGrantsReceiveDistinctOperationIds() {
        RewardGrantId grantA = RewardGrantId.random();
        RewardGrantId grantB = RewardGrantId.random();

        RewardComponentOperationId opA0 = RewardComponentOperationId.derive(grantA, 0);
        RewardComponentOperationId opB0 = RewardComponentOperationId.derive(grantB, 0);

        assertThat(opA0).isNotEqualTo(opB0);
    }

    @Test
    @DisplayName("19. Component with mismatched non-derived operation ID is rejected by aggregate boundary")
    void nonDerivedOperationIdIsRejectedByAggregateBoundary() {
        RewardGrantId grantId = RewardGrantId.random();
        RewardComponentOperationId arbitraryOp = RewardComponentOperationId.of(UUID.randomUUID());
        Instant now = Instant.now();

        RewardGrantComponent comp = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                arbitraryOp,
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{}",
                RewardComponentState.PENDING,
                null,
                now);

        assertThatThrownBy(() -> new RewardGrant(
                        grantId,
                        recipientProfile,
                        "SEASON_PAYOUT",
                        "season-1",
                        RewardGrantState.PENDING,
                        List.of(comp),
                        null,
                        null,
                        now,
                        now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Component operation ID must be derived from");
    }
}
