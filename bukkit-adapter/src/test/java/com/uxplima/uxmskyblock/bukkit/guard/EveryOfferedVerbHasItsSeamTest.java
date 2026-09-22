package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A verb a shipped file offers is a verb whose seam this plugin wired.
 *
 * <p>A file that ships is the file an operator copies from. One offering {@code [take-money]} from
 * a plugin that has never looked at the server's economy answers "cannot pay 100 coins" to a line
 * the file itself taught them, and it looks exactly like a working feature until somebody presses
 * it. That happened elsewhere in the estate this afternoon, in the very file that teaches the
 * grammar.
 *
 * <p>The seams are the context's own: a wallet for the money verbs and an item store for the item
 * one. Wiring neither is fine as long as no shipped file offers them, which is what this asks.
 */
class EveryOfferedVerbHasItsSeamTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path SOURCES = Path.of("src/main/java");

    /** Each cost verb, and the call that has to be in the sources before a file may offer it. */
    private static final Map<String, String> SEAM_OF = Map.of(
            "[take-money]", ".wallet(",
            "[give-money]", ".wallet(",
            "[take-item]", ".itemStore(");

    @Test
    @DisplayName("No shipped file offers a verb whose seam is not wired")
    void everyofferedVerbHasItsSeam() throws IOException {
        String sources = allSources();
        TreeSet<String> offered = new TreeSet<>();

        for (Path file : shippedResources()) {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> pair : SEAM_OF.entrySet()) {
                if (body.contains(pair.getKey()) && !sources.contains(pair.getValue())) {
                    offered.add(
                            file.getFileName() + " offers " + pair.getKey() + " and nothing calls " + pair.getValue());
                }
            }
        }

        assertThat(offered)
                .describedAs("a file that ships is the file an operator copies from, and a verb with "
                        + "no seam behind it refuses a line that file taught them")
                .isEmpty();
    }

    @Test
    @DisplayName("The scan really read the shipped files, so an empty one cannot pass this file")
    void thescanReadTheFiles() throws IOException {
        assertThat(shippedResources()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(allSources()).hasSizeGreaterThan(1000);
    }

    private static List<Path> shippedResources() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        }
    }

    private static String allSources() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }
}
