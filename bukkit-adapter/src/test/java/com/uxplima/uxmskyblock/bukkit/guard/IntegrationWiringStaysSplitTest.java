package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The integration wiring stays small enough to read.
 *
 * <p>It had grown past eight hundred lines: the command tree, the menu verbs, the sweeps and the crash
 * recoveries all lived in one constructor and its helpers. Each now has a class of its own, and a new
 * part belongs in one of those or in another beside them.
 */
class IntegrationWiringStaysSplitTest {

    private static final Path BOOTSTRAP = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");

    @Test
    @DisplayName("IntegrationWiring stays under five hundred lines")
    void theWiringStaysSmall() throws IOException {
        long lines = Files.readAllLines(BOOTSTRAP.resolve("IntegrationWiring.java"), StandardCharsets.UTF_8)
                .size();

        assertThat(lines).isLessThan(500);
    }

    @Test
    @DisplayName("The parts that moved out are still called from it")
    void thePartsAreStillCalled() throws IOException {
        String source = Files.readString(BOOTSTRAP.resolve("IntegrationWiring.java"), StandardCharsets.UTF_8);
        String commands = Files.readString(BOOTSTRAP.resolve("IslandCommandWiring.java"), StandardCharsets.UTF_8);

        assertThat(commands).contains("SkyblockMenuVerbs.register(");
        assertThat(source)
                .contains("IslandCommandWiring.build(")
                .contains("DomainEventSubscription.subscribe(")
                .contains("housekeeping.start();")
                .contains("housekeeping.recoverAfterStart();")
                .contains("housekeeping.close();");
    }
}
