package com.uxplima.uxmskyblock.persistence.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlTemporaryAccessAdapterSqliteTest {

    private Database database;
    private SqlTemporaryAccessAdapter adapter;

    private final ProfileId granteeProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ProfileId grantorProfile = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final PlayerUuid grantorPlayer = PlayerUuid.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlTemporaryAccessAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Saves and retrieves temporary access grant with full permission set")
    void saveAndFindById() {
        GrantId grantId = GrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(3600, ChronoUnit.SECONDS);

        Set<PermissionKey> permissions =
                Set.of(PermissionKey.of("uxm:block.break"), PermissionKey.of("uxm:chest.open"));

        TemporaryAccessGrant grant = new TemporaryAccessGrant(
                grantId,
                "skyblock-01",
                "ISLAND",
                "island-xyz-42",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_TIMESTAMP,
                grantorPlayer,
                1001L,
                "node-alpha",
                "gen-99",
                GrantState.ACTIVE,
                permissions,
                now,
                expiresAt,
                now);

        adapter.save(grant);

        Optional<TemporaryAccessGrant> found = adapter.findById(grantId);
        assertThat(found).isPresent();

        TemporaryAccessGrant loaded = found.get();
        assertThat(loaded.grantId()).isEqualTo(grantId);
        assertThat(loaded.instanceId()).isEqualTo("skyblock-01");
        assertThat(loaded.targetRootTypeId()).isEqualTo("ISLAND");
        assertThat(loaded.targetRootKey()).isEqualTo("island-xyz-42");
        assertThat(loaded.granteeProfileId()).isEqualTo(granteeProfile);
        assertThat(loaded.grantedByProfileId()).isEqualTo(grantorProfile);
        assertThat(loaded.terminationPolicy()).isEqualTo(TerminationPolicy.UNTIL_TIMESTAMP);
        assertThat(loaded.anchorPlayerUuid()).isEqualTo(grantorPlayer);
        assertThat(loaded.anchorSessionEpoch()).isEqualTo(1001L);
        assertThat(loaded.anchorNodeId()).isEqualTo("node-alpha");
        assertThat(loaded.anchorProcessGenerationId()).isEqualTo("gen-99");
        assertThat(loaded.state()).isEqualTo(GrantState.ACTIVE);
        assertThat(loaded.permissions()).containsExactlyInAnyOrderElementsOf(permissions);
        assertThat(loaded.createdAt()).isEqualTo(now);
        assertThat(loaded.expiresAt()).isEqualTo(expiresAt);
        assertThat(loaded.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Saves grant with nullable anchor fields and finds active grants by grantee")
    void findActiveByGrantee() {
        GrantId grant1 = GrantId.random();
        GrantId grant2 = GrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        TemporaryAccessGrant activeGrant = new TemporaryAccessGrant(
                grant1,
                "skyblock-01",
                "ISLAND",
                "island-xyz-1",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(PermissionKey.of("uxm:door.use")),
                now,
                null,
                now);

        TemporaryAccessGrant revokedGrant = new TemporaryAccessGrant(
                grant2,
                "skyblock-01",
                "ISLAND",
                "island-xyz-2",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                GrantState.REVOKED,
                Set.of(),
                now,
                null,
                now);

        adapter.save(activeGrant);
        adapter.save(revokedGrant);

        List<TemporaryAccessGrant> activeGrants = adapter.findActiveByGrantee(granteeProfile);
        assertThat(activeGrants).hasSize(1);
        assertThat(activeGrants.get(0).grantId()).isEqualTo(grant1);
        assertThat(activeGrants.get(0).anchorPlayerUuid()).isNull();
        assertThat(activeGrants.get(0).anchorSessionEpoch()).isNull();
    }

    @Test
    @DisplayName("Finds active grants by target root key")
    void findActiveByRoot() {
        GrantId grant1 = GrantId.random();
        GrantId grant2 = GrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        ProfileId otherGrantee = ProfileId.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));

        TemporaryAccessGrant g1 = new TemporaryAccessGrant(
                grant1,
                "skyblock-01",
                "ISLAND",
                "shared-target",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_SESSION_END,
                grantorPlayer,
                1L,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(),
                now,
                null,
                now);

        TemporaryAccessGrant g2 = new TemporaryAccessGrant(
                grant2,
                "skyblock-01",
                "ISLAND",
                "shared-target",
                otherGrantee,
                grantorProfile,
                TerminationPolicy.UNTIL_SESSION_END,
                grantorPlayer,
                1L,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(),
                now,
                null,
                now);

        adapter.save(g1);
        adapter.save(g2);

        List<TemporaryAccessGrant> rootGrants = adapter.findActiveByRoot("ISLAND", "shared-target");
        assertThat(rootGrants).hasSize(2);
        assertThat(rootGrants.stream().map(TemporaryAccessGrant::grantId).toList())
                .containsExactlyInAnyOrder(grant1, grant2);
    }

    @Test
    @DisplayName("Updates grant state and purges timestamp-expired grants")
    void updateStateAndPurgeExpired() {
        GrantId grant1 = GrantId.random();
        GrantId grant2 = GrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        TemporaryAccessGrant g1 = new TemporaryAccessGrant(
                grant1,
                "skyblock-01",
                "ISLAND",
                "root-1",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(),
                now,
                null,
                now);

        TemporaryAccessGrant g2 = new TemporaryAccessGrant(
                grant2,
                "skyblock-01",
                "ISLAND",
                "root-2",
                granteeProfile,
                grantorProfile,
                TerminationPolicy.UNTIL_TIMESTAMP,
                null,
                null,
                null,
                null,
                GrantState.ACTIVE,
                Set.of(),
                now.minus(10, ChronoUnit.MINUTES),
                now.minus(2, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES));

        adapter.save(g1);
        adapter.save(g2);

        // Revoke g1 explicitly
        adapter.updateState(grant1, GrantState.REVOKED, now.plus(1, ChronoUnit.SECONDS));
        assertThat(adapter.findById(grant1).orElseThrow().state()).isEqualTo(GrantState.REVOKED);

        // Purge expired timestamp grants
        adapter.purgeExpired(now);
        assertThat(adapter.findById(grant2).orElseThrow().state()).isEqualTo(GrantState.EXPIRED);
    }
}
