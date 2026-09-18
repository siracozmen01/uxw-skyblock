package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantExpiredException;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantNotFoundException;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RewardInboxServiceTest {

    private InMemoryRewardStorage storage;
    private RewardClaimCoordinator coordinator;
    private RewardInboxService service;

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ProfileId otherProfile = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @BeforeEach
    void setUp() {
        storage = new InMemoryRewardStorage();

        RewardDeliveryHandler mockItemHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.ITEM;
            }

            @Override
            public DeliveryResult deliver(
                    RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        RewardDeliveryHandler mockPermHandler = new RewardDeliveryHandler() {
            @Override
            public RewardComponentType supportedType() {
                return RewardComponentType.PERMISSION;
            }

            @Override
            public DeliveryResult deliver(
                    RewardGrant grant,
                    com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent component,
                    ProfileId recipient) {
                return DeliveryResult.success(component.componentOperationId().value());
            }
        };

        coordinator = new RewardClaimCoordinator(storage, List.of(mockItemHandler, mockPermHandler));
        service = new RewardInboxService(storage, coordinator);
    }

    @Test
    @DisplayName("Issue reward with zero components is rejected")
    void issueWithZeroComponentsFails() {
        assertThatThrownBy(() -> service.issueReward(recipientProfile, "ADMIN", "source-1", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot issue a reward grant with zero components");
    }

    @Test
    @DisplayName("Query pending and all rewards returns correct filtered lists")
    void queryRewardsFilteredProperly() {
        RewardDraftComponent comp = new RewardDraftComponent(RewardComponentType.ITEM, "uxm:item", 1, "{}");

        RewardGrant g1 = service.issueReward(recipientProfile, "SEASON", "s1", null, List.of(comp));
        RewardGrant g2 = service.issueReward(recipientProfile, "SEASON", "s2", null, List.of(comp));
        service.issueReward(otherProfile, "SEASON", "s3", null, List.of(comp));

        List<RewardGrant> pending = service.getPendingRewards(recipientProfile);
        assertThat(pending).hasSize(2).extracting(RewardGrant::grantId).containsExactly(g1.grantId(), g2.grantId());

        // Claim g1
        service.claimReward(g1.grantId(), recipientProfile);

        pending = service.getPendingRewards(recipientProfile);
        assertThat(pending).hasSize(1).extracting(RewardGrant::grantId).containsExactly(g2.grantId());

        List<RewardGrant> all = service.getAllRewards(recipientProfile);
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("ClaimAllRewards claims all pending rewards for a profile")
    void claimAllRewardsSucceeds() {
        RewardDraftComponent comp = new RewardDraftComponent(RewardComponentType.ITEM, "uxm:item", 1, "{}");

        service.issueReward(recipientProfile, "A", "1", null, List.of(comp));
        service.issueReward(recipientProfile, "B", "2", null, List.of(comp));

        ClaimAllRewardsResult result = service.claimAllRewards(recipientProfile);
        assertThat(result.totalProcessed()).isEqualTo(2);
        assertThat(result.successfullyClaimed()).isEqualTo(2);
        assertThat(result.failedOrIncomplete()).isEqualTo(0);

        assertThat(service.getPendingRewards(recipientProfile)).isEmpty();
    }

    @Test
    @DisplayName("Claiming non-existent grant throws RewardGrantNotFoundException")
    void claimNonExistentThrows() {
        assertThatThrownBy(() -> service.claimReward(RewardGrantId.random(), recipientProfile))
                .isInstanceOf(RewardGrantNotFoundException.class);
    }

    @Test
    @DisplayName("Claiming grant belonging to another profile is rejected")
    void claimWrongProfileRejected() {
        RewardDraftComponent comp = new RewardDraftComponent(RewardComponentType.ITEM, "uxm:item", 1, "{}");
        RewardGrant grant = service.issueReward(recipientProfile, "A", "1", null, List.of(comp));

        assertThatThrownBy(() -> service.claimReward(grant.grantId(), otherProfile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not the recipient of grant");
    }

    @Test
    @DisplayName("Expired grant cannot be claimed and throws RewardGrantExpiredException")
    void claimExpiredGrantThrows() {
        RewardDraftComponent comp = new RewardDraftComponent(RewardComponentType.ITEM, "uxm:item", 1, "{}");
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        RewardGrant grant = service.issueReward(recipientProfile, "A", "1", past, List.of(comp));

        assertThatThrownBy(() -> service.claimReward(grant.grantId(), recipientProfile))
                .isInstanceOf(RewardGrantExpiredException.class);

        RewardGrant reloaded = storage.findGrantById(grant.grantId()).orElseThrow();
        assertThat(reloaded.state()).isEqualTo(RewardGrantState.EXPIRED);
    }

    @Test
    @DisplayName("Expire pending rewards purges expired rewards")
    void expirePendingRewardsPurgesProperly() {
        RewardDraftComponent comp = new RewardDraftComponent(RewardComponentType.ITEM, "uxm:item", 1, "{}");
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        Instant future = Instant.now().plus(Duration.ofDays(1));

        RewardGrant expiredGrant = service.issueReward(recipientProfile, "A", "1", past, List.of(comp));
        RewardGrant activeGrant = service.issueReward(recipientProfile, "B", "2", future, List.of(comp));

        int expiredCount = service.expirePendingRewards(Instant.now());
        assertThat(expiredCount).isEqualTo(1);

        RewardGrant g1 = storage.findGrantById(expiredGrant.grantId()).orElseThrow();
        assertThat(g1.state()).isEqualTo(RewardGrantState.EXPIRED);

        RewardGrant g2 = storage.findGrantById(activeGrant.grantId()).orElseThrow();
        assertThat(g2.state()).isEqualTo(RewardGrantState.PENDING);
    }
}
