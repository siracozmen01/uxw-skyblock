package com.uxplima.uxmskyblock.core.application.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.RulesetAccessViolationException;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessInvalidAnchorException;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessManagementPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemporaryAccessServiceTest {

    private InMemoryTemporaryAccessStorage storage;
    private TemporaryAccessService service;

    private final ProfileId ownerProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ProfileId visitorProfile = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final PlayerUuid visitorPlayer = PlayerUuid.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final PlayerUuid differentPlayer = PlayerUuid.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));

    private final String rootType = "uxm:island";
    private final String rootKey = "55555555-5555-5555-5555-555555555555";
    private final CurrentNodeProcessIdentity nodeIdentity = new CurrentNodeProcessIdentity("node-alpha", "gen-boot-1");

    @BeforeEach
    void setUp() {
        storage = new InMemoryTemporaryAccessStorage();
        service = new TemporaryAccessService(storage);
    }

    @Test
    @DisplayName("1. TemporaryAccessDoesNotCreateMembershipTest: Cannot delegate member management or bank withdrawal")
    void temporaryAccessDoesNotCreateMembershipTest() {
        // Attempting to grant MEMBER_INVITE or BANK_WITHDRAW fails fast
        assertThatThrownBy(() -> service.issueGrant(
                        rootKey,
                        rootType,
                        rootKey,
                        visitorProfile,
                        visitorPlayer,
                        ProfileType.CLASSIC,
                        ownerProfile,
                        TerminationPolicy.UNTIL_REVOKED,
                        null,
                        null,
                        null,
                        null,
                        Set.of(StandardPermissions.MEMBER_INVITE),
                        null))
                .isInstanceOf(TemporaryAccessManagementPermissionDeniedException.class);

        assertThatThrownBy(() -> service.issueGrant(
                        rootKey,
                        rootType,
                        rootKey,
                        visitorProfile,
                        visitorPlayer,
                        ProfileType.CLASSIC,
                        ownerProfile,
                        TerminationPolicy.UNTIL_REVOKED,
                        null,
                        null,
                        null,
                        null,
                        Set.of(StandardPermissions.BANK_WITHDRAW),
                        null))
                .isInstanceOf(TemporaryAccessManagementPermissionDeniedException.class);
    }

    @Test
    @DisplayName("2. TemporaryAccessExpiryPolicyContractTest: All termination policies evaluate deterministically")
    void temporaryAccessExpiryPolicyContractTest() {
        Instant now = Instant.now();

        // 1. UNTIL_TIMESTAMP
        TemporaryAccessGrant timestampGrant = service.issueGrant(
                rootKey,
                rootType,
                rootKey,
                visitorProfile,
                visitorPlayer,
                ProfileType.CLASSIC,
                ownerProfile,
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                Set.of(StandardPermissions.BLOCK_BREAK),
                now.plus(Duration.ofMinutes(10)));
        assertThat(timestampGrant.state()).isEqualTo(GrantState.ACTIVE);

        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_BREAK,
                        now,
                        nodeIdentity,
                        null,
                        ProfileType.CLASSIC))
                .isTrue();

        // After expiry timestamp
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_BREAK,
                        now.plus(Duration.ofMinutes(11)),
                        nodeIdentity,
                        null,
                        ProfileType.CLASSIC))
                .isFalse();

        // 2. UNTIL_REVOKED
        TemporaryAccessGrant revokedGrant = service.issueGrant(
                rootKey,
                rootType,
                rootKey,
                visitorProfile,
                visitorPlayer,
                ProfileType.CLASSIC,
                ownerProfile,
                TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                Set.of(StandardPermissions.CHEST_OPEN),
                null);

        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.CHEST_OPEN,
                        now,
                        nodeIdentity,
                        null,
                        ProfileType.CLASSIC))
                .isTrue();

        service.revokeGrant(revokedGrant.grantId(), ownerProfile);
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.CHEST_OPEN,
                        now,
                        nodeIdentity,
                        null,
                        ProfileType.CLASSIC))
                .isFalse();
    }

    @Test
    @DisplayName(
            "18. RulesetCannotBeBypassedByTemporaryTrustTest: Ironman profiles cannot receive economic permissions")
    void rulesetCannotBeBypassedByTemporaryTrustTest() {
        // Attempting to grant shop or bank permissions to Ironman fails at issue time
        assertThatThrownBy(() -> service.issueGrant(
                        rootKey,
                        rootType,
                        rootKey,
                        visitorProfile,
                        visitorPlayer,
                        ProfileType.IRONMAN,
                        ownerProfile,
                        TerminationPolicy.UNTIL_REVOKED,
                        null,
                        null,
                        null,
                        null,
                        Set.of(StandardPermissions.SHOP_ACCESS),
                        null))
                .isInstanceOf(RulesetAccessViolationException.class);

        // Even if grant had non-economic permission, economic check unconditionally returns false for Ironman
        service.issueGrant(
                rootKey,
                rootType,
                rootKey,
                visitorProfile,
                visitorPlayer,
                ProfileType.IRONMAN,
                ownerProfile,
                TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                Set.of(StandardPermissions.NATURAL_INTERACT),
                null);

        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.SHOP_ACCESS,
                        Instant.now(),
                        nodeIdentity,
                        null,
                        ProfileType.IRONMAN))
                .isFalse();
    }

    @Test
    @DisplayName("21. TemporaryAccessCannotAnchorToDifferentPlayerTest: anchor_player_uuid must belong to grantee")
    void temporaryAccessCannotAnchorToDifferentPlayerTest() {
        assertThatThrownBy(() -> service.issueGrant(
                        rootKey,
                        rootType,
                        rootKey,
                        visitorProfile,
                        visitorPlayer,
                        ProfileType.CLASSIC,
                        ownerProfile,
                        TerminationPolicy.UNTIL_SESSION_END,
                        differentPlayer, // Different player!
                        1L,
                        null,
                        null,
                        Set.of(StandardPermissions.BLOCK_BREAK),
                        null))
                .isInstanceOf(TemporaryAccessInvalidAnchorException.class);
    }

    @Test
    @DisplayName(
            "22. TemporaryAccessSessionGenerationExpiryTest: Evaluates session absent, epoch mismatch, lease expiry")
    void temporaryAccessSessionGenerationExpiryTest() {
        Instant now = Instant.now();
        service.issueGrant(
                rootKey,
                rootType,
                rootKey,
                visitorProfile,
                visitorPlayer,
                ProfileType.CLASSIC,
                ownerProfile,
                TerminationPolicy.UNTIL_SESSION_END,
                visitorPlayer,
                5L, // anchor session epoch = 5
                null,
                null,
                Set.of(StandardPermissions.BLOCK_PLACE),
                null);

        // 1. Session row absent -> EXPIRED
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_PLACE,
                        now,
                        nodeIdentity,
                        null,
                        ProfileType.CLASSIC))
                .isFalse();

        // 2. Session epoch mismatch -> EXPIRED
        PlayerSessionRecord mismatchEpochSession = new PlayerSessionRecord(
                visitorPlayer,
                visitorProfile,
                ServerNodeId.of("node-alpha"),
                6L, // Epoch 6 != 5
                SessionState.ACTIVE,
                now.plus(Duration.ofMinutes(1)),
                1L,
                null,
                null,
                null);
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_PLACE,
                        now,
                        nodeIdentity,
                        mismatchEpochSession,
                        ProfileType.CLASSIC))
                .isFalse();

        // 3. State not ACTIVE (e.g. DRAINING) -> EXPIRED
        PlayerSessionRecord drainingSession = new PlayerSessionRecord(
                visitorPlayer,
                visitorProfile,
                ServerNodeId.of("node-alpha"),
                5L,
                SessionState.DRAINING,
                now.plus(Duration.ofMinutes(1)),
                1L,
                null,
                null,
                null);
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_PLACE,
                        now,
                        nodeIdentity,
                        drainingSession,
                        ProfileType.CLASSIC))
                .isFalse();

        // 4. Lease expired -> EXPIRED
        PlayerSessionRecord expiredLeaseSession = new PlayerSessionRecord(
                visitorPlayer,
                visitorProfile,
                ServerNodeId.of("node-alpha"),
                5L,
                SessionState.ACTIVE,
                now.minusSeconds(5), // lease expired in the past
                1L,
                null,
                null,
                null);
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_PLACE,
                        now,
                        nodeIdentity,
                        expiredLeaseSession,
                        ProfileType.CLASSIC))
                .isFalse();

        // 5. Exact match and active lease -> VALID
        PlayerSessionRecord validSession = new PlayerSessionRecord(
                visitorPlayer,
                visitorProfile,
                ServerNodeId.of("node-alpha"),
                5L,
                SessionState.ACTIVE,
                now.plus(Duration.ofMinutes(1)),
                1L,
                null,
                null,
                null);
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_PLACE,
                        now,
                        nodeIdentity,
                        validSession,
                        ProfileType.CLASSIC))
                .isTrue();
    }

    @Test
    @DisplayName("23. NodeProcessRestartGrantExpiryTest: Node ID or generation ID mismatch expires grant")
    void nodeProcessRestartGrantExpiryTest() {
        Instant now = Instant.now();
        service.issueGrant(
                rootKey,
                rootType,
                rootKey,
                visitorProfile,
                visitorPlayer,
                ProfileType.CLASSIC,
                ownerProfile,
                TerminationPolicy.NODE_PROCESS_RESTART,
                null,
                null,
                "node-alpha",
                "gen-boot-1",
                Set.of(StandardPermissions.BLOCK_BREAK),
                null);

        // Matching node process identity -> VALID
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_BREAK,
                        now,
                        new CurrentNodeProcessIdentity("node-alpha", "gen-boot-1"),
                        null,
                        ProfileType.CLASSIC))
                .isTrue();

        // Different boot generation -> EXPIRED
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_BREAK,
                        now,
                        new CurrentNodeProcessIdentity("node-alpha", "gen-boot-2"),
                        null,
                        ProfileType.CLASSIC))
                .isFalse();

        // Different node ID -> EXPIRED
        assertThat(service.hasAccess(
                        rootType,
                        rootKey,
                        visitorProfile,
                        StandardPermissions.BLOCK_BREAK,
                        now,
                        new CurrentNodeProcessIdentity("node-beta", "gen-boot-1"),
                        null,
                        ProfileType.CLASSIC))
                .isFalse();
    }

    private static class InMemoryTemporaryAccessStorage implements TemporaryAccessStoragePort {
        private final Map<GrantId, TemporaryAccessGrant> storage = new ConcurrentHashMap<>();

        @Override
        public void save(TemporaryAccessGrant grant) {
            storage.put(grant.grantId(), grant);
        }

        @Override
        public Optional<TemporaryAccessGrant> findById(GrantId grantId) {
            return Optional.ofNullable(storage.get(grantId));
        }

        @Override
        public List<TemporaryAccessGrant> findActiveByGrantee(ProfileId granteeProfileId) {
            List<TemporaryAccessGrant> list = new ArrayList<>();
            for (TemporaryAccessGrant g : storage.values()) {
                if (g.granteeProfileId().equals(granteeProfileId) && g.state() == GrantState.ACTIVE) {
                    list.add(g);
                }
            }
            return list;
        }

        @Override
        public List<TemporaryAccessGrant> findActiveByRoot(String targetRootTypeId, String targetRootKey) {
            List<TemporaryAccessGrant> list = new ArrayList<>();
            for (TemporaryAccessGrant g : storage.values()) {
                if (g.targetRootTypeId().equals(targetRootTypeId)
                        && g.targetRootKey().equals(targetRootKey)
                        && g.state() == GrantState.ACTIVE) {
                    list.add(g);
                }
            }
            return list;
        }

        @Override
        public void updateState(GrantId grantId, GrantState newState, Instant updatedAt) {
            storage.computeIfPresent(grantId, (k, current) -> current.withState(newState, updatedAt));
        }

        @Override
        public void purgeExpired(Instant now) {
            storage.forEach((id, g) -> {
                if (g.state() == GrantState.ACTIVE && g.terminationPolicy() == TerminationPolicy.UNTIL_TIMESTAMP) {
                    if (g.expiresAt() != null && !g.expiresAt().isAfter(now)) {
                        storage.put(id, g.withState(GrantState.EXPIRED, now));
                    }
                }
            });
        }
    }
}
