package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 5 (GAMEMODE_ARCHITECTURE.md Section 11.3 & 12):
 * Asserts that duplicate or concurrent claim requests for the same RewardGrant
 * execute idempotently without duplicating currency or items.
 */
class RewardClaimIdempotencyContractTest {

    private InMemoryRewardStorage storage;
    private RewardClaimCoordinator coordinator;
    private RewardInboxService service;

    private final AtomicInteger itemDeliveries = new AtomicInteger();
    private final AtomicInteger currencyDeliveries = new AtomicInteger();

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @BeforeEach
    void setUp() {
        storage = new InMemoryRewardStorage();
        itemDeliveries.set(0);
        currencyDeliveries.set(0);

        RewardDeliveryHandler itemHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.ITEM;
            }

            @Override
            public DeliveryResult deliver(
                    RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    ProfileId recipient) {
                itemDeliveries.incrementAndGet();
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler currencyHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.SQL_CURRENCY;
            }

            @Override
            public DeliveryResult deliver(
                    RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    ProfileId recipient) {
                currencyDeliveries.incrementAndGet();
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        coordinator = new RewardClaimCoordinator(storage, List.of(itemHandler, currencyHandler));
        service = new RewardInboxService(storage, coordinator);
    }

    @Test
    @DisplayName("5. Duplicate claims on the same grant execute idempotently without duplicate delivery")
    void duplicateClaimsExecuteIdempotentlyWithoutDuplicateDelivery() {
        RewardDraftComponent itemDraft = new RewardDraftComponent(
                RewardComponentType.ITEM, "uxm:item_bundle", 1, "{\"item\":\"DIAMOND_SWORD\"}");
        RewardDraftComponent currencyDraft = new RewardDraftComponent(
                RewardComponentType.SQL_CURRENCY, "uxm:currency_deposit", 1, "{\"amount\":5000}");

        RewardGrant grant = service.issueReward(
                recipientProfile, "SEASON_PAYOUT", "season-1-rank-1", null, List.of(itemDraft, currencyDraft));

        // 1. Initial claim
        ClaimRewardResult initialClaim = service.claimReward(grant.grantId(), recipientProfile);
        assertThat(initialClaim.success()).isTrue();
        assertThat(initialClaim.finalState()).isEqualTo(RewardGrantState.CLAIMED);
        assertThat(initialClaim.committedCount()).isEqualTo(2);

        assertThat(itemDeliveries.get()).isEqualTo(1);
        assertThat(currencyDeliveries.get()).isEqualTo(1);

        // 2. Duplicate claim attempt
        ClaimRewardResult duplicateClaim = service.claimReward(grant.grantId(), recipientProfile);
        assertThat(duplicateClaim.success()).isTrue();
        assertThat(duplicateClaim.finalState()).isEqualTo(RewardGrantState.CLAIMED);

        // Invariant: Handlers were NOT invoked again
        assertThat(itemDeliveries.get()).isEqualTo(1);
        assertThat(currencyDeliveries.get()).isEqualTo(1);

        // 3. Third claim attempt directly via coordinator
        RewardGrant reloaded = storage.findGrantById(grant.grantId()).orElseThrow();
        ClaimRewardResult thirdClaim = coordinator.coordinateClaim(reloaded, recipientProfile);
        assertThat(thirdClaim.success()).isTrue();
        assertThat(itemDeliveries.get()).isEqualTo(1);
        assertThat(currencyDeliveries.get()).isEqualTo(1);
    }
}
