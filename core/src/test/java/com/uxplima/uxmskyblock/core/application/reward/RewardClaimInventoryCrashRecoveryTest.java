package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural Contract Test 6 (GAMEMODE_ARCHITECTURE.md Section 11.3 & 12):
 * Asserts that a simulated node crash during multi-component reward claiming is safely
 * recovered without data loss, partial state leakage, or duplicate delivery.
 */
class RewardClaimInventoryCrashRecoveryTest {

    private InMemoryRewardStorage storage;
    private RewardClaimCoordinator coordinator;
    private RewardInboxService service;

    private final AtomicInteger itemDeliveryCount = new AtomicInteger();
    private final AtomicInteger currencyDeliveryCount = new AtomicInteger();
    private final AtomicBoolean crashSimulated = new AtomicBoolean(true);

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @BeforeEach
    void setUp() {
        storage = new InMemoryRewardStorage();
        itemDeliveryCount.set(0);
        currencyDeliveryCount.set(0);
        crashSimulated.set(true);

        RewardDeliveryHandler itemHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.ITEM;
            }

            @Override
            public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
                itemDeliveryCount.incrementAndGet();
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler currencyHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.SQL_CURRENCY;
            }

            @Override
            public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipient) {
                if (crashSimulated.get()) {
                    // Simulate crash / protocol timeout / node failure during second component delivery
                    return DeliveryResult.failure("Simulated network timeout / node crash");
                }
                currencyDeliveryCount.incrementAndGet();
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        coordinator = new RewardClaimCoordinator(storage, List.of(itemHandler, currencyHandler));
        service = new RewardInboxService(storage, coordinator);
    }

    @Test
    @DisplayName(
            "6. Node crash mid-claim preserves committed components and retries uncommitted cleanly without duplicate delivery")
    void crashMidClaimRecoversCleanlyWithoutDuplication() {
        RewardDraftComponent itemDraft = new RewardDraftComponent(
                RewardComponentType.ITEM, "uxm:item_bundle", 1, "{\"item\":\"NETHERITE_SWORD\"}");
        RewardDraftComponent currencyDraft = new RewardDraftComponent(
                RewardComponentType.SQL_CURRENCY, "uxm:currency_deposit", 1, "{\"amount\":10000}");

        RewardGrant grant = service.issueReward(
                recipientProfile, "SEASON_PAYOUT", "season-1-rank-1", null, List.of(itemDraft, currencyDraft));

        // 1. Initial claim attempt fails on component 1 due to simulated crash
        ClaimRewardResult crashResult = service.claimReward(grant.grantId(), recipientProfile);
        assertThat(crashResult.success()).isFalse();
        assertThat(crashResult.finalState()).isEqualTo(RewardGrantState.RECOVERY_REQUIRED);
        assertThat(crashResult.committedCount()).isEqualTo(1);

        // Verify storage state post-crash: Component 0 is COMMITTED, Component 1 is FAILED
        RewardGrant postCrashGrant = storage.findGrantById(grant.grantId()).orElseThrow();
        assertThat(postCrashGrant.state()).isEqualTo(RewardGrantState.RECOVERY_REQUIRED);
        assertThat(postCrashGrant.components().get(0).state()).isEqualTo(RewardComponentState.COMMITTED);
        assertThat(postCrashGrant.components().get(1).state()).isEqualTo(RewardComponentState.FAILED);

        // Item delivery occurred exactly once before crash
        assertThat(itemDeliveryCount.get()).isEqualTo(1);
        assertThat(currencyDeliveryCount.get()).isEqualTo(0);

        // 2. Node recovers from crash, network restored
        crashSimulated.set(false);

        // 3. Retry claim after recovery
        ClaimRewardResult recoveryResult = service.claimReward(grant.grantId(), recipientProfile);
        assertThat(recoveryResult.success()).isTrue();
        assertThat(recoveryResult.finalState()).isEqualTo(RewardGrantState.CLAIMED);
        assertThat(recoveryResult.committedCount()).isEqualTo(2);

        // Invariant: Item was NEVER re-delivered (count still 1), currency delivered once
        assertThat(itemDeliveryCount.get()).isEqualTo(1);
        assertThat(currencyDeliveryCount.get()).isEqualTo(1);

        // Verify final storage state: All COMMITTED, Grant CLAIMED
        RewardGrant finalGrant = storage.findGrantById(grant.grantId()).orElseThrow();
        assertThat(finalGrant.state()).isEqualTo(RewardGrantState.CLAIMED);
        assertThat(finalGrant.components().get(0).state()).isEqualTo(RewardComponentState.COMMITTED);
        assertThat(finalGrant.components().get(1).state()).isEqualTo(RewardComponentState.COMMITTED);
    }
}
