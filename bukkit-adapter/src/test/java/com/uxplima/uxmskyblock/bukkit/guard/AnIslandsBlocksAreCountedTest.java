package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whoever enforces an island's limits knows what the island already holds.
 *
 * <p>Every limit count lives in memory. A restart starts every island at zero, so an island that
 * had already placed its whole allowance of hoppers could place the allowance again, and again
 * after the next restart: the limit was defeated by restarting the server. The scan that counts
 * what is really there existed, and nothing called it.
 */
class AnIslandsBlocksAreCountedTest {

    private static final Path BOOTSTRAP = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");
    private static final Path LIMITS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/limit");

    @Test
    @DisplayName("Whoever counts an island's blocks is handed to whoever enforces its limits")
    void thelimitListenerIsToldWhoCounts() throws IOException {
        assertThat(readAll(BOOTSTRAP))
                .describedAs(
                        "an island nobody counts starts each boot at zero and can place its " + "whole allowance again")
                .contains("limitListener.useReconciler(this.limitReconciler)");
    }

    @Test
    @DisplayName("A placement asks for the count before the limit is enforced")
    void theplacementAsksFirst() throws IOException {
        String limits = readAll(LIMITS);

        assertThat(limits)
                .describedAs("the count has to be real before it is compared against anything")
                .contains("countOnceIfNeeded(");
        assertThat(limits.indexOf("countOnceIfNeeded("))
                .describedAs("asked before tryIncrement, not after it")
                .isLessThan(limits.indexOf("limitService.tryIncrement("));
    }

    private static String readAll(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
