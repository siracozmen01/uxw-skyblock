package com.uxplima.uxmskyblock.core.domain.access;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.jspecify.annotations.Nullable;

/**
 * Pure domain aggregate representing a time-bounded or session-anchored temporary access grant.
 *
 * <p>Strict Invariant: Issuing a {@code TemporaryAccessGrant} never creates membership rows in
 * {@code island_members} or confers team ownership, bank withdrawal, or co-op succession rights.
 */
public record TemporaryAccessGrant(
        GrantId grantId,
        String instanceId,
        String targetRootTypeId,
        String targetRootKey,
        ProfileId granteeProfileId,
        ProfileId grantedByProfileId,
        TerminationPolicy terminationPolicy,
        @Nullable PlayerUuid anchorPlayerUuid,
        @Nullable Long anchorSessionEpoch,
        @Nullable String anchorNodeId,
        @Nullable String anchorProcessGenerationId,
        GrantState state,
        Set<PermissionKey> permissions,
        Instant createdAt,
        @Nullable Instant expiresAt,
        Instant updatedAt) {

    public static final Set<PermissionKey> FORBIDDEN_MANAGEMENT_PERMISSIONS = Set.of(
            StandardPermissions.MEMBER_INVITE,
            StandardPermissions.MEMBER_KICK,
            StandardPermissions.MEMBER_PROMOTE,
            StandardPermissions.MEMBER_DEMOTE,
            StandardPermissions.SETTINGS_MODIFY,
            StandardPermissions.BANK_WITHDRAW);

    public TemporaryAccessGrant {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(targetRootTypeId, "targetRootTypeId must not be null");
        Objects.requireNonNull(targetRootKey, "targetRootKey must not be null");
        Objects.requireNonNull(granteeProfileId, "granteeProfileId must not be null");
        Objects.requireNonNull(grantedByProfileId, "grantedByProfileId must not be null");
        Objects.requireNonNull(terminationPolicy, "terminationPolicy must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // Enforce boundary: temporary access cannot delegate permanent management/ownership/bank withdrawal
        for (PermissionKey key : permissions) {
            if (FORBIDDEN_MANAGEMENT_PERMISSIONS.contains(key)) {
                throw new TemporaryAccessManagementPermissionDeniedException(
                        "Cannot grant management or bank withdrawal permission in temporary access: "
                                + key.qualifiedName());
            }
        }

        if (terminationPolicy == TerminationPolicy.UNTIL_SESSION_END) {
            if (anchorPlayerUuid == null || anchorSessionEpoch == null) {
                throw new TemporaryAccessInvalidAnchorException(
                        "anchorPlayerUuid and anchorSessionEpoch are required for UNTIL_SESSION_END");
            }
        }
        if (terminationPolicy == TerminationPolicy.NODE_PROCESS_RESTART) {
            if (anchorNodeId == null || anchorProcessGenerationId == null) {
                throw new TemporaryAccessInvalidAnchorException(
                        "anchorNodeId and anchorProcessGenerationId are required for NODE_PROCESS_RESTART");
            }
        }
        if (terminationPolicy == TerminationPolicy.UNTIL_TIMESTAMP) {
            if (expiresAt == null) {
                throw new TemporaryAccessInvalidAnchorException("expiresAt is required for UNTIL_TIMESTAMP");
            }
        }

        permissions = Collections.unmodifiableSet(Set.copyOf(permissions));
    }

    public Optional<PlayerUuid> optAnchorPlayerUuid() {
        return Optional.ofNullable(anchorPlayerUuid);
    }

    public Optional<Long> optAnchorSessionEpoch() {
        return Optional.ofNullable(anchorSessionEpoch);
    }

    public Optional<String> optAnchorNodeId() {
        return Optional.ofNullable(anchorNodeId);
    }

    public Optional<String> optAnchorProcessGenerationId() {
        return Optional.ofNullable(anchorProcessGenerationId);
    }

    public Optional<Instant> optExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public boolean hasPermission(PermissionKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return permissions.contains(key);
    }

    /**
     * Evaluates grant expiration using exact distributed termination predicates.
     *
     * @param now current evaluation timestamp
     * @param currentNodeIdentity active node runtime identity
     * @param sessionRecord live session record of the grantee, if available
     * @return true if the grant has expired or is inactive, false if currently valid
     */
    public boolean isExpired(
            Instant now, CurrentNodeProcessIdentity currentNodeIdentity, @Nullable PlayerSessionRecord sessionRecord) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(currentNodeIdentity, "currentNodeIdentity must not be null");

        if (state != GrantState.ACTIVE) {
            return true;
        }

        return switch (terminationPolicy) {
            case UNTIL_TIMESTAMP -> expiresAt != null && !expiresAt.isAfter(now);
            case UNTIL_SESSION_END -> {
                if (sessionRecord == null) {
                    yield true; // 1. Session row absent -> EXPIRED
                }
                if (anchorSessionEpoch == null || sessionRecord.sessionEpoch() != anchorSessionEpoch) {
                    yield true; // 2. Session epoch mismatch -> EXPIRED
                }
                if (sessionRecord.state() != SessionState.ACTIVE || sessionRecord.isLeaseExpired(now)) {
                    yield true; // 3. Session not active or lease expired -> EXPIRED
                }
                yield false;
            }
            case NODE_PROCESS_RESTART -> {
                if (anchorNodeId == null || anchorProcessGenerationId == null) {
                    yield true;
                }
                yield !anchorNodeId.equals(currentNodeIdentity.nodeId())
                        || !anchorProcessGenerationId.equals(currentNodeIdentity.processGenerationId());
            }
            case UNTIL_REVOKED -> false;
        };
    }

    public TemporaryAccessGrant withState(GrantState newState, Instant newUpdatedAt) {
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(newUpdatedAt, "newUpdatedAt must not be null");
        return new TemporaryAccessGrant(
                grantId,
                instanceId,
                targetRootTypeId,
                targetRootKey,
                granteeProfileId,
                grantedByProfileId,
                terminationPolicy,
                anchorPlayerUuid,
                anchorSessionEpoch,
                anchorNodeId,
                anchorProcessGenerationId,
                newState,
                permissions,
                createdAt,
                expiresAt,
                newUpdatedAt);
    }
}
