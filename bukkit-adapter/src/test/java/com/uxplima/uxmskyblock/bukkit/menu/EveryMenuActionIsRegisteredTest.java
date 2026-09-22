package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An action a shipped window names is an action something registers.
 *
 * <p>The windows are the file that teaches an operator the vocabulary. A window that lists an
 * action of ours and reaches nothing gives them a dead button out of the very file they copied it
 * from, and the button looks exactly like a working one until it is pressed.
 *
 * <p>Only this plugin's own namespace is checked. {@code close}, {@code open:}, {@code command:},
 * {@code message:} and {@code sound:} belong to the menu engine and are its to answer for.
 */
class EveryMenuActionIsRegisteredTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");
    private static final Path SOURCES = Path.of("src/main/java");

    /** The plugin's own namespace, as a window writes it. */
    private static final Pattern NAMED = Pattern.compile("\"(skyblock:[a-z-]+)");

    @Test
    @DisplayName("Every action of ours a window names is registered somewhere")
    void everynamedActionIsRegistered() throws IOException {
        Set<String> named = actionsTheWindowsName();
        String sources = allSources();

        TreeSet<String> dead = new TreeSet<>();
        for (String action : named) {
            if (!sources.contains('"' + action + '"')) {
                dead.add(action);
            }
        }

        assertThat(named)
                .describedAs("the windows really do name actions of ours")
                .isNotEmpty();
        assertThat(dead)
                .describedAs("a window lists this and nothing registers it, so an operator who "
                        + "copies the line gets a button that does nothing")
                .isEmpty();
    }

    private static Set<String> actionsTheWindowsName() throws IOException {
        TreeSet<String> named = new TreeSet<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                Matcher matcher = NAMED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    named.add(matcher.group(1));
                }
            }
        }
        return named;
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
