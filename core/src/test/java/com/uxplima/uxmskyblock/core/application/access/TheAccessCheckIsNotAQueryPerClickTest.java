package com.uxplima.uxmskyblock.core.application.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.domain.access.CurrentNodeProcessIdentity;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.core.domain.permission.StandardPermissions;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asking whether somebody has a temporary grant is not a query per click.
 *
 * <p>Every block a player who is not a member of the island touches goes through this check, and it
 * read the grant table every single time, on the thread the interaction arrived on. The common case
 * is an island with no grants at all, and that case was the uncached one, so a visitor tapping a
 * chest ran a query per tap.
 */
class TheAccessCheckIsNotAQueryPerClickTest {

    private static final String ROOT_TYPE = "uxm:island";
    private static final String ROOT_KEY = "55555555-5555-5555-5555-555555555555";
    private static final ProfileId OWNER = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final ProfileId VISITOR = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final PlayerUuid VISITOR_PLAYER =
            PlayerUuid.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private static final PermissionKey BLOCK_BREAK = StandardPermissions.BLOCK_BREAK;
    private static final CurrentNodeProcessIdentity NODE = new CurrentNodeProcessIdentity("node-alpha", "gen-1");
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

    /** Counts what reached the database, which is the whole point. */
    private static final class CountingStorage implements TemporaryAccessStoragePort {
        private final Map<GrantId, TemporaryAccessGrant> grants = new ConcurrentHashMap<>();
        final AtomicInteger rootQueries = new AtomicInteger();

        @Override
        public void save(TemporaryAccessGrant grant) {
            grants.put(grant.grantId(), grant);
        }

        @Override
        public Optional<TemporaryAccessGrant> findById(GrantId grantId) {
            return Optional.ofNullable(grants.get(grantId));
        }

        @Override
        public List<TemporaryAccessGrant> findActiveByGrantee(ProfileId granteeProfileId) {
            return grants.values().stream()
                    .filter(grant -> grant.granteeProfileId().equals(granteeProfileId))
                    .filter(grant -> grant.state() == GrantState.ACTIVE)
                    .toList();
        }

        @Override
        public List<TemporaryAccessGrant> findActiveByRoot(String targetRootTypeId, String targetRootKey) {
            rootQueries.incrementAndGet();
            return grants.values().stream()
                    .filter(grant -> grant.targetRootTypeId().equals(targetRootTypeId))
                    .filter(grant -> grant.targetRootKey().equals(targetRootKey))
                    .filter(grant -> grant.state() == GrantState.ACTIVE)
                    .toList();
        }

        @Override
        public void updateState(GrantId grantId, GrantState newState, Instant updatedAt) {
            TemporaryAccessGrant grant = grants.get(grantId);
            if (grant != null) {
                grants.put(grantId, grant.withState(newState, updatedAt));
            }
        }

        @Override
        public void purgeExpired(Instant now) {
            grants.values().removeIf(grant -> grant.isExpired(now, NODE, null));
        }
    }

    private boolean ask(TemporaryAccessService service) {
        return service.hasAccess(ROOT_TYPE, ROOT_KEY, VISITOR, BLOCK_BREAK, NOW, NODE, null, ProfileType.CLASSIC);
    }

    @Test
    @DisplayName("A hundred clicks on an island with no grants read the table once")
    void aHundredClicksReadTheTableOnce() {
        CountingStorage storage = new CountingStorage();
        TemporaryAccessService service = new TemporaryAccessService(storage, Duration.ofSeconds(30));

        for (int click = 0; click < 100; click++) {
            assertThat(ask(service)).describedAs("click %d", click).isFalse();
        }

        assertThat(storage.rootQueries.get())
                .describedAs("queries the grant table answered for a hundred clicks")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A grant this node just made is seen at once, not after the window")
    void aGrantThisNodeMadeIsSeenAtOnce() {
        CountingStorage storage = new CountingStorage();
        TemporaryAccessService service = new TemporaryAccessService(storage, Duration.ofHours(1));

        assertThat(ask(service)).describedAs("before the grant").isFalse();

        service.issueGrant(
                ROOT_KEY,
                ROOT_TYPE,
                ROOT_KEY,
                VISITOR,
                VISITOR_PLAYER,
                ProfileType.CLASSIC,
                OWNER,
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                Set.of(BLOCK_BREAK),
                NOW.plus(Duration.ofHours(2)));

        assertThat(ask(service))
                .describedAs("a grant is not made to wait for a window this node does not need")
                .isTrue();
    }

    @Test
    @DisplayName("A grant this node revoked stops working at once")
    void aRevokedGrantStopsAtOnce() {
        CountingStorage storage = new CountingStorage();
        TemporaryAccessService service = new TemporaryAccessService(storage, Duration.ofHours(1));

        TemporaryAccessGrant grant = service.issueGrant(
                ROOT_KEY,
                ROOT_TYPE,
                ROOT_KEY,
                VISITOR,
                VISITOR_PLAYER,
                ProfileType.CLASSIC,
                OWNER,
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                Set.of(BLOCK_BREAK),
                NOW.plus(Duration.ofHours(2)));
        assertThat(ask(service)).isTrue();

        service.revokeGrant(grant.grantId(), OWNER);

        assertThat(ask(service))
                .describedAs("a revoked grant must not keep working for the length of the window")
                .isFalse();
    }

    @Test
    @DisplayName("A window of nothing reads the table every time, which is what the file says it does")
    void aZeroWindowAlwaysReads() {
        CountingStorage storage = new CountingStorage();
        TemporaryAccessService service = new TemporaryAccessService(storage, Duration.ZERO);

        for (int click = 0; click < 5; click++) {
            ask(service);
        }

        assertThat(storage.rootQueries.get()).isEqualTo(5);
    }
}
