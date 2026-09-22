package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.JsonText;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A payload written into the outbox is JSON a reader can parse, whatever an administrator typed.
 *
 * <p>The payloads are built by hand with the values dropped between quotes. A freeze reason with a
 * quote, a backslash or a line break in it wrote an event no node could read.
 */
class EveryOutboxPayloadParsesTest {

    private static final String AWKWARD = "said \"dupe\" twice\\ then\nleft\ttabbed \u0001";

    @Test
    @DisplayName("Escaped text reads back as the text it was, through a real JSON parser")
    void escapedTextRoundTrips() {
        JsonObject parsed = JsonParser.parseString("{\"text\":" + JsonText.quoted(AWKWARD) + "}")
                .getAsJsonObject();

        assertThat(parsed.get("text").getAsString()).isEqualTo(AWKWARD);
    }

    @Test
    @DisplayName("A freeze whose reason holds quotes and a line break writes a payload that parses")
    void aFreezeWithAnAwkwardReasonParses() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        IslandAdminFreezePort freezes = mock(IslandAdminFreezePort.class);
        IslandAdminFreezeService service = new IslandAdminFreezeService(storage, freezes, null, mock(OutboxPort.class));

        service.freezeIsland(islandId, AWKWARD, "Admin \"Quote\"");

        ArgumentCaptor<StagedOutboxEvent> staged = ArgumentCaptor.forClass(StagedOutboxEvent.class);
        verify(freezes)
                .updateAdministrativeState(eq(islandId), eq(AdministrativeState.FROZEN), eq(AWKWARD), staged.capture());
        JsonObject payload = JsonParser.parseString(staged.getValue().payload()).getAsJsonObject();
        assertThat(payload.get("reason").getAsString()).isEqualTo(AWKWARD);
        assertThat(payload.get("actor").getAsString()).isEqualTo("Admin \"Quote\"");
    }
}
