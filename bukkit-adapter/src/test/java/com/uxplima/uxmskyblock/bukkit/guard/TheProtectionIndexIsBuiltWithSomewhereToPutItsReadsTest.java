package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The index that answers for every block is built with somewhere to put its reads.
 *
 * <p>{@link com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex} answers whose island a block
 * belongs to, and every break, placement, interaction and boundary crossing asks it. A miss has to
 * be filled off the thread the touch arrived on, and the index can only do that if it was given a
 * scheduler. Built without one it fills the miss where it stands, which is a query on the region
 * thread on the first touch of every island, and again after every eviction.
 *
 * <p>The plugin was doing exactly that. Both listeners take the index as optional and substitute one
 * with no scheduler when they are handed none, and both wirings were handing them none. The
 * protection listener's convenience constructor also substitutes the bundled English catalogue, so
 * every refusal a player read ignored the operator's language files.
 *
 * <p>The convenience constructors stay, because a test that wants a synchronous answer is exactly
 * who they are for. What is checked here is that the wiring never takes one.
 */
class TheProtectionIndexIsBuiltWithSomewhereToPutItsReadsTest {

    private static final Path WIRING = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");

    private static String wiringSource(String file) throws IOException {
        return Files.readString(WIRING.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("The one index the plugin builds is given the scheduler")
    void theIndexIsGivenTheScheduler() throws IOException {
        assertThat(wiringSource("SkyblockBootstrap.java"))
                .describedAs("an index with no scheduler fills a miss where it stands")
                .contains("new SpatialIslandIndex(")
                .doesNotContain("new SpatialIslandIndex(persistenceWiring.bootstrap().islandStoragePort(), null)");
    }

    @Test
    @DisplayName("The protection listener is handed that index rather than left to invent one")
    void theProtectionListenerIsHandedTheIndex() throws IOException {
        String source = wiringSource("SkyblockBootstrap.java");
        int at = source.indexOf("new IslandProtectionListener(");
        assertThat(at).describedAs("the wiring builds a protection listener").isGreaterThan(0);

        String call = source.substring(at, source.indexOf(");", at));
        assertThat(call)
                .describedAs("the arguments the wiring passes")
                .contains("spatialIndex")
                .contains("configWiring.messages()");
    }

    @Test
    @DisplayName("The anti abuse listener shares that index rather than inventing a second one")
    void theAntiAbuseListenerSharesIt() throws IOException {
        String source = wiringSource("AdminWiring.java");
        int at = source.indexOf("new IslandAntiAbuseListener(");
        assertThat(at).describedAs("the wiring builds an anti abuse listener").isGreaterThan(0);

        String call = source.substring(at, source.indexOf(");", at));
        assertThat(call).describedAs("the arguments the wiring passes").contains("protectionListener.spatialIndex()");
    }

    @Test
    @DisplayName("No wiring hands a listener the bundled catalogue instead of the operator's")
    void noWiringUsesTheBundledCatalogue() throws IOException {
        try (var files = Files.walk(WIRING)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                assertThat(Files.readString(file, StandardCharsets.UTF_8))
                        .describedAs("%s must read the operator's language files", file.getFileName())
                        .doesNotContain("Messages.bundled()");
            }
        }
    }
}
