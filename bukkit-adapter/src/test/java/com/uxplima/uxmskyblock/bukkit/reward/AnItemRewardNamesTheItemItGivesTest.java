package com.uxplima.uxmskyblock.bukkit.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.application.reward.RewardDeliveryHandler.DeliveryResult;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An item reward gives the item it names, or nothing.
 *
 * <p>A payload whose item the server does not know was delivered as diamonds, and one that named no
 * item at all as dirt. A reward with a misspelt item, a season payout say, handed every winner
 * diamonds. It is now refused and stays in the inbox, where an operator can see it and fix it.
 */
class AnItemRewardNamesTheItemItGivesTest {

    private ServerMock server;
    private PlayerMock player;
    private PlayerSessionCoordinator sessions;
    private InventoryMutationJournalPort journal;
    private final ProfileId profile = new ProfileId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profile));
        when(sessions.getActiveSession(player.getUniqueId()))
                .thenReturn(new ActiveSession(new PlayerUuid(player.getUniqueId()), profile, 1L, 1L));
        journal = mock(InventoryMutationJournalPort.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"item\":\"DIAMOND_SWORDD\",\"amount\":1}", "{\"amount\":3}"})
    @DisplayName("A payload naming no item the server knows is refused, and nothing is given")
    void anUnknownItemIsRefused(String payload) {
        ItemRewardDeliveryHandler handler = new ItemRewardDeliveryHandler(sessions, journal, new ServerNodeId("node"));

        DeliveryResult result = handler.deliver(grant(), component(payload), profile);

        assertThat(result.success()).isFalse();
        assertThat(player.getInventory().isEmpty())
                .describedAs("what the player was given")
                .isTrue();
        verifyNoInteractions(journal);
    }

    private RewardGrant grant() {
        return new RewardGrant(
                new RewardGrantId(UUID.randomUUID()),
                profile,
                "SEASON_PAYOUT",
                "season-1",
                RewardGrantState.CLAIMING,
                List.of(),
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private static RewardGrantComponent component(String payload) {
        return new RewardGrantComponent(
                UUID.randomUUID(),
                new RewardGrantId(UUID.randomUUID()),
                0,
                new RewardComponentOperationId(UUID.randomUUID()),
                RewardComponentType.ITEM,
                "uxm:item_bundle",
                1,
                payload,
                RewardComponentState.PENDING,
                null,
                Instant.now());
    }
}
