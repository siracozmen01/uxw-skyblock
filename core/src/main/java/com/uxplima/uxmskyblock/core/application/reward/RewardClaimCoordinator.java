package com.uxplima.uxmskyblock.core.application.reward;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantExpiredException;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;

/**
 * Enterprise orchestrator governing multi-protocol delivery of reward components.
 *
 * <p>Enforces the core invariants from {@code GAMEMODE_ARCHITECTURE.md} Section 11.3:
 * <ul>
 *   <li><b>Isolation Invariant:</b> Delivery operations are keyed by {@code componentOperationId}
 *       derived from {@code grantId + componentIndex}, never ambiguous parent grant ID.</li>
 *   <li><b>Crash Resilience:</b> Per-component state is tracked in SQL. If a node crashes mid-claim,
 *       components marked {@code COMMITTED} are NEVER re-granted on retry.</li>
 *   <li><b>State Lifecycle:</b> The parent grant transitions to {@code CLAIMED} only after all
 *       components are durably completed.</li>
 * </ul>
 */
public final class RewardClaimCoordinator {

    /** How long a claim may sit in {@code CLAIMING} before another claim may take it over. */
    public static final Duration DEFAULT_CLAIM_RECOVERY_WINDOW = Duration.ofMinutes(2);

    private final RewardStoragePort storagePort;
    private final Map<RewardComponentType, RewardDeliveryHandler> handlers;
    private final Duration claimRecoveryWindow;

    public RewardClaimCoordinator(RewardStoragePort storagePort, Collection<RewardDeliveryHandler> deliveryHandlers) {
        this(storagePort, deliveryHandlers, DEFAULT_CLAIM_RECOVERY_WINDOW);
    }

    public RewardClaimCoordinator(
            RewardStoragePort storagePort,
            Collection<RewardDeliveryHandler> deliveryHandlers,
            Duration claimRecoveryWindow) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        Objects.requireNonNull(deliveryHandlers, "deliveryHandlers must not be null");
        this.claimRecoveryWindow = Objects.requireNonNull(claimRecoveryWindow, "claimRecoveryWindow must not be null");
        if (claimRecoveryWindow.isNegative() || claimRecoveryWindow.isZero()) {
            throw new IllegalArgumentException("claimRecoveryWindow must be positive: " + claimRecoveryWindow);
        }
        this.handlers = new EnumMap<>(RewardComponentType.class);
        for (RewardDeliveryHandler handler : deliveryHandlers) {
            this.handlers.put(handler.supportedType(), handler);
        }
    }

    /**
     * Coordinates the delivery of all uncommitted components in the specified grant.
     *
     * @param grant reward grant aggregate
     * @param callerProfileId profile ID attempting the claim
     * @return claim execution result
     */
    public ClaimRewardResult coordinateClaim(RewardGrant grant, ProfileId callerProfileId) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(callerProfileId, "callerProfileId must not be null");

        if (!grant.recipientProfileId().equals(callerProfileId)) {
            throw new IllegalArgumentException(
                    "Profile " + callerProfileId + " is not the recipient of grant " + grant.grantId());
        }

        // 1. Idempotency: Already claimed grants return immediate success without re-delivering
        if (grant.state() == RewardGrantState.CLAIMED) {
            return ClaimRewardResult.success(grant.grantId(), grant.components().size());
        }

        // 2. Expiration check
        Instant now = Instant.now();
        if (grant.isExpired(now)) {
            storagePort.updateGrantState(grant.grantId(), RewardGrantState.EXPIRED, null, now);
            throw new RewardGrantExpiredException("Reward grant " + grant.grantId() + " expired at "
                    + grant.optExpiresAt().orElse(null));
        }

        // 3. Take the claim, and only if nobody else has.
        //
        // This used to read the state and then write CLAIMING unconditionally. Two claims of the same
        // grant, from a double click or a laggy client sending the packet twice, both read PENDING,
        // both wrote CLAIMING, and both went on to hand out every component. The delivery handlers
        // catch most of that further down, in a journal and an inventory version check, but a
        // duplicate that has to be undone after the item is already in the player's hands is a worse
        // place to catch it than here, where it never starts.
        // A claim already under way used to be walked straight past, on the reasoning that a node
        // which crashed halfway must be able to pick the grant up again. It let a second claim
        // arriving while the first was still delivering hand out every component the first had not
        // committed yet. A claim is only taken over once it has sat still longer than the recovery
        // window, which a claim in progress never does.
        if (grant.state() == RewardGrantState.CLAIMING && !hasStalled(grant, now)) {
            return alreadyBeingClaimed(grant);
        }
        if (!storagePort.compareAndSetGrantState(
                grant.grantId(), grant.state(), RewardGrantState.CLAIMING, null, now)) {
            return alreadyBeingClaimed(grant);
        }

        int committedCount = 0;
        int totalCount = grant.components().size();

        for (RewardGrantComponent component : grant.components()) {
            if (component.isCommitted()) {
                committedCount++;
                continue;
            }

            RewardDeliveryHandler handler = handlers.get(component.componentType());
            if (handler == null) {
                storagePort.updateGrantState(grant.grantId(), RewardGrantState.RECOVERY_REQUIRED, null, Instant.now());
                return ClaimRewardResult.failure(
                        grant.grantId(),
                        RewardGrantState.RECOVERY_REQUIRED,
                        committedCount,
                        totalCount,
                        "No delivery handler registered for component type " + component.componentType());
            }

            RewardDeliveryHandler.DeliveryResult result;
            try {
                result = handler.deliver(grant, component, callerProfileId);
            } catch (Exception ex) {
                result = RewardDeliveryHandler.DeliveryResult.failure("Delivery exception: " + ex.getMessage());
            }

            Instant compUpdateTime = Instant.now();
            if (result.success()) {
                UUID journalOpId = result.journalOperationId() != null
                        ? result.journalOperationId()
                        : component.componentOperationId().value();
                storagePort.updateComponentState(
                        component.componentId(), RewardComponentState.COMMITTED, journalOpId, compUpdateTime);
                committedCount++;
            } else {
                storagePort.updateComponentState(
                        component.componentId(), RewardComponentState.FAILED, null, compUpdateTime);
                storagePort.updateGrantState(grant.grantId(), RewardGrantState.RECOVERY_REQUIRED, null, compUpdateTime);
                return ClaimRewardResult.failure(
                        grant.grantId(),
                        RewardGrantState.RECOVERY_REQUIRED,
                        committedCount,
                        totalCount,
                        result.errorMessage() != null ? result.errorMessage() : "Unknown delivery error");
            }
        }

        if (committedCount == totalCount) {
            Instant completionTime = Instant.now();
            storagePort.updateGrantState(grant.grantId(), RewardGrantState.CLAIMED, completionTime, completionTime);
            return ClaimRewardResult.success(grant.grantId(), totalCount);
        }

        return ClaimRewardResult.failure(
                grant.grantId(),
                RewardGrantState.RECOVERY_REQUIRED,
                committedCount,
                totalCount,
                "Incomplete component delivery: committed " + committedCount + " of " + totalCount);
    }

    /**
     * Whether a claim has sat in {@code CLAIMING} long enough that the node holding it is gone.
     *
     * <p>The clock starts when the claim took the grant, and component deliveries do not move it, so
     * the window has to be longer than a whole claim can plausibly take. That is why it is measured
     * in minutes: a grant reaching a dozen handlers is still well inside it, and a node that died
     * mid-claim is well outside it.
     */
    private boolean hasStalled(RewardGrant grant, Instant now) {
        return grant.updatedAt().plus(claimRecoveryWindow).isBefore(now);
    }

    private static ClaimRewardResult alreadyBeingClaimed(RewardGrant grant) {
        return ClaimRewardResult.failure(
                grant.grantId(), grant.state(), 0, grant.components().size(), "This reward is already being claimed.");
    }
}
