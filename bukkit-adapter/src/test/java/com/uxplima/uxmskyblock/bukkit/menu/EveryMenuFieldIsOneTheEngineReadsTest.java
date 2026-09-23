package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every field a shipped menu writes is one the engine reads.
 *
 * <p>A misspelled field, or one written at a level the engine does not look at, is dropped without
 * a word: the file says something and the menu does something else. uxmLib names such a field when a
 * window loads, and this holds every shipped window to saying none.
 */
class EveryMenuFieldIsOneTheEngineReadsTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    @Test
    @DisplayName("Loading every shipped menu names no field the engine ignores and no warning at all")
    void noShippedMenuWritesAFieldNobodyReads() throws Exception {
        Logger loader = Logger.getLogger(MenuSpecLoader.class.getName());
        List<String> warned = new ArrayList<>();
        Handler listening = new Handler() {
            @Override
            public void publish(LogRecord line) {
                if (line.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warned.add(line.getMessage());
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // Another test may have turned this logger down, and a record below its level never reaches a handler.
        Level was = loader.getLevel();
        loader.setLevel(Level.ALL);
        loader.addHandler(listening);
        try (Stream<Path> files = Files.list(MENUS)) {
            List<Path> menus =
                    files.filter(f -> f.toString().endsWith(".conf")).sorted().toList();
            assertThat(menus).isNotEmpty();
            for (Path menu : menus) {
                new MenuSpecLoader().load(menu);
            }
        } finally {
            loader.removeHandler(listening);
            loader.setLevel(was);
        }

        assertThat(warned)
                .describedAs("what the engine said while reading the shipped menus")
                .isEmpty();
    }
}
