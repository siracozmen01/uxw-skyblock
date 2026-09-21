package com.uxplima.uxmskyblock.bukkit.menu;

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
 * Bedrock is the floor, not a feature.
 *
 * <p>A chest window renders for a Bedrock client as a list of icons with no lore, so a window that
 * only exists as a chest is a window a Bedrock player cannot read. Two of the four windows shipped
 * that way, and nothing said so: there is no compile error for a missing form and no test that ever
 * opened one.
 */
class EveryMenuHasABedrockPathTest {

    private static final Path MENUS = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/menu");

    @Test
    @DisplayName("Every window draws a native form for a Bedrock player")
    void everyWindowHasAForm() throws IOException {
        List<String> withoutForm = new ArrayList<>();
        for (Path file : menuClasses()) {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (!body.contains("BedrockFormService")) {
                withoutForm.add(file.getFileName().toString());
            }
        }

        assertThat(withoutForm)
                .describedAs("windows a Bedrock player can open but cannot read")
                .isEmpty();
    }

    @Test
    @DisplayName("The window package really was read, so an empty scan cannot pass this file")
    void theScanFoundWindows() throws IOException {
        assertThat(menuClasses()).hasSizeGreaterThanOrEqualTo(4);
    }

    private static List<Path> menuClasses() throws IOException {
        try (Stream<Path> files = Files.list(MENUS)) {
            return files.filter(f -> f.toString().endsWith("Menu.java")).toList();
        }
    }
}
