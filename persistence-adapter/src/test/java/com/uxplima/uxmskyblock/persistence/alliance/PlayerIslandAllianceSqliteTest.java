package com.uxplima.uxmskyblock.persistence.alliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceId;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandAllianceSqliteTest {

    private Database database;
    private PlayerIslandAllianceAdapter adapter;

    private final IslandId islandA = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final IslandId islandB = IslandId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final IslandId islandC = IslandId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final ProfileId profile1 = ProfileId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandAllianceAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Saves alliance and verifies areAllied bidirectional check")
    void saveAndCheckAlliance() {
        IslandAlliance alliance = IslandAlliance.canonical(AllianceId.random(), islandA, islandB, Instant.now());
        adapter.saveAlliance(alliance);

        assertThat(adapter.areAllied(islandA, islandB)).isTrue();
        assertThat(adapter.areAllied(islandB, islandA)).isTrue();
        assertThat(adapter.areAllied(islandA, islandC)).isFalse();
    }

    @Test
    @DisplayName("Queries alliances and counts for participating islands")
    void queryAndCountAlliances() {
        IslandAlliance allianceAB = IslandAlliance.canonical(AllianceId.random(), islandA, islandB, Instant.now());
        IslandAlliance allianceAC = IslandAlliance.canonical(AllianceId.random(), islandA, islandC, Instant.now());

        adapter.saveAlliance(allianceAB);
        adapter.saveAlliance(allianceAC);

        assertThat(adapter.countAlliances(islandA)).isEqualTo(2);
        assertThat(adapter.countAlliances(islandB)).isEqualTo(1);
        assertThat(adapter.countAlliances(islandC)).isEqualTo(1);

        List<IslandAlliance> forA = adapter.findAlliances(islandA);
        assertThat(forA).hasSize(2);
        assertThat(forA.stream().map(a -> a.getPartner(islandA)).toList()).containsExactlyInAnyOrder(islandB, islandC);
    }

    @Test
    @DisplayName("Removes alliance successfully in both directions")
    void removeAlliance() {
        IslandAlliance alliance = IslandAlliance.canonical(AllianceId.random(), islandA, islandB, Instant.now());
        adapter.saveAlliance(alliance);
        assertThat(adapter.areAllied(islandA, islandB)).isTrue();

        adapter.removeAlliance(islandB, islandA);
        assertThat(adapter.areAllied(islandA, islandB)).isFalse();
        assertThat(adapter.countAlliances(islandA)).isEqualTo(0);
    }

    @Test
    @DisplayName("Saves, updates, and finds alliance invite")
    void saveAndFindInvite() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(300, ChronoUnit.SECONDS);

        IslandAllianceInvite invite =
                new IslandAllianceInvite(AllianceInviteId.random(), islandA, islandB, profile1, now, expiresAt);
        adapter.saveInvite(invite);

        Optional<IslandAllianceInvite> retrieved = adapter.findInvite(islandA, islandB);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().senderIslandId()).isEqualTo(islandA);
        assertThat(retrieved.get().targetIslandId()).isEqualTo(islandB);
        assertThat(retrieved.get().senderProfileId()).isEqualTo(profile1);
    }

    @Test
    @DisplayName("Queries pending invites excluding expired ones")
    void queryPendingInvites() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        IslandAllianceInvite activeInvite = new IslandAllianceInvite(
                AllianceInviteId.random(), islandA, islandB, profile1, now, now.plus(300, ChronoUnit.SECONDS));
        IslandAllianceInvite expiredInvite = new IslandAllianceInvite(
                AllianceInviteId.random(),
                islandC,
                islandB,
                profile1,
                now.minus(600, ChronoUnit.SECONDS),
                now.minus(100, ChronoUnit.SECONDS));

        adapter.saveInvite(activeInvite);
        adapter.saveInvite(expiredInvite);

        List<IslandAllianceInvite> pending = adapter.findPendingInvites(islandB, now);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).senderIslandId()).isEqualTo(islandA);
    }

    @Test
    @DisplayName("Deletes invite and purges expired invites")
    void deleteAndPurgeInvites() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        IslandAllianceInvite invite1 = new IslandAllianceInvite(
                AllianceInviteId.random(), islandA, islandB, profile1, now, now.plus(300, ChronoUnit.SECONDS));
        IslandAllianceInvite invite2 = new IslandAllianceInvite(
                AllianceInviteId.random(),
                islandC,
                islandB,
                profile1,
                now.minus(600, ChronoUnit.SECONDS),
                now.minus(100, ChronoUnit.SECONDS));

        adapter.saveInvite(invite1);
        adapter.saveInvite(invite2);

        adapter.deleteInvite(islandA, islandB);
        assertThat(adapter.findInvite(islandA, islandB)).isEmpty();

        adapter.purgeExpiredInvites(now);
        assertThat(adapter.findInvite(islandC, islandB)).isEmpty();
    }
}
