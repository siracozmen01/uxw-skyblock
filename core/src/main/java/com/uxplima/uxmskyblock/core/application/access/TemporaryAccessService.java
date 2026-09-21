package com.uxplima.uxmskyblock.core.application.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.RulesetAccessViolationException;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrantNotFoundException;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessInvalidAnchorException;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise service governing the creation, revocation, expiry evaluation,
 * and ruleset protection for temporary access grants.
 */
public final class TemporaryAccessService {

    public static final Set<PermissionKey> ECONOMIC_PERMISSIONS = Set.of(
            StandardPermissions.BANK_DEPOSIT, StandardPermissions.BANK_WITHDRAW, StandardPermissions.SHOP_ACCESS);

    /** How long a root's grants may be answered from memory before they are read again. */
    public static final Duration DEFAULT_GRANT_LOOKUP_TTL = Duration.ofSeconds(30);

    private final TemporaryAccessStoragePort storagePort;
    private final Duration grantLookupTtl;

    /**
     * Every grant on a root, and when it was read.
     *
     * <p>{@link #hasAccess} is asked about every block a player who is not a member touches, and it
     * went to the database every single time: a query per click, on the thread the interaction
     * arrived on. The common case is a root with no grants at all, and that case was the uncached
     * one, so a visitor tapping a chest ran a query per tap.
     *
     * <p>A root with no grants is remembered as having none, which is the answer that matters. This
     * node writes its own grants into the map as it makes them, so what it granted is never stale;
     * the read is only there to notice a grant another node made, and a bounded staleness is the
     * right price for that where a query per click is not.
     */
    private final java.util.concurrent.ConcurrentMap<String, List<TemporaryAccessGrant>> grantsByRoot =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentMap<String, Instant> rootReadAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    public TemporaryAccessService(TemporaryAccessStoragePort storagePort) {
        this(storagePort, DEFAULT_GRANT_LOOKUP_TTL);
    }

    public TemporaryAccessService(TemporaryAccessStoragePort storagePort, Duration grantLookupTtl) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.grantLookupTtl = Objects.requireNonNull(grantLookupTtl, "grantLookupTtl must not be null");
        if (grantLookupTtl.isNegative()) {
            throw new IllegalArgumentException("grantLookupTtl must not be negative: " + grantLookupTtl);
        }
    }

    private static String rootKey(String targetRootTypeId, String targetRootKey) {
        return targetRootTypeId + '\u001f' + targetRootKey;
    }

    /** Every grant on the root, from memory when it was read recently enough. */
    private List<TemporaryAccessGrant> activeGrantsOn(String targetRootTypeId, String targetRootKey, Instant now) {
        String key = rootKey(targetRootTypeId, targetRootKey);
        Instant readAt = rootReadAt.get(key);
        List<TemporaryAccessGrant> cached = grantsByRoot.get(key);
        // Good strictly before it runs out, so a window of nothing reads every time, which is what
        // the configuration file promises an operator who writes zero.
        if (cached != null && readAt != null && readAt.plus(grantLookupTtl).isAfter(now)) {
            return cached;
        }
        List<TemporaryAccessGrant> fresh = List.copyOf(storagePort.findActiveByRoot(targetRootTypeId, targetRootKey));
        grantsByRoot.put(key, fresh);
        rootReadAt.put(key, now);
        return fresh;
    }

    /** Forgets what this node knew about a root, so the next question is asked of the database. */
    private void forgetRoot(String targetRootTypeId, String targetRootKey) {
        String key = rootKey(targetRootTypeId, targetRootKey);
        grantsByRoot.remove(key);
        rootReadAt.remove(key);
    }

    /** How many roots this node is answering from memory, for a caller that wants to say so. */
    public int rootsHeldInMemory() {
        return grantsByRoot.size();
    }

    /**
     * Issues a new temporary access grant.
     *
     * @param instanceId target instance ID
     * @param targetRootTypeId root type ID (e.g. "uxm:island")
     * @param targetRootKey root key (e.g. island UUID string)
     * @param granteeProfileId profile ID receiving the grant
     * @param granteePlayerUuid player UUID owning the grantee profile
     * @param granteeProfileType ruleset type of grantee profile
     * @param grantedByProfileId profile ID granting access
     * @param terminationPolicy policy governing termination
     * @param anchorPlayerUuid optional player UUID anchor
     * @param anchorSessionEpoch optional session epoch anchor
     * @param anchorNodeId optional node ID anchor
     * @param anchorProcessGenerationId optional process generation ID anchor
     * @param permissions granted permissions
     * @param expiresAt optional timestamp expiration
     * @return created grant
     */
    public TemporaryAccessGrant issueGrant(
            String instanceId,
            String targetRootTypeId,
            String targetRootKey,
            ProfileId granteeProfileId,
            PlayerUuid granteePlayerUuid,
            ProfileType granteeProfileType,
            ProfileId grantedByProfileId,
            TerminationPolicy terminationPolicy,
            @Nullable PlayerUuid anchorPlayerUuid,
            @Nullable Long anchorSessionEpoch,
            @Nullable String anchorNodeId,
            @Nullable String anchorProcessGenerationId,
            Set<PermissionKey> permissions,
            @Nullable Instant expiresAt) {
        Objects.requireNonNull(granteeProfileId, "granteeProfileId");
        Objects.requireNonNull(granteePlayerUuid, "granteePlayerUuid");
        Objects.requireNonNull(granteeProfileType, "granteeProfileType");
        Objects.requireNonNull(grantedByProfileId, "grantedByProfileId");
        Objects.requireNonNull(terminationPolicy, "terminationPolicy");
        Objects.requireNonNull(permissions, "permissions");

        if (granteeProfileId.equals(grantedByProfileId)) {
            throw new IllegalArgumentException("Cannot grant temporary access to oneself: " + granteeProfileId);
        }

        // Ironman / Hardcore boundary protection (Test 18)
        if (granteeProfileType == ProfileType.IRONMAN) {
            for (PermissionKey key : permissions) {
                if (ECONOMIC_PERMISSIONS.contains(key)) {
                    throw new RulesetAccessViolationException(
                            "Ironman ruleset boundary forbids economic temporary access permissions: "
                                    + key.qualifiedName());
                }
            }
        }

        // Anchor invariant (Test 21): anchor_player_uuid MUST belong to grantee_profile_id
        if (terminationPolicy == TerminationPolicy.UNTIL_SESSION_END) {
            if (anchorPlayerUuid == null || !granteePlayerUuid.equals(anchorPlayerUuid)) {
                throw new TemporaryAccessInvalidAnchorException(
                        "anchor_player_uuid must belong to grantee_profile_id: expected=" + granteePlayerUuid
                                + ", actual=" + anchorPlayerUuid);
            }
        }

        Instant now = Instant.now();
        TemporaryAccessGrant grant = new TemporaryAccessGrant(
                GrantId.random(),
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
                GrantState.ACTIVE,
                permissions,
                now,
                expiresAt,
                now);

        storagePort.save(grant);
        forgetRoot(targetRootTypeId, targetRootKey);
        return grant;
    }

    public void revokeGrant(GrantId grantId, ProfileId revokerProfileId) {
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(revokerProfileId, "revokerProfileId");

        TemporaryAccessGrant grant =
                storagePort.findById(grantId).orElseThrow(() -> new TemporaryAccessGrantNotFoundException(grantId));

        if (grant.state() != GrantState.REVOKED) {
            storagePort.updateState(grantId, GrantState.REVOKED, Instant.now());
        }
        forgetRoot(grant.targetRootTypeId(), grant.targetRootKey());
    }

    /**
     * Authoritatively checks whether a grantee has permission on a target root,
     * deterministically expiring grants if termination conditions are met.
     */
    public boolean hasAccess(
            String targetRootTypeId,
            String targetRootKey,
            ProfileId granteeProfileId,
            PermissionKey permissionKey,
            Instant now,
            CurrentNodeProcessIdentity currentNodeIdentity,
            @Nullable PlayerSessionRecord sessionRecord,
            ProfileType granteeProfileType) {
        Objects.requireNonNull(targetRootTypeId, "targetRootTypeId");
        Objects.requireNonNull(targetRootKey, "targetRootKey");
        Objects.requireNonNull(granteeProfileId, "granteeProfileId");
        Objects.requireNonNull(permissionKey, "permissionKey");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(currentNodeIdentity, "currentNodeIdentity");
        Objects.requireNonNull(granteeProfileType, "granteeProfileType");

        // Ruleset boundary enforcement
        if (granteeProfileType == ProfileType.IRONMAN && ECONOMIC_PERMISSIONS.contains(permissionKey)) {
            return false;
        }

        List<TemporaryAccessGrant> grants = activeGrantsOn(targetRootTypeId, targetRootKey, now);
        for (TemporaryAccessGrant grant : grants) {
            if (!grant.granteeProfileId().equals(granteeProfileId)) {
                continue;
            }

            if (grant.isExpired(now, currentNodeIdentity, sessionRecord)) {
                continue;
            }

            if (grant.hasPermission(permissionKey)) {
                return true;
            }
        }

        return false;
    }

    public List<TemporaryAccessGrant> getActiveGrantsForRoot(String targetRootTypeId, String targetRootKey) {
        // A caller asking for the list is a menu or a command rather than a block click, so it reads
        // through to the database and leaves what it found where the hot path will find it.
        forgetRoot(targetRootTypeId, targetRootKey);
        return activeGrantsOn(targetRootTypeId, targetRootKey, Instant.now());
    }

    public List<TemporaryAccessGrant> getActiveGrantsForGrantee(ProfileId granteeProfileId) {
        return storagePort.findActiveByGrantee(granteeProfileId);
    }

    public Optional<TemporaryAccessGrant> getGrant(GrantId grantId) {
        return storagePort.findById(grantId);
    }

    public void purgeExpired(Instant now) {
        storagePort.purgeExpired(now);
        // Whatever was purged was on some root, and which one is not worth a second query.
        grantsByRoot.clear();
        rootReadAt.clear();
    }
}
