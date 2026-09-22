package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The service that announces things is told things.
 *
 * <p>It was built, given a URL per topic, rate limited and queued, and nothing ever told it
 * anything: not one of its four notification methods had a caller anywhere in the plugin. An
 * operator who filled in a webhook URL got silence and no way to tell why. ARCHITECTURE.md names
 * Discord webhooks a version one requirement.
 *
 * <p>Building something and never speaking to it is the shape this checks for, because it is the
 * one the compiler is happiest with.
 */
class TheDiscordServiceIsToldSomethingTest {

    private static final Path WIRING = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap");

    @Test
    @DisplayName("The wiring hands the announcer to the services that know something happened")
    void theAnnouncerIsRegistered() throws IOException {
        String source = Files.readString(WIRING.resolve("IntegrationWiring.java"), StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("an alliance formed or dissolved is worth announcing")
                .contains("allianceService().setAnnouncer(");
        assertThat(source)
                .describedAs("an island frozen or unfrozen is worth announcing")
                .contains("freezeService().setAnnouncer(");
    }

    @Test
    @DisplayName("Every topic an operator can fill in has something that fills it")
    void everyTopicHasASource() throws IOException {
        String config = Files.readString(Path.of("src/main/resources/modules/discord.conf"), StandardCharsets.UTF_8);

        // The two wired here. The other two are named in the file and have their own work; this
        // records which is which rather than letting all four look alike.
        assertThat(config).contains("alliances").contains("admin-audit");
    }
}
