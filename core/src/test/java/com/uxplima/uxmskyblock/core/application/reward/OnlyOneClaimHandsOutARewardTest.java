package com.uxplima.uxmskyblock.core.application.reward;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
 * A reward is handed out once, however many times it is clicked.
 *
 * <p>The coordinator read the grant's state, saw it was not claimed, wrote CLAIMING and handed out
 * every component. Two claims of the same grant, from a double click or a laggy client sending the
 * packet twice, both read PENDING and both handed out. The delivery handlers catch most of that
 * further down, in a journal and an inventory version check, but a duplicate that has to be undone
 * after the item is in the player's hands is a worse place to catch it than the one where it never
 * starts.
 */
class OnlyOneClaimHandsOutARewardTest {

    private static final ProfileId RECIPIENT = new ProfileId(UUID.randomUUID());

    /** Counts what reached a delivery handler, which is what a duplicate would double. */
    private static final class CountingHandler implements RewardDeliveryHandler {
        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public RewardComponentType supportedType() {
            return RewardComponentType.SQL_CURRENCY;
        }

        @Override
        public DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipientProfileId) {
            delivered.incrementAndGet();
            return DeliveryResult.success(UUID.randomUUID());
        }
    }

    private static RewardGrant pendingGrant(RewardGrantId grantId) {
        RewardGrantComponent component = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                RewardComponentOperationId.derive(grantId, 0),
                RewardComponentType.SQL_CURRENCY,
                "currency",
                1,
                "{\"amount\":100}",
                RewardComponentState.PENDING,
                null,
                Instant.now());
        return new RewardGrant(
                grantId,
                RECIPIENT,
                "TEST",
                "test-1",
                RewardGrantState.PENDING,
                List.of(component),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    @Test
    @DisplayName("Two claims of the same grant hand it out once, not twice")
    void aSecondClaimHandsOutNothing() {
        InMemoryRewardStorage storage = new InMemoryRewardStorage();
        RewardGrantId grantId = RewardGrantId.random();
        RewardGrant grant = pendingGrant(grantId);
        storage.saveGrant(grant);
        CountingHandler handler = new CountingHandler();
        RewardClaimCoordinator coordinator = new RewardClaimCoordinator(storage, List.of(handler));

        // Both claims hold the same snapshot: the one a double click would each have loaded before
        // either of them wrote anything.
        ClaimRewardResult first = coordinator.coordinateClaim(grant, RECIPIENT);
        ClaimRewardResult second = coordinator.coordinateClaim(grant, RECIPIENT);

        assertThat(first.success()).isTrue();
        assertThat(second.success())
                .describedAs("the second claim must be refused, not served")
                .isFalse();
        assertThat(handler.delivered)
                .describedAs("the reward reached the player once")
                .hasValue(1);
    }

    @Test
    @DisplayName("The one claim that wins hands out every component")
    void theWinningClaimDeliversEverything() {
        InMemoryRewardStorage storage = new InMemoryRewardStorage();
        RewardGrantId grantId = RewardGrantId.random();
        RewardGrant grant = pendingGrant(grantId);
        storage.saveGrant(grant);
        CountingHandler handler = new CountingHandler();
        RewardClaimCoordinator coordinator = new RewardClaimCoordinator(storage, List.of(handler));

        ClaimRewardResult result = coordinator.coordinateClaim(grant, RECIPIENT);

        assertThat(result.success()).isTrue();
        assertThat(handler.delivered).hasValue(1);
        assertThat(storage.findGrantById(grantId).orElseThrow().state()).isEqualTo(RewardGrantState.CLAIMED);
    }

    @Test
    @DisplayName("A grant already claimed is answered without handing anything out again")
    void anAlreadyClaimedGrantIsIdempotent() {
        InMemoryRewardStorage storage = new InMemoryRewardStorage();
        RewardGrantId grantId = RewardGrantId.random();
        storage.saveGrant(pendingGrant(grantId));
        storage.updateGrantState(grantId, RewardGrantState.CLAIMED, Instant.now(), Instant.now());
        CountingHandler handler = new CountingHandler();
        RewardClaimCoordinator coordinator = new RewardClaimCoordinator(storage, List.of(handler));

        ClaimRewardResult result =
                coordinator.coordinateClaim(storage.findGrantById(grantId).orElseThrow(), RECIPIENT);

        assertThat(result.success()).isTrue();
        assertThat(handler.delivered).hasValue(0);
    }
}
