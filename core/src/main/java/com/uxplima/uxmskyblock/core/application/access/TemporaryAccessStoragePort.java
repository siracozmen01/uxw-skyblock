package com.uxplima.uxmskyblock.core.application.access;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Outbound persistence port for saving, retrieving, and updating temporary access grants.
 */
public interface TemporaryAccessStoragePort {

    /**
     * Persists a new temporary access grant and its associated permissions.
     *
     * @param grant the grant aggregate
     */
    void save(TemporaryAccessGrant grant);

    /**
     * Finds a grant by its unique ID.
     *
     * @param grantId grant ID
     * @return optional grant
     */
    Optional<TemporaryAccessGrant> findById(GrantId grantId);

    /**
     * Retrieves all active grants for a specific grantee profile.
     *
     * @param granteeProfileId grantee profile ID
     * @return list of active grants
     */
    List<TemporaryAccessGrant> findActiveByGrantee(ProfileId granteeProfileId);

    /**
     * Retrieves all active grants for a target root instance.
     *
     * @param targetRootTypeId namespaced root type (e.g. "uxm:island")
     * @param targetRootKey root aggregate key
     * @return list of active grants
     */
    List<TemporaryAccessGrant> findActiveByRoot(String targetRootTypeId, String targetRootKey);

    /**
     * Atomically updates the state of a grant.
     *
     * @param grantId grant ID
     * @param newState new grant state
     * @param updatedAt update timestamp
     */
    void updateState(GrantId grantId, GrantState newState, Instant updatedAt);

    /**
     * Purges or expires timestamp-based grants whose expiration has passed.
     *
     * @param now current timestamp
     */
    void purgeExpired(Instant now);
}
