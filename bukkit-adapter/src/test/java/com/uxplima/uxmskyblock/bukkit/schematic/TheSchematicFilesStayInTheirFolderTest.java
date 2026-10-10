package com.uxplima.uxmskyblock.bukkit.schematic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.format.SpongeSchematicWriter;
import com.uxplima.uxmlib.schematic.paper.CaptureOptions;
import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The structure files are read and written under the plugin's folder and nowhere else, are read once while
 * they are unchanged, and a file that is not a schematic says so rather than pasting nothing.
 */
class TheSchematicFilesStayInTheirFolderTest {

    @TempDir
    Path folder;

    private final List<Vec3i> captured = new ArrayList<>();
    private final World world = mock(World.class);

    private IslandSchematics schematics() {
        return new IslandSchematics(
                folder,
                Runnable::run,
                (schematic, at, options) -> CompletableFuture.failedFuture(new AssertionError("no paste here")),
                (world, corner, other, origin, options) -> {
                    captured.add(origin);
                    return CompletableFuture.completedFuture(island("minecraft:gold_block"));
                },
                PasteOptions.DEFAULT);
    }

    @Test
    @DisplayName("A file is written where none stands, read back as written, and listed by its name")
    void writtenReadAndListed() throws Exception {
        IslandSchematics files = schematics();

        assertThat(files.writeIfMissing("schematics/classic.schem", island("minecraft:stone"))
                        .join())
                .isTrue();
        assertThat(files.writeIfMissing("schematics/classic.schem", island("minecraft:dirt"))
                        .join())
                .describedAs("a file that stands is the operator's")
                .isFalse();
        Files.writeString(folder.resolve("schematics/notes.txt"), "not a schematic");

        assertThat(files.read("schematics/classic.schem").join())
                .hasValueSatisfying(read -> assertThat(read.blockAt(0, 0, 0)).isEqualTo("minecraft:stone"));
        assertThat(files.list().join()).containsExactly("classic");
        assertThat(files.read("schematics/none.schem").join()).isEmpty();
    }

    @Test
    @DisplayName("A file is read once while it is unchanged, and again once it changes")
    void readOnceWhileUnchanged() throws Exception {
        IslandSchematics files = schematics();
        files.writeIfMissing("schematics/classic.schem", island("minecraft:stone"))
                .join();

        Schematic first = files.read("schematics/classic.schem").join().orElseThrow();
        assertThat(files.read("schematics/classic.schem").join()).containsSame(first);

        files.save("classic", world, Vec3i.ZERO, Vec3i.ZERO, Vec3i.ZERO, CaptureOptions.DEFAULT)
                .join();

        assertThat(files.read("schematics/classic.schem").join())
                .hasValueSatisfying(read -> assertThat(read.blockAt(0, 0, 0)).isEqualTo("minecraft:gold_block"));

        // Changed by another tool while the server runs.
        try (var out = Files.newOutputStream(folder.resolve("schematics/classic.schem"))) {
            SpongeSchematicWriter.write(
                    Schematic.builder(2, 1, 1)
                            .dataVersion(4671)
                            .block(0, 0, 0, "minecraft:diamond_block")
                            .build(),
                    out);
        }
        assertThat(files.read("schematics/classic.schem").join())
                .hasValueSatisfying(read -> assertThat(read.blockAt(0, 0, 0)).isEqualTo("minecraft:diamond_block"));
    }

    @Test
    @DisplayName("A path that leads out of the plugin's folder reads nothing and writes nothing")
    void noPathLeavesTheFolder() {
        IslandSchematics files = schematics();

        assertThatThrownBy(() -> files.read("../server.properties").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.writeIfMissing("schematics/../../escaped.schem", island("minecraft:stone"))
                        .join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(folder.resolveSibling("escaped.schem"))).isFalse();
        assertThatThrownBy(() -> files.save("../x", world, Vec3i.ZERO, Vec3i.ZERO, Vec3i.ZERO, CaptureOptions.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(captured).describedAs("nothing was saved for a name refused").isEmpty();
    }

    @Test
    @DisplayName("A file that is not a schematic fails its read, naming the file")
    void aBrokenFileSaysSo() throws Exception {
        Files.createDirectories(folder.resolve("schematics"));
        Files.writeString(folder.resolve("schematics/broken.schem"), "not a schematic", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> schematics().read("schematics/broken.schem").join())
                .hasCauseInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("schematics/broken.schem");
    }

    @Test
    @DisplayName("A name is lower case letters, digits, underscores and dashes, with or without its ending")
    void names() {
        assertThat(IslandSchematics.nameOf("Classic")).hasValue("classic");
        assertThat(IslandSchematics.nameOf("spawn_v2.schem")).hasValue("spawn_v2");
        assertThat(IslandSchematics.nameOf("../classic")).isEmpty();
        assertThat(IslandSchematics.nameOf("two words")).isEmpty();
        assertThat(IslandSchematics.nameOf("")).isEmpty();
        assertThat(IslandSchematics.pathOf("classic")).isEqualTo("schematics/classic.schem");
    }

    private static Schematic island(String block) {
        return Schematic.builder(1, 1, 1)
                .dataVersion(4671)
                .block(0, 0, 0, block)
                .build();
    }
}
