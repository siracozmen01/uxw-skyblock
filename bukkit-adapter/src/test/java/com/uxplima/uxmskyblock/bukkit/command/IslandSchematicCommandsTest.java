package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmlib.schematic.paper.Rotation;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /is schematic} through Brigadier: two corners marked where the operator stands, a box saved around
 * the block they stand on, a schematic pasted there turned as asked, the names listed, and a line for every
 * way it can go wrong.
 */
class IslandSchematicCommandsTest extends MockBukkitHarness {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path folder;

    private record Saved(Vec3i corner, Vec3i otherCorner, Vec3i origin) {}

    private record Pasted(Location at, Rotation rotation) {}

    private final List<Saved> saved = new ArrayList<>();
    private final List<Pasted> pasted = new ArrayList<>();
    private PasteReport report = new PasteReport(12, 0, 0, Set.of(), 1, Set.of(), Map.of(), List.of());
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private World world;
    private PlayerMock admin;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        IslandSchematics schematics = new IslandSchematics(
                folder,
                Runnable::run,
                (schematic, at, options) -> {
                    pasted.add(new Pasted(at, options.rotation()));
                    return CompletableFuture.completedFuture(report);
                },
                (w, corner, other, origin, options) -> {
                    saved.add(new Saved(corner, other, origin));
                    return CompletableFuture.completedFuture(Schematic.builder(3, 3, 4)
                            .dataVersion(4671)
                            .block(0, 0, 0, "minecraft:stone")
                            .build());
                },
                PasteOptions.DEFAULT);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(
                new IslandSchematicCommands(() -> schematics, inlineScheduler(), Messages.bundled()).build());
        admin = createPlayer("Admin");
        admin.addAttachment(MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_SCHEMATIC.node(), true);
    }

    @Test
    @DisplayName("The word alone says how it is used")
    void usage() throws Exception {
        assertThat(run(admin, "schematic")).singleElement().asString().contains("pos1", "save", "paste", "list");
    }

    @Test
    @DisplayName("Two corners marked where the operator stands are saved around the block they stand on")
    void marksAndSaves() throws Exception {
        assertThat(run(admin, "schematic save island"))
                .singleElement()
                .asString()
                .contains("Mark both corners");

        standAt(10, 65, 10);
        assertThat(run(admin, "schematic pos1")).singleElement().asString().contains("10, 65, 10");
        standAt(12, 67, 13);
        assertThat(run(admin, "schematic pos2")).singleElement().asString().contains("12, 67, 13");
        standAt(11, 65, 11);
        List<String> said = run(admin, "schematic save island");

        assertThat(saved)
                .containsExactly(new Saved(new Vec3i(10, 65, 10), new Vec3i(12, 67, 13), new Vec3i(11, 64, 11)));
        assertThat(Files.exists(folder.resolve("schematics/island.schem"))).isTrue();
        assertThat(said.getLast()).contains("Saved island", "3x3x4");
        assertThat(run(admin, "schematic list")).singleElement().asString().contains("island");
    }

    @Test
    @DisplayName("A name that is not one is refused before anything is saved")
    void aBadNameIsRefused() throws Exception {
        standAt(10, 65, 10);
        run(admin, "schematic pos1");
        run(admin, "schematic pos2");

        assertThat(run(admin, "schematic save a.b")).singleElement().asString().contains("lower case");
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("A schematic is pasted on the block the operator stands on, turned as asked, and what it did is said")
    void pastesTurned() throws Exception {
        Files.createDirectories(folder.resolve("schematics"));
        new IslandSchematics(
                        folder,
                        Runnable::run,
                        (s, a, o) -> CompletableFuture.failedFuture(new AssertionError()),
                        (w, a, b, c, o) -> CompletableFuture.failedFuture(new AssertionError()),
                        PasteOptions.DEFAULT)
                .writeIfMissing(
                        "schematics/tower.schem",
                        Schematic.builder(1, 1, 1)
                                .dataVersion(4671)
                                .block(0, 0, 0, "minecraft:stone")
                                .build())
                .join();
        report = new PasteReport(
                12, 3, 0, Set.of("minecraft:conduit"), 1, Set.of(), Map.of(), List.of("minecraft:no_such_block"));
        standAt(20, 70, 20);

        List<String> said = run(admin, "schematic paste tower 90");

        assertThat(pasted).singleElement().satisfies(paste -> {
            assertThat(paste.rotation()).isEqualTo(Rotation.CLOCKWISE_90);
            assertThat(paste.at().getBlockX()).isEqualTo(20);
            assertThat(paste.at().getBlockY()).isEqualTo(69);
            assertThat(paste.at().getBlockZ()).isEqualTo(20);
        });
        assertThat(said).anyMatch(line -> line.contains("Pasted tower") && line.contains("12 blocks"));
        assertThat(said).anyMatch(line -> line.contains("minecraft:no_such_block"));
        assertThat(said).anyMatch(line -> line.contains("minecraft:conduit"));
        assertThat(said).anyMatch(line -> line.contains("3 blocks fell past"));
        assertThat(run(admin, "schematic paste tower")).anyMatch(line -> line.contains("Pasted tower"));
        assertThat(pasted.getLast().rotation()).isEqualTo(Rotation.NONE);
    }

    @Test
    @DisplayName("A turn that is not a quarter turn and a name with no file are each said")
    void refusals() throws Exception {
        standAt(20, 70, 20);

        assertThat(run(admin, "schematic paste tower 45"))
                .singleElement()
                .asString()
                .contains("not a turn");
        assertThat(run(admin, "schematic paste missing")).anyMatch(line -> line.contains("no schematic called"));
        assertThat(run(admin, "schematic list")).singleElement().asString().contains("No schematic");
        assertThat(pasted).isEmpty();
    }

    @Test
    @DisplayName("A player without the node cannot reach the word")
    void aPlayerWithoutTheNodeIsRefused() {
        PlayerMock player = createPlayer("Player");

        List<String> said = new ArrayList<>();
        try {
            said.addAll(run(player, "schematic list"));
        } catch (CommandSyntaxException refused) {
            // Brigadier treats a branch the sender may not see as an unknown command.
        }

        assertThat(said).isEmpty();
    }

    private void standAt(int x, int y, int z) {
        admin.setLocation(new Location(world, x + 0.5, y, z + 0.5));
    }

    private List<String> run(PlayerMock sender, String line) throws CommandSyntaxException {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(line, source);
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = sender.nextComponentMessage()) != null) {
            lines.add(PLAIN.serialize(next));
        }
        return lines;
    }

    private static SchedulerPort inlineScheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }
}
