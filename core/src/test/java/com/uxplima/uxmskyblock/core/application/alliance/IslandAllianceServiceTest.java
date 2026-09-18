package com.uxplima.uxmskyblock.core.application.alliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteExpiredException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteNotFoundException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.alliance.AlreadyAlliedException;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.alliance.SelfAllianceNotAllowedException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandAllianceServiceTest {

    private InMemoryAllianceStorage storage;
    private IslandAllianceService service;

    private final IslandId islandA = IslandId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private final IslandId islandB = IslandId.of(UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private final IslandId islandC = IslandId.of(UUID.fromString("33333333-3333-3333-3333-333333333333"));
    private final IslandId islandD = IslandId.of(UUID.fromString("44444444-4444-4444-4444-444444444444"));
    private final ProfileId profile1 = ProfileId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    @BeforeEach
    void setUp() {
        storage = new InMemoryAllianceStorage();
        service = new IslandAllianceService(storage, 2, Duration.ofMinutes(5), true, true, true);
    }

    @Test
    @DisplayName("Self alliance invite throws SelfAllianceNotAllowedException")
    void cannotInviteSelf() {
        assertThatThrownBy(() -> service.sendInvite(islandA, islandA, profile1))
                .isInstanceOf(SelfAllianceNotAllowedException.class);
    }

    @Test
    @DisplayName("Cannot send invite when already allied")
    void cannotInviteWhenAlreadyAllied() {
        service.sendInvite(islandA, islandB, profile1);
        service.acceptInvite(islandA, islandB);

        assertThatThrownBy(() -> service.sendInvite(islandA, islandB, profile1))
                .isInstanceOf(AlreadyAlliedException.class);
        assertThatThrownBy(() -> service.sendInvite(islandB, islandA, profile1))
                .isInstanceOf(AlreadyAlliedException.class);
    }

    @Test
    @DisplayName("Cannot send invite when sender or target reaches maxAllies limit")
    void cannotExceedMaxAlliesOnInvite() {
        // Ally A with B
        service.sendInvite(islandA, islandB, profile1);
        service.acceptInvite(islandA, islandB);

        // Ally A with C (A reaches limit 2)
        service.sendInvite(islandA, islandC, profile1);
        service.acceptInvite(islandA, islandC);

        assertThat(service.getAllianceCount(islandA)).isEqualTo(2);

        // A cannot invite D
        assertThatThrownBy(() -> service.sendInvite(islandA, islandD, profile1))
                .isInstanceOf(AllianceLimitExceededException.class);

        // D cannot invite A
        assertThatThrownBy(() -> service.sendInvite(islandD, islandA, profile1))
                .isInstanceOf(AllianceLimitExceededException.class);
    }

    @Test
    @DisplayName("Accept invite fails when invite not found")
    void acceptFailsWhenNotFound() {
        assertThatThrownBy(() -> service.acceptInvite(islandA, islandB))
                .isInstanceOf(AllianceInviteNotFoundException.class);
    }

    @Test
    @DisplayName("Accept invite fails and purges invite when expired")
    void acceptFailsWhenExpired() {
        // Service with negative duration to simulate immediate expiry
        IslandAllianceService expiredService =
                new IslandAllianceService(storage, 2, Duration.ofSeconds(-10), true, true, true);

        expiredService.sendInvite(islandA, islandB, profile1);

        assertThatThrownBy(() -> expiredService.acceptInvite(islandA, islandB))
                .isInstanceOf(AllianceInviteExpiredException.class);

        assertThat(storage.findInvite(islandA, islandB)).isEmpty();
    }

    @Test
    @DisplayName("Accept invite successfully establishes canonical alliance")
    void acceptInviteSuccessfullyEstablishesAlliance() {
        IslandAllianceInvite invite = service.sendInvite(islandA, islandB, profile1);
        assertThat(invite.senderIslandId()).isEqualTo(islandA);
        assertThat(invite.targetIslandId()).isEqualTo(islandB);

        IslandAlliance alliance = service.acceptInvite(islandA, islandB);
        assertThat(alliance.involves(islandA)).isTrue();
        assertThat(alliance.involves(islandB)).isTrue();
        assertThat(alliance.getPartner(islandA)).isEqualTo(islandB);
        assertThat(alliance.getPartner(islandB)).isEqualTo(islandA);

        assertThat(service.areAllied(islandA, islandB)).isTrue();
        assertThat(service.areAllied(islandB, islandA)).isTrue();
        assertThat(service.getAllies(islandA)).containsExactly(islandB);
        assertThat(service.getAllies(islandB)).containsExactly(islandA);
    }

    @Test
    @DisplayName("Decline invite removes the invite without creating alliance")
    void declineInviteRemovesInvite() {
        service.sendInvite(islandA, islandB, profile1);
        service.declineInvite(islandA, islandB);

        assertThat(service.areAllied(islandA, islandB)).isFalse();
        assertThat(storage.findInvite(islandA, islandB)).isEmpty();
    }

    @Test
    @DisplayName("Remove alliance successfully dissolves the relationship")
    void removeAllianceDissolvesRelationship() {
        service.sendInvite(islandA, islandB, profile1);
        service.acceptInvite(islandA, islandB);
        assertThat(service.areAllied(islandA, islandB)).isTrue();

        service.removeAlliance(islandA, islandB);
        assertThat(service.areAllied(islandA, islandB)).isFalse();
        assertThat(service.areAllied(islandB, islandA)).isFalse();
        assertThat(service.getAllianceCount(islandA)).isEqualTo(0);
        assertThat(service.getAllianceCount(islandB)).isEqualTo(0);
    }

    @Test
    @DisplayName("Friendly fire shielding and privileged visit access checks")
    void diplomaticPrivilegesChecks() {
        service.sendInvite(islandA, islandB, profile1);
        service.acceptInvite(islandA, islandB);

        assertThat(service.isFriendlyFireShielded(islandA, islandB)).isTrue();
        assertThat(service.canPrivilegedVisit(islandA, islandB)).isTrue();

        assertThat(service.isFriendlyFireShielded(islandA, islandC)).isFalse();
        assertThat(service.canPrivilegedVisit(islandA, islandC)).isFalse();

        // When shielding disabled in config
        IslandAllianceService unshieldedService =
                new IslandAllianceService(storage, 2, Duration.ofMinutes(5), false, false, false);
        assertThat(unshieldedService.isFriendlyFireShielded(islandA, islandB)).isFalse();
        assertThat(unshieldedService.canPrivilegedVisit(islandA, islandB)).isFalse();
    }

    private static final class InMemoryAllianceStorage implements IslandAllianceStoragePort {

        private final List<IslandAlliance> alliances = new ArrayList<>();
        private final Map<String, IslandAllianceInvite> invites = new HashMap<>();

        private String inviteKey(IslandId sender, IslandId target) {
            return sender.value() + "->" + target.value();
        }

        @Override
        public void saveAlliance(IslandAlliance alliance) {
            alliances.removeIf(a -> a.involves(alliance.islandA()) && a.involves(alliance.islandB()));
            alliances.add(alliance);
        }

        @Override
        public void removeAlliance(IslandId islandA, IslandId islandB) {
            alliances.removeIf(a -> a.involves(islandA) && a.involves(islandB));
        }

        @Override
        public boolean areAllied(IslandId islandA, IslandId islandB) {
            return alliances.stream().anyMatch(a -> a.involves(islandA) && a.involves(islandB));
        }

        @Override
        public List<IslandAlliance> findAlliances(IslandId islandId) {
            return alliances.stream().filter(a -> a.involves(islandId)).toList();
        }

        @Override
        public int countAlliances(IslandId islandId) {
            return findAlliances(islandId).size();
        }

        @Override
        public void saveInvite(IslandAllianceInvite invite) {
            invites.put(inviteKey(invite.senderIslandId(), invite.targetIslandId()), invite);
        }

        @Override
        public Optional<IslandAllianceInvite> findInvite(IslandId senderIslandId, IslandId targetIslandId) {
            return Optional.ofNullable(invites.get(inviteKey(senderIslandId, targetIslandId)));
        }

        @Override
        public List<IslandAllianceInvite> findPendingInvites(IslandId targetIslandId, Instant now) {
            return invites.values().stream()
                    .filter(inv -> inv.targetIslandId().equals(targetIslandId) && !inv.isExpired(now))
                    .toList();
        }

        @Override
        public void deleteInvite(IslandId senderIslandId, IslandId targetIslandId) {
            invites.remove(inviteKey(senderIslandId, targetIslandId));
        }

        @Override
        public void purgeExpiredInvites(Instant now) {
            invites.entrySet().removeIf(e -> e.getValue().isExpired(now));
        }
    }
}
