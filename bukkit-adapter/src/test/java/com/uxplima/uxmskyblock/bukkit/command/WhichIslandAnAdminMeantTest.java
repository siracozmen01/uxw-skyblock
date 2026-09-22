package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Which island an administrator meant when they typed a word.
 *
 * <p>One helper answers this for every administrative verb: the freeze, the unfreeze, the inspect,
 * the delete, the restore and the rollback, and the warp visit beside them. It decides which island
 * a destructive command lands on and it had no test of its own.
 *
 * <p>It reads a word three ways, in this order: an island's own identifier, the player standing on
 * the server under that name, and the player who has been here before under that name. The order
 * matters and so does the falling through: a player who is online but whose session is not ready
 * yet must not stop the last way from being tried.
 */
class WhichIslandAnAdminMeantTest extends MockBukkitHarness {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private IslandLocationService locations;
    private PlayerSessionCoordinator sessions;

    @BeforeEach
    void setUpResolver() {
        locations = mock(IslandLocationService.class);
        sessions = mock(PlayerSessionCoordinator.class);
    }

    private Optional<IslandId> resolve(String target) {
        return IslandAdminCommands.resolveIslandId(sessions, locations, target);
    }

    @Test
    @DisplayName("An island's own identifier is taken as it is written")
    void anidentifierIsTakenAsWritten() {
        assertThat(resolve(ISLAND.value().toString())).contains(ISLAND);
        verifyNoInteractions(locations);
        verifyNoInteractions(sessions);
    }

    @Test
    @DisplayName("An identifier is taken even when no island wears it, because the caller checks that")
    void anidentifierIsNotCheckedHere() {
        UUID nobodys = UUID.randomUUID();

        assertThat(resolve(nobodys.toString()))
                .describedAs("whether an island exists is the verb's question, not this one's")
                .contains(IslandId.of(nobodys));
    }

    @Test
    @DisplayName("A player standing here is read through their session")
    void aplayerWhoIsHereIsReadThroughTheirSession() {
        PlayerMock online = createPlayer("Ayse");
        when(sessions.activeProfile(online.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        assertThat(resolve("Ayse")).contains(ISLAND);
        verify(sessions).activeProfile(online.getUniqueId());
    }

    @Test
    @DisplayName("A player who is here but has no island yet names no island")
    void aplayerWithNoIslandNamesNone() {
        PlayerMock online = createPlayer("Mehmet");
        when(sessions.activeProfile(online.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.empty());
        when(sessions.findDurableActiveProfile(online.getUniqueId())).thenReturn(Optional.empty());

        assertThat(resolve("Mehmet")).isEmpty();
    }

    @Test
    @DisplayName("A player whose session is not ready is still looked for the other way")
    void anunreadySessionFallsThrough() {
        PlayerMock online = createPlayer("Zeynep");
        when(sessions.activeProfile(online.getUniqueId())).thenReturn(Optional.empty());
        when(sessions.findDurableActiveProfile(online.getUniqueId())).thenReturn(Optional.of(PROFILE));
        when(locations.findIslandId(PROFILE)).thenReturn(Optional.of(ISLAND));

        assertThat(resolve("Zeynep"))
                .describedAs("a session that has not finished loading must not lose an admin their island")
                .contains(ISLAND);
    }

    @Test
    @DisplayName("A word nobody has ever been called names no island")
    void awordNobodyHasWornNamesNothing() {
        assertThat(resolve("nobody-has-this-name")).isEmpty();
    }

    @Test
    @DisplayName("A node with no sessions at all still reads an identifier")
    void withoutSessionsAnIdentifierStillWorks() {
        assertThat(IslandAdminCommands.resolveIslandId(
                        null, locations, ISLAND.value().toString()))
                .contains(ISLAND);
        assertThat(IslandAdminCommands.resolveIslandId(null, locations, "Ayse"))
                .describedAs("without a session there is no way from a name to a profile")
                .isEmpty();
    }
}
