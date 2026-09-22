package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing moves an entity with a synchronous teleport.
 *
 * <p>Folia throws on one, because the destination may belong to another region's thread. Two were
 * left: the void rescue, so a player who fell off an island on a region threaded server fell to
 * their death anyway, and the end of a reset, so the player was left over the void where their
 * island had been. A test on MockBukkit could not see either, because MockBukkit accepts the call
 * Folia refuses.
 */
class NoSynchronousTeleportTest {

    private static final Pattern SYNCHRONOUS = Pattern.compile("\\.teleport\\(");

    @Test
    @DisplayName("No production source calls teleport rather than teleportAsync")
    void noSynchronousTeleport() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.startsWith("//") || line.startsWith("*")) {
                        continue;
                    }
                    Matcher matcher = SYNCHRONOUS.matcher(line);
                    if (matcher.find()) {
                        offences.add(file.getFileName() + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }

        assertThat(offences)
                .describedAs("Folia throws on a synchronous teleport; use teleportAsync")
                .isEmpty();
    }
}
