package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A command class without a test is a command nobody has typed.
 *
 * <p>Eight of them had none, and what they were missing was not small: a hardcoded quest count in a
 * level recalculation, a visit that checked neither the ban list nor the lock, seven handlers that
 * queried the database on the thread Brigadier calls a command on. Each of those was found by
 * writing the test, not by reading the class.
 *
 * <p>So the rule is the shape rather than the count: a class named {@code Island*Commands} has a
 * class named {@code Island*CommandsTest} beside it.
 */
class EveryCommandClassHasATestTest {

    private static final Path COMMANDS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/command");

    private static final Path TESTS = Path.of("src/test/java/com/uxplima/uxmskyblock/bukkit/command");

    private static SortedSet<String> commandClasses() throws IOException {
        TreeSet<String> classes = new TreeSet<>();
        try (Stream<Path> files = Files.list(COMMANDS)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("Island") && name.endsWith("Commands.java")) {
                    classes.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }
        return classes;
    }

    @Test
    @DisplayName("There really are command classes to check, so a clean result means something")
    void theScanFindsSomething() throws IOException {
        assertThat(commandClasses()).describedAs("command classes").hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("Every command class has a test beside it")
    void everyCommandClassHasATest() throws IOException {
        TreeSet<String> untested = new TreeSet<>();
        for (String commandClass : commandClasses()) {
            if (!Files.exists(TESTS.resolve(commandClass + "Test.java"))) {
                untested.add(commandClass);
            }
        }

        assertThat(untested)
                .describedAs("a command class with no test is a command nobody has typed")
                .isEmpty();
    }
}
