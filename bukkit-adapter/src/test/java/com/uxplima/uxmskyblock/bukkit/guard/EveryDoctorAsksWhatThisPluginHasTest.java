package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The doctor asks about everything this plugin has that can go wrong where nobody sees it.
 *
 * <p>Each check is declared in the health class and wired where the plugin is put together. A check
 * that is declared and never wired is a doctor that says nothing about it, which reads as healthy.
 */
class EveryDoctorAsksWhatThisPluginHasTest {

    private static final Path SOURCE = Path.of("src", "main", "java");
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    @DisplayName("A plugin with a database reports its storage")
    void storage() throws IOException {
        assertThat(doctorSays("storage")).isTrue();
    }

    @Test
    @DisplayName("A plugin that ships menu files reports how many were read")
    void windows() throws IOException {
        assertThat(Files.isDirectory(RESOURCES.resolve("menus"))).isTrue();
        assertThat(doctorSays("windows")).isTrue();
    }

    @Test
    @DisplayName("A plugin that publishes placeholders reports whether anybody is listening")
    void placeholders() throws IOException {
        assertThat(sources()).contains("PlaceholderExpansions.register(");
        assertThat(doctorSays("placeholders")).isTrue();
    }

    @Test
    @DisplayName("The world the islands live in and the economy the bank pays through are reported")
    void worldAndEconomy() throws IOException {
        assertThat(doctorSays("islandWorld")).isTrue();
        assertThat(doctorSays("economy")).isTrue();
    }

    @Test
    @DisplayName("The doctor is a command an operator can reach")
    void theDoctorIsReachable() throws IOException {
        assertThat(sources()).contains("Cmd.literal(\"doctor\")").contains("buildDoctor()");
    }

    /** Whether a check is both declared and wired. */
    private static boolean doctorSays(String check) throws IOException {
        String sources = sources();
        return sources.contains("HealthCheck " + check + "(") && sources.contains("SkyblockHealth." + check + "(");
    }

    private static String sources() throws IOException {
        List<String> read = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                read.add(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return String.join("\n", read);
    }
}
