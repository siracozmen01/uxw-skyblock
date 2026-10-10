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
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The way each menu asks for a word is a line of {@code text-input.conf}, and the server is given that file.
 *
 * <p>The menu engine read the file and the plugin never wrote it, so an operator who wanted the bank to ask in
 * chat and the warp name in an anvil had nothing to open and no way to learn the names of the places.
 */
class EveryPlaceAMenuAsksIsInTheInputFileTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");
    private static final Path INPUT = Path.of("src/main/resources/text-input.conf");
    private static final Pattern INPUT_STEP = Pattern.compile("do = \"input:([a-z.]+)\"");

    @Test
    @DisplayName("Every place a shipped menu asks for a word has its own line, in a way the engine knows")
    void everyPlaceHasALine() throws IOException {
        Set<String> asked = new TreeSet<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".conf")).toList()) {
                Matcher matcher = INPUT_STEP.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    asked.add(matcher.group(1));
                }
            }
        }
        var root = HoconConfigurationLoader.builder().path(INPUT).build().load();

        assertThat(asked).contains("bank.amount", "warp.name");
        for (String place : asked) {
            assertThat(root.node("modes", place).getString())
                    .describedAs("the way %s asks", place)
                    .isIn("anvil", "chat", "sign", "dialog");
        }
        assertThat(root.node("default-mode").getString()).isIn("anvil", "chat", "sign", "dialog");
    }

    @Test
    @DisplayName("The input file is written next to the server before the menus are read")
    void theFileIsUnpacked() throws IOException {
        String loader = Files.readString(
                Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/ConfigurationLoader.java"),
                StandardCharsets.UTF_8);

        assertThat(loader).contains("unpackResource(plugin, \"text-input.conf\", textInputFile);");
    }
}
