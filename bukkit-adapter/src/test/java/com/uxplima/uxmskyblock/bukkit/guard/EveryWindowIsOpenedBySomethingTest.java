package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A window nothing opens is a window nobody sees.
 *
 * <p>The public warp directory drew chat lines for a year while every warp carried an icon material
 * that was written, stored and never drawn, because the window that would have drawn it did not
 * exist. Writing the window is only half of it: a window the bootstrap never builds and no command
 * ever opens is the same defect wearing a nicer class.
 *
 * <p>This reads the tree rather than the running server, so it costs nothing and cannot be fooled
 * by a node whose modules are off. It asks one question of each window: does anything outside the
 * window package name it?
 */
class EveryWindowIsOpenedBySomethingTest {

    private static final Path MENUS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/menu");
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    @DisplayName("Every window is named by something outside the window package")
    void everyWindowIsReached() throws IOException {
        TreeSet<String> unreached = new TreeSet<>();
        List<String> everythingElse = productionSourcesOutsideTheMenuPackage();

        for (String window : windowClasses()) {
            boolean named = everythingElse.stream().anyMatch(body -> body.contains(window));
            if (!named) {
                unreached.add(window);
            }
        }

        assertThat(unreached)
                .describedAs("windows nothing builds and nothing opens, so no player ever sees them")
                .isEmpty();
    }

    @Test
    @DisplayName("The tree really was read, so an empty scan cannot pass this file")
    void theScanFoundBothSides() throws IOException {
        assertThat(windowClasses()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(productionSourcesOutsideTheMenuPackage()).hasSizeGreaterThanOrEqualTo(50);
    }

    private static List<String> windowClasses() throws IOException {
        try (Stream<Path> files = Files.list(MENUS)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Menu.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> productionSourcesOutsideTheMenuPackage() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(MENUS))
                    .map(EveryWindowIsOpenedBySomethingTest::read)
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
