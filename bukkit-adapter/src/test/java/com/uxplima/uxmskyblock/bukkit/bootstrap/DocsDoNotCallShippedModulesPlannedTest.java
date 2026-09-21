package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A document must not call a shipped module planned.
 *
 * <p>The architecture document listed the REST adapter, the Redis transport and the proxy bridge as
 * planned and not implemented, long after all three worked. A reader comparing the docs to the code
 * could only conclude one of them was lying, and there was no way to tell which.
 *
 * <p>This is the cheap half of that comparison: a module the build really has must not be described
 * as absent. The expensive half, whether a documented endpoint answers, is what the REST and menu
 * guards do.
 *
 * <p><b>This guard runs where the documents are, which is a developer's checkout and not CI.</b>
 * {@code docs/} is deliberately outside version control, so on a fresh clone there is nothing here
 * to read. A guard that cannot see its subject must say so and stand aside rather than fail a build
 * for everyone; failing there would only teach the next person that this file is noise.
 */
class DocsDoNotCallShippedModulesPlannedTest {

    private static final Path DOCS = Path.of("../docs");

    private static final Path SETTINGS = Path.of("../settings.gradle.kts");

    /** The Gradle paths of the modules this build has, as settings.gradle.kts spells them. */
    private static final List<String> SHIPPED_MODULES =
            List.of(":rest-adapter", ":persistence-adapter", ":bukkit-adapter", ":core", ":api");

    @Test
    @DisplayName("No document calls a module the build ships planned or unimplemented")
    void noShippedModuleIsCalledPlanned() throws IOException {
        assumeTrue(Files.isDirectory(DOCS), "docs/ is outside version control, so there is nothing here to read");
        String settings = Files.readString(SETTINGS, StandardCharsets.UTF_8);
        List<String> offences = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(DOCS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".md")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains("NOT IMPLEMENTED") && !line.contains("PLANNED —")) {
                        continue;
                    }
                    for (String module : SHIPPED_MODULES) {
                        // The quotes matter: settings.gradle.kts spells the path with them, and a
                        // bare contains would match a module whose name is a prefix of another.
                        if (line.contains(module) && settings.contains("\"" + module + "\"")) {
                            offences.add(file.getFileName() + ":" + (i + 1) + "  " + line.strip());
                        }
                    }
                }
            }
        }

        assertThat(scanned).describedAs("the guard really read the documents").isGreaterThan(5);
        assertThat(offences)
                .describedAs("a module the build ships must not be documented as absent")
                .isEmpty();
    }
}
