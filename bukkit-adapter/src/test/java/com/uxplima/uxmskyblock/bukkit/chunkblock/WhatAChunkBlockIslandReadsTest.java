package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.World;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A ChunkBlock island's players read how far it has opened, open the chunk they face with the level the
 * island has, and read the {@code %skyblock_chunkblock_<name>%} placeholders.
 */
class WhatAChunkBlockIslandReadsTest extends MockBukkitHarness {

    private final Map<IslandId, ChunkPos> origins = new HashMap<>();
    private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final AtomicLong level = new AtomicLong();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockPanel panel;

    @BeforeEach
    void setUpPanel() {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Opener");
        ProfileId profile = new ProfileId(player.getUniqueId());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(player.getUniqueId()),
                profile,
                Instant.now());
        when(islands.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island.id()));
        when(islands.findIslandById(island.id())).thenReturn(Optional.of(island));
        service = new ChunkBlockService(memory(), new ChunkUnlockRules(List.of(2L, 5L), 5));
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        panel = new ChunkBlockPanel(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                uuid -> Optional.of(profile),
                (id, who) -> level.get());
    }

    @Test
    @DisplayName("The chunk a player faces: south is growing z, then west, north and east")
    void theFacedChunk() {
        assertThat(ChunkBlockPanel.faced(new Location(world, 8, 100, 8, 0, 0))).isEqualTo(new ChunkPos(0, 1));
        assertThat(ChunkBlockPanel.faced(new Location(world, 8, 100, 8, 90, 0))).isEqualTo(new ChunkPos(-1, 0));
        assertThat(ChunkBlockPanel.faced(new Location(world, 8, 100, 8, 180, 0)))
                .isEqualTo(new ChunkPos(0, -1));
        assertThat(ChunkBlockPanel.faced(new Location(world, 8, 100, 8, -90, 0)))
                .isEqualTo(new ChunkPos(1, 0));
        assertThat(ChunkBlockPanel.faced(new Location(world, 8, 100, 8, 40, 0)))
                .describedAs("forty degrees is still south")
                .isEqualTo(new ChunkPos(0, 1));
    }

    @Test
    @DisplayName("Facing a closed chunk opens it once the level is there, and says what the next needs")
    void unlockingTheFacedChunk() {
        service.start(island.id(), 8, 8);
        player.setLocation(new Location(world, 8, 100, 14, -90, 0));

        level.set(1);
        panel.unlockFaced(player);
        assertThat(said()).singleElement().asString().contains("needs level 2").contains("level 1");

        level.set(2);
        panel.unlockFaced(player);
        assertThat(said()).singleElement().asString().contains("is open").contains("5");
        assertThat(service.isOpen(island.id(), new ChunkPos(1, 0))).contains(true);

        panel.unlockFaced(player);
        assertThat(said()).singleElement().asString().contains("already open");

        player.setLocation(new Location(world, 40, 100, 8, -90, 0));
        panel.unlockFaced(player);
        assertThat(said()).singleElement().asString().contains("shares a side");
    }

    @Test
    @DisplayName("A classic island is told it is not a ChunkBlock island")
    void aClassicIsland() {
        panel.open(player);

        assertThat(said()).singleElement().asString().contains("not a ChunkBlock island");
    }

    @Test
    @DisplayName("Without its menu file the panel says the standing in chat")
    void theStandingInChat() {
        service.start(island.id(), 8, 8);
        level.set(3);

        panel.open(player);

        assertThat(said())
                .singleElement()
                .asString()
                .contains("1 chunks are open")
                .contains("level 2")
                .contains("level 3");
    }

    @Test
    @DisplayName("The placeholders read memory, and say nothing of an island that is not a ChunkBlock island")
    void placeholders() {
        assertThat(panel.placeholder(island.id().value(), "is_chunkblock")).isEqualTo("false");
        assertThat(panel.placeholder(island.id().value(), "open_chunks")).isEmpty();

        service.start(island.id(), 8, 8);
        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 9);

        assertThat(panel.placeholder(island.id().value(), "is_chunkblock")).isEqualTo("true");
        assertThat(panel.placeholder(island.id().value(), "open_chunks")).isEqualTo("2");
        assertThat(panel.placeholder(island.id().value(), "next_level")).isEqualTo("5");
        assertThat(panel.placeholder(island.id().value(), "no_such_value")).isNull();
    }

    @Test
    @DisplayName("The shipped menu file and its words name only values the panel gives")
    void theMenuNamesOnlyWhatThePanelGives() throws Exception {
        java.util.Set<String> given =
                panel.values(ChunkTerritory.startingAt(new ChunkPos(0, 0)), 0).keySet();
        List<String> named = new ArrayList<>();
        collect(Pattern.compile("%argument_([a-z_]+)%"), resource("menus/island-chunks.conf"), named);
        for (String language : List.of("messages/messages_en.conf", "messages/messages_tr.conf")) {
            String text = resource(language);
            String block = text.substring(text.indexOf("    chunks {\n        title"));
            block = block.substring(0, block.indexOf("\n    }"));
            collect(Pattern.compile("<argument_([a-z_]+)>"), block, named);
        }

        assertThat(named).isNotEmpty();
        assertThat(given).containsAll(named);
        assertThat(resource("menus/island-chunks.conf")).contains("skyblock:island:chunks unlock");
    }

    private static void collect(Pattern pattern, String text, List<String> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private List<String> said() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ChunkTerritoryPort memory() {
        return new ChunkTerritoryPort() {
            @Override
            public Map<IslandId, ChunkTerritory> findAll() {
                return Map.of();
            }

            @Override
            public Optional<ChunkTerritory> find(IslandId islandId) {
                ChunkPos origin = origins.get(islandId);
                return origin == null
                        ? Optional.empty()
                        : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
            }

            @Override
            public void start(IslandId islandId, ChunkPos origin) {
                origins.putIfAbsent(islandId, origin);
            }

            @Override
            public boolean open(IslandId islandId, ChunkPos chunk, int order) {
                return opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            }

            @Override
            public void close(IslandId islandId, List<ChunkPos> chunks) {
                opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
            }
        };
    }
}
