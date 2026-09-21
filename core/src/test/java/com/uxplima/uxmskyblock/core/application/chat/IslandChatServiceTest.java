package com.uxplima.uxmskyblock.core.application.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.chat.ChatRateLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.chat.NoIslandForChatException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandChatServiceTest {

    private static final IslandId ISLAND_ID = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER_ID = ProfileId.of(UUID.randomUUID());
    private static final ProfileId MEMBER_ID = ProfileId.of(UUID.randomUUID());
    private static final ProfileId VISITOR_ID = ProfileId.of(UUID.randomUUID());
    private static final ProfileId SPY_ID = ProfileId.of(UUID.randomUUID());
    private static final IslandId ALLY_ISLAND_ID = IslandId.of(UUID.randomUUID());
    private static final ProfileId ALLY_OWNER_ID = ProfileId.of(UUID.randomUUID());
    private static final IslandId STRANGER_ISLAND_ID = IslandId.of(UUID.randomUUID());
    private static final ProfileId STRANGER_OWNER_ID = ProfileId.of(UUID.randomUUID());

    private FakeIslandStorage storage;
    private LocalIslandChatTransportAdapter transport;
    private RecordingDeliveryPort deliveryPort;
    private Set<ProfileId> onlineMembers;
    private IslandChatService chatService;

    @BeforeEach
    void setUp() {
        storage = new FakeIslandStorage();
        transport = new LocalIslandChatTransportAdapter();
        deliveryPort = new RecordingDeliveryPort();
        onlineMembers = new HashSet<>();

        chatService = new IslandChatService(
                storage,
                transport,
                deliveryPort,
                candidates -> {
                    Set<ProfileId> here = new HashSet<>(candidates);
                    here.retainAll(onlineMembers);
                    return here;
                },
                3);

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        PlayerUuid ownerUuid = PlayerUuid.of(OWNER_ID.value());
        PlayerUuid memberUuid = PlayerUuid.of(MEMBER_ID.value());

        Island island = Island.create(ISLAND_ID, bounds, ownerUuid, OWNER_ID, Instant.now());
        island = island.addMember(new IslandMember(memberUuid, MEMBER_ID, IslandRole.MEMBER, Instant.now()));
        storage.saveIsland(island);

        storage.saveIsland(Island.create(
                ALLY_ISLAND_ID, bounds, PlayerUuid.of(ALLY_OWNER_ID.value()), ALLY_OWNER_ID, Instant.now()));
        storage.saveIsland(Island.create(
                STRANGER_ISLAND_ID,
                bounds,
                PlayerUuid.of(STRANGER_OWNER_ID.value()),
                STRANGER_OWNER_ID,
                Instant.now()));
    }

    /** A service whose alliance lookup says these two islands are allied and nothing else is. */
    private IslandChatService alliedChatService(Set<ProfileId> present) {
        return new IslandChatService(
                storage,
                new LocalIslandChatTransportAdapter(),
                deliveryPort,
                candidates -> {
                    Set<ProfileId> here = new HashSet<>(candidates);
                    here.retainAll(present);
                    return here;
                },
                3,
                islandId -> ISLAND_ID.equals(islandId) ? List.of(ALLY_ISLAND_ID) : List.of());
    }

    private static Set<ProfileId> everybodyOnline() {
        return new HashSet<>(Set.of(OWNER_ID, MEMBER_ID, ALLY_OWNER_ID, STRANGER_OWNER_ID));
    }

    @Test
    @DisplayName("A message on the alliance channel reaches the allied island, and not a stranger's")
    void allianceChatReachesTheAllyAndNobodyElse() {
        IslandChatService allied = alliedChatService(everybodyOnline());
        allied.setChannel(OWNER_ID, IslandChatChannel.ALLIANCE);

        allied.sendChat(OWNER_ID, "Owner", "are you there");

        assertThat(deliveryPort.memberDeliveries).hasSize(1);
        assertThat(deliveryPort.memberDeliveries.get(0).recipients())
                .contains(OWNER_ID, MEMBER_ID, ALLY_OWNER_ID)
                .doesNotContain(STRANGER_OWNER_ID);
    }

    @Test
    @DisplayName("A message on the island's own channel never reaches the ally")
    void islandChatStaysOnTheIsland() {
        IslandChatService allied = alliedChatService(everybodyOnline());
        allied.setChannel(OWNER_ID, IslandChatChannel.ISLAND);

        allied.sendChat(OWNER_ID, "Owner", "just us");

        assertThat(deliveryPort.memberDeliveries).hasSize(1);
        assertThat(deliveryPort.memberDeliveries.get(0).recipients())
                .contains(OWNER_ID, MEMBER_ID)
                .doesNotContain(ALLY_OWNER_ID);
    }

    @Test
    @DisplayName("The frame says which channel it is, so a reader can tell the two apart")
    void theFrameNamesItsChannel() {
        IslandChatService allied = alliedChatService(everybodyOnline());
        allied.setChannel(OWNER_ID, IslandChatChannel.ALLIANCE);

        allied.sendChat(OWNER_ID, "Owner", "hello allies");

        assertThat(deliveryPort.memberDeliveries.get(0).frame().channel()).isEqualTo(IslandChatChannel.ALLIANCE);
    }

    @Test
    @DisplayName("The short form sends one line on the alliance channel and leaves the player where they were")
    void theShortFormDoesNotMoveThePlayer() {
        IslandChatService allied = alliedChatService(everybodyOnline());
        allied.setChannel(OWNER_ID, IslandChatChannel.ISLAND);

        allied.sendChatOn(OWNER_ID, "Owner", "one line", IslandChatChannel.ALLIANCE);

        assertThat(deliveryPort.memberDeliveries.get(0).frame().channel()).isEqualTo(IslandChatChannel.ALLIANCE);
        assertThat(allied.getChannel(OWNER_ID))
                .describedAs("a short form that silently moves somebody sends their next message to the wrong people")
                .isEqualTo(IslandChatChannel.ISLAND);
    }

    @Test
    @DisplayName("A player who was on the public channel is still on it after the short form")
    void theShortFormLeavesAPublicPlayerPublic() {
        IslandChatService allied = alliedChatService(everybodyOnline());

        allied.sendChatOn(OWNER_ID, "Owner", "one line", IslandChatChannel.ALLIANCE);

        assertThat(allied.getChannel(OWNER_ID)).isEqualTo(IslandChatChannel.GLOBAL);
    }

    @Test
    @DisplayName("A server without alliances puts a player asking for the channel on their island's own")
    void withoutAlliancesTheChannelFallsBackToTheIsland() {
        onlineMembers.add(OWNER_ID);

        assertThat(chatService.hasAlliances()).isFalse();
        assertThat(chatService.setChannel(OWNER_ID, IslandChatChannel.ALLIANCE)).isEqualTo(IslandChatChannel.ISLAND);
    }

    @Test
    @DisplayName("An allied island decides for itself who among its own members may read chat")
    void theAlliedIslandAppliesItsOwnPermissions() {
        Island ally = storage.findIslandById(ALLY_ISLAND_ID).orElseThrow();
        ProfileId quietOne = ProfileId.of(UUID.randomUUID());
        storage.saveIsland(ally.addMember(
                new IslandMember(PlayerUuid.of(quietOne.value()), quietOne, IslandRole.VISITOR, Instant.now())));

        Set<ProfileId> online = everybodyOnline();
        online.add(quietOne);
        IslandChatService allied = alliedChatService(online);
        allied.setChannel(OWNER_ID, IslandChatChannel.ALLIANCE);

        allied.sendChat(OWNER_ID, "Owner", "hello");

        assertThat(deliveryPort.memberDeliveries.get(0).recipients())
                .contains(ALLY_OWNER_ID)
                .describedAs("a visitor on the allied island carries no CHAT_VIEW")
                .doesNotContain(quietOne);
    }

    @Test
    @DisplayName("channel defaults to GLOBAL and toggles to ISLAND when island exists")
    void channelToggleSucceeds() {
        assertThat(chatService.getChannel(OWNER_ID)).isEqualTo(IslandChatChannel.GLOBAL);

        IslandChatChannel toggled = chatService.toggleChannel(OWNER_ID);
        assertThat(toggled).isEqualTo(IslandChatChannel.ISLAND);
        assertThat(chatService.getChannel(OWNER_ID)).isEqualTo(IslandChatChannel.ISLAND);

        IslandChatChannel toggledBack = chatService.toggleChannel(OWNER_ID);
        assertThat(toggledBack).isEqualTo(IslandChatChannel.GLOBAL);
        assertThat(chatService.getChannel(OWNER_ID)).isEqualTo(IslandChatChannel.GLOBAL);
    }

    @Test
    @DisplayName("channel toggle throws NoIslandForChatException when player has no island")
    void channelToggleFailsWithoutIsland() {
        assertThatThrownBy(() -> chatService.toggleChannel(VISITOR_ID)).isInstanceOf(NoIslandForChatException.class);
    }

    @Test
    @DisplayName("spy state toggles and can be set explicitly")
    void spyStateManagement() {
        assertThat(chatService.isSpy(SPY_ID)).isFalse();

        boolean toggled = chatService.toggleSpy(SPY_ID);
        assertThat(toggled).isTrue();
        assertThat(chatService.isSpy(SPY_ID)).isTrue();

        chatService.setSpy(SPY_ID, false);
        assertThat(chatService.isSpy(SPY_ID)).isFalse();
    }

    @Test
    @DisplayName("handlePlayerQuit clears channel and spy state")
    void handlePlayerQuitClearsState() {
        chatService.setChannel(OWNER_ID, IslandChatChannel.ISLAND);
        chatService.setSpy(OWNER_ID, true);

        chatService.handlePlayerQuit(OWNER_ID);

        assertThat(chatService.getChannel(OWNER_ID)).isEqualTo(IslandChatChannel.GLOBAL);
        assertThat(chatService.isSpy(OWNER_ID)).isFalse();
    }

    @Test
    @DisplayName("sendChat rejects sender without island")
    void sendChatRejectsSenderWithoutIsland() {
        assertThatThrownBy(() -> chatService.sendChat(VISITOR_ID, "Visitor", "Hello!"))
                .isInstanceOf(NoIslandForChatException.class);
    }

    @Test
    @DisplayName("sendChat rejects sender without CHAT_SEND permission")
    void sendChatRejectsSenderWithoutPermission() {
        ProfileId mutedMemberId = ProfileId.of(UUID.randomUUID());
        PlayerUuid mutedUuid = PlayerUuid.of(mutedMemberId.value());
        IslandRole mutedRole = new IslandRole(
                "MUTED",
                100,
                "Muted",
                Collections.singleton(IslandPermission.CHAT_VIEW), // CHAT_VIEW only, no CHAT_SEND
                false);

        Island island = storage.findIslandById(ISLAND_ID).orElseThrow();
        island = island.addMember(new IslandMember(mutedUuid, mutedMemberId, mutedRole, Instant.now()));
        storage.saveIsland(island);

        assertThatThrownBy(() -> chatService.sendChat(mutedMemberId, "MutedUser", "Can I speak?"))
                .isInstanceOf(IslandChatPermissionDeniedException.class);
    }

    @Test
    @DisplayName("sendChat distributes to local online members and active staff spies")
    void sendChatDistributesToMembersAndSpies() {
        onlineMembers.add(OWNER_ID);
        onlineMembers.add(MEMBER_ID);
        chatService.setSpy(SPY_ID, true);

        chatService.sendChat(OWNER_ID, "OwnerPlayer", "Team meeting at base!");

        assertThat(deliveryPort.memberDeliveries).hasSize(1);
        RecordingDeliveryPort.MemberDelivery memberDelivery = deliveryPort.memberDeliveries.get(0);
        assertThat(memberDelivery.recipients).containsExactlyInAnyOrder(OWNER_ID, MEMBER_ID);
        assertThat(memberDelivery.frame.message()).isEqualTo("Team meeting at base!");
        assertThat(memberDelivery.frame.senderName()).isEqualTo("OwnerPlayer");
        assertThat(memberDelivery.frame.senderRole()).isEqualTo(IslandRole.OWNER);

        assertThat(deliveryPort.spyDeliveries).hasSize(1);
        RecordingDeliveryPort.SpyDelivery spyDelivery = deliveryPort.spyDeliveries.get(0);
        assertThat(spyDelivery.spies).containsExactly(SPY_ID);
        assertThat(spyDelivery.islandName)
                .isEqualTo(ISLAND_ID.value().toString().substring(0, 8));
        assertThat(spyDelivery.frame.message()).isEqualTo("Team meeting at base!");
    }

    @Test
    @DisplayName("staff spy who is also an island member does not receive duplicate spy message")
    void spyMemberDoesNotReceiveDuplicate() {
        onlineMembers.add(OWNER_ID);
        onlineMembers.add(MEMBER_ID);
        // Owner is both a member recipient AND an active spy
        chatService.setSpy(OWNER_ID, true);
        chatService.setSpy(SPY_ID, true);

        chatService.sendChat(MEMBER_ID, "MemberPlayer", "Secure communication");

        assertThat(deliveryPort.memberDeliveries).hasSize(1);
        assertThat(deliveryPort.memberDeliveries.get(0).recipients).containsExactlyInAnyOrder(OWNER_ID, MEMBER_ID);

        // Only SPY_ID receives spy delivery; OWNER_ID is excluded from spy recipients
        assertThat(deliveryPort.spyDeliveries).hasSize(1);
        assertThat(deliveryPort.spyDeliveries.get(0).spies).containsExactly(SPY_ID);
    }

    @Test
    @DisplayName("sendChat enforces rate limits")
    void sendChatEnforcesRateLimits() {
        onlineMembers.add(OWNER_ID);

        chatService.sendChat(OWNER_ID, "Owner", "msg 1");
        chatService.sendChat(OWNER_ID, "Owner", "msg 2");
        chatService.sendChat(OWNER_ID, "Owner", "msg 3");

        // 4th message exceeds limit of 3
        assertThatThrownBy(() -> chatService.sendChat(OWNER_ID, "Owner", "msg 4"))
                .isInstanceOf(ChatRateLimitExceededException.class);
    }

    @Test
    @DisplayName("blank messages are ignored")
    void blankMessagesIgnored() {
        chatService.sendChat(OWNER_ID, "Owner", "   ");
        assertThat(deliveryPort.memberDeliveries).isEmpty();
        assertThat(deliveryPort.spyDeliveries).isEmpty();
    }

    // Test Helpers

    private static class RecordingDeliveryPort implements IslandChatDeliveryPort {
        record MemberDelivery(Set<ProfileId> recipients, IslandChatFrame frame) {}

        record SpyDelivery(Set<ProfileId> spies, IslandChatFrame frame, String islandName) {}

        final List<MemberDelivery> memberDeliveries = new ArrayList<>();
        final List<SpyDelivery> spyDeliveries = new ArrayList<>();

        @Override
        public void deliverToMembers(Set<ProfileId> recipients, IslandChatFrame frame) {
            memberDeliveries.add(new MemberDelivery(recipients, frame));
        }

        @Override
        public void deliverToSpies(Set<ProfileId> spies, IslandChatFrame frame, String islandName) {
            spyDeliveries.add(new SpyDelivery(spies, frame, islandName));
        }
    }

    private static class FakeIslandStorage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new HashMap<>();
        private final Map<ProfileId, IslandId> profileToIsland = new HashMap<>();

        void saveIsland(Island island) {
            islands.put(island.id(), island);
            for (ProfileId memberId : island.members().keySet()) {
                profileToIsland.put(memberId, island.id());
            }
        }

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            saveIsland(island);
        }

        @Override
        public Optional<Island> findIslandById(IslandId id) {
            return Optional.ofNullable(islands.get(id));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return Optional.ofNullable(profileToIsland.get(profileId));
        }

        @Override
        public void deleteIsland(IslandId id) {
            Island removed = islands.remove(id);
            if (removed != null) {
                profileToIsland.keySet().removeAll(removed.members().keySet());
            }
        }

        @Override
        public Optional<Island> findIslandByLocation(String worldName, int x, int z) {
            return Optional.empty();
        }
    }
}
