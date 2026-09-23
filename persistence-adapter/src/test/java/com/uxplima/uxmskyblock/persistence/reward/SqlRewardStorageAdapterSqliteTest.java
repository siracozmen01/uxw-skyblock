package com.uxplima.uxmskyblock.persistence.reward;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlRewardStorageAdapterSqliteTest {

    private Database database;
    private SqlRewardStorageAdapter adapter;

    private final ProfileId recipientProfile = ProfileId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final ProfileId otherProfile = ProfileId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new SqlRewardStorageAdapter(database);

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000002');");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('22222222-2222-2222-2222-222222222222', '00000000-0000-0000-0000-000000000002', 'CLASSIC');");
        }
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Saves and retrieves reward grant with multi-protocol components")
    void saveAndFindById() {
        RewardGrantId grantId = RewardGrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(7, ChronoUnit.DAYS);

        RewardGrantComponent comp0 = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                RewardComponentOperationId.derive(grantId, 0),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{\"item\":\"NETHERITE_INGOT\",\"count\":16}",
                RewardComponentState.PENDING,
                null,
                now);

        RewardGrantComponent comp1 = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                1,
                RewardComponentOperationId.derive(grantId, 1),
                RewardComponentType.SQL_CURRENCY,
                "uxm:currency_deposit",
                1,
                "{\"currency\":\"coins\",\"amount\":25000}",
                RewardComponentState.PENDING,
                null,
                now);

        RewardGrant grant = new RewardGrant(
                grantId,
                recipientProfile,
                "SEASON_PAYOUT",
                "season-1-rank-1",
                RewardGrantState.PENDING,
                List.of(comp0, comp1),
                null,
                expiresAt,
                now,
                now);

        adapter.saveGrant(grant);

        Optional<RewardGrant> found = adapter.findGrantById(grantId);
        assertThat(found).isPresent();

        RewardGrant loaded = found.get();
        assertThat(loaded.grantId()).isEqualTo(grantId);
        assertThat(loaded.recipientProfileId()).isEqualTo(recipientProfile);
        assertThat(loaded.sourceType()).isEqualTo("SEASON_PAYOUT");
        assertThat(loaded.sourceId()).isEqualTo("season-1-rank-1");
        assertThat(loaded.state()).isEqualTo(RewardGrantState.PENDING);
        assertThat(loaded.claimedAt()).isNull();
        assertThat(loaded.expiresAt()).isEqualTo(expiresAt);
        assertThat(loaded.createdAt()).isEqualTo(now);
        assertThat(loaded.updatedAt()).isEqualTo(now);

        assertThat(loaded.components()).hasSize(2);
        RewardGrantComponent loadedComp0 = loaded.components().get(0);
        assertThat(loadedComp0.componentId()).isEqualTo(comp0.componentId());
        assertThat(loadedComp0.componentIndex()).isEqualTo(0);
        assertThat(loadedComp0.componentOperationId()).isEqualTo(comp0.componentOperationId());
        assertThat(loadedComp0.componentType()).isEqualTo(RewardComponentType.ITEM);
        assertThat(loadedComp0.payloadTypeId()).isEqualTo("uxm:item_bundle");
        assertThat(loadedComp0.payloadSchemaVersion()).isEqualTo(1);
        assertThat(loadedComp0.payloadData()).isEqualTo("{\"item\":\"NETHERITE_INGOT\",\"count\":16}");
        assertThat(loadedComp0.state()).isEqualTo(RewardComponentState.PENDING);
        assertThat(loadedComp0.journalOperationId()).isNull();

        RewardGrantComponent loadedComp1 = loaded.components().get(1);
        assertThat(loadedComp1.componentId()).isEqualTo(comp1.componentId());
        assertThat(loadedComp1.componentIndex()).isEqualTo(1);
        assertThat(loadedComp1.componentOperationId()).isEqualTo(comp1.componentOperationId());
        assertThat(loadedComp1.componentType()).isEqualTo(RewardComponentType.SQL_CURRENCY);
        assertThat(loadedComp1.state()).isEqualTo(RewardComponentState.PENDING);
    }

    @Test
    @DisplayName("findPendingGrantsByRecipient returns pending and claiming grants, excluding claimed")
    void findPendingGrantsFiltersCorrectly() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        RewardGrantId g1 = RewardGrantId.random();
        RewardGrant grant1 = createGrant(g1, recipientProfile, RewardGrantState.PENDING, now, null);

        RewardGrantId g2 = RewardGrantId.random();
        RewardGrant grant2 = createGrant(g2, recipientProfile, RewardGrantState.CLAIMED, now, now);

        RewardGrantId g3 = RewardGrantId.random();
        RewardGrant grant3 = createGrant(g3, otherProfile, RewardGrantState.PENDING, now, null);

        adapter.saveGrant(grant1);
        adapter.saveGrant(grant2);
        adapter.saveGrant(grant3);

        List<RewardGrant> pending = adapter.findPendingGrantsByRecipient(recipientProfile);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).grantId()).isEqualTo(g1);

        List<RewardGrant> all = adapter.findAllGrantsByRecipient(recipientProfile);
        assertThat(all).hasSize(2).extracting(RewardGrant::grantId).containsExactlyInAnyOrder(g1, g2);
    }

    @Test
    @DisplayName("Updates grant state and component state cleanly")
    void updateStates() {
        RewardGrantId grantId = RewardGrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        RewardGrant grant = createGrant(grantId, recipientProfile, RewardGrantState.PENDING, now, null);
        adapter.saveGrant(grant);

        UUID compId = grant.components().get(0).componentId();
        UUID journalOp = UUID.randomUUID();
        Instant t1 = now.plus(1, ChronoUnit.SECONDS);

        adapter.updateComponentState(compId, RewardComponentState.COMMITTED, journalOp, t1);

        RewardGrant updated = adapter.findGrantById(grantId).orElseThrow();
        assertThat(updated.components().get(0).state()).isEqualTo(RewardComponentState.COMMITTED);
        assertThat(updated.components().get(0).journalOperationId()).isEqualTo(journalOp);

        Instant t2 = now.plus(2, ChronoUnit.SECONDS);
        adapter.updateGrantState(grantId, RewardGrantState.CLAIMED, t2, t2);

        RewardGrant claimed = adapter.findGrantById(grantId).orElseThrow();
        assertThat(claimed.state()).isEqualTo(RewardGrantState.CLAIMED);
        assertThat(claimed.claimedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("expireGrants marks expired grants accurately")
    void expireGrants() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant past = now.minus(10, ChronoUnit.MINUTES);
        Instant future = now.plus(10, ChronoUnit.MINUTES);

        RewardGrantId g1 = RewardGrantId.random();
        RewardGrant grantExpired = createGrant(g1, recipientProfile, RewardGrantState.PENDING, now, past);

        RewardGrantId g2 = RewardGrantId.random();
        RewardGrant grantActive = createGrant(g2, recipientProfile, RewardGrantState.PENDING, now, future);

        adapter.saveGrant(grantExpired);
        adapter.saveGrant(grantActive);

        int expiredCount = adapter.expireGrants(now);
        assertThat(expiredCount).isEqualTo(1);

        assertThat(adapter.findGrantById(g1).orElseThrow().state()).isEqualTo(RewardGrantState.EXPIRED);
        assertThat(adapter.findGrantById(g2).orElseThrow().state()).isEqualTo(RewardGrantState.PENDING);
    }

    @Test
    @DisplayName("Foreign key cascade: deleting recipient profile deletes grant and components")
    void cascadeDeleteRecipientProfile() throws Exception {
        RewardGrantId grantId = RewardGrantId.random();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        RewardGrant grant = createGrant(grantId, recipientProfile, RewardGrantState.PENDING, now, null);
        adapter.saveGrant(grant);

        assertThat(adapter.findGrantById(grantId)).isPresent();

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("DELETE FROM player_profiles WHERE profile_id = '" + recipientProfile.value() + "';");
        }

        assertThat(adapter.findGrantById(grantId)).isEmpty();
    }

    @Test
    @DisplayName("An inbox is read in two queries however many grants it holds, each grant with its own components")
    void anInboxIsReadInTwoQueries() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        java.util.List<RewardGrantId> made = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RewardGrantId id = RewardGrantId.random();
            made.add(id);
            adapter.saveGrant(createGrant(id, recipientProfile, RewardGrantState.PENDING, now.plusSeconds(i), null));
        }
        adapter.saveGrant(createGrant(RewardGrantId.random(), otherProfile, RewardGrantState.PENDING, now, null));
        java.util.List<String> sent = new java.util.ArrayList<>();
        SqlRewardStorageAdapter counted = new SqlRewardStorageAdapter(
                com.uxplima.uxmskyblock.persistence.testfixture.CountingConnections.over(database, sent));

        List<RewardGrant> pending = counted.findPendingGrantsByRecipient(recipientProfile);
        int forPending = sent.size();
        sent.clear();
        List<RewardGrant> all = counted.findAllGrantsByRecipient(recipientProfile);

        assertThat(forPending).describedAs("queries for an inbox of four").isEqualTo(2);
        assertThat(sent).describedAs("queries for every grant of a recipient").hasSize(2);
        assertThat(pending).extracting(RewardGrant::grantId).containsExactlyElementsOf(made);
        assertThat(all).hasSize(4);
        for (RewardGrant grant : pending) {
            assertThat(grant.components())
                    .describedAs("the components of %s", grant.grantId())
                    .hasSize(1)
                    .allMatch(component -> component.grantId().equals(grant.grantId()));
        }
    }

    private RewardGrant createGrant(
            RewardGrantId grantId,
            ProfileId recipient,
            RewardGrantState state,
            Instant createdAt,
            @Nullable Instant expiresAt) {
        RewardGrantComponent comp = new RewardGrantComponent(
                UUID.randomUUID(),
                grantId,
                0,
                RewardComponentOperationId.derive(grantId, 0),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                "{}",
                state == RewardGrantState.CLAIMED ? RewardComponentState.COMMITTED : RewardComponentState.PENDING,
                state == RewardGrantState.CLAIMED ? UUID.randomUUID() : null,
                createdAt);

        return new RewardGrant(
                grantId,
                recipient,
                "ADMIN",
                "manual",
                state,
                List.of(comp),
                state == RewardGrantState.CLAIMED ? createdAt : null,
                expiresAt,
                createdAt,
                createdAt);
    }
}
