package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An upgrade key names one upgrade, wherever it is written.
 *
 * <p>The shipped configuration called them {@code island_size} and {@code member_limit}. The Java
 * constants called them {@code SIZE} and {@code MEMBERS}. The menu file's clicks named the constants.
 * So the control menu read a tier nothing had ever stored and showed every island tier 0, and a
 * click on the size slot asked to buy an upgrade the catalogue had never heard of.
 *
 * <p>Nothing in the build could see it: the file parsed, the Java compiled, and no test ever put a
 * key from one of the three next to a key from another.
 */
class EveryUpgradeKeyNamesARealUpgradeTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    /** Only a quoted click argument counts, so the file's own prose about the verb is not a key. */
    private static final Pattern BUY = Pattern.compile("\"skyblock:buy-upgrade:([A-Za-z0-9_]+)\"");

    private static Set<String> definedKeys() {
        return UpgradesConfiguration.defaultConfiguration().definitions().keySet().stream()
                .map(UpgradeId::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Test
    @DisplayName("Every upgrade the plugin itself reads is one the shipped configuration defines")
    void everyBuiltInUpgradeIsDefined() {
        Set<String> defined = definedKeys();
        for (UpgradeId upgradeId : UpgradeId.builtIn()) {
            assertThat(defined)
                    .describedAs("upgrades the shipped configuration defines, looking for %s", upgradeId.key())
                    .contains(upgradeId.key());
        }
    }

    @Test
    @DisplayName("Every key a shipped menu asks to buy is one the shipped configuration defines")
    void everyMenuKeyIsDefined() throws IOException {
        Set<String> defined = definedKeys();
        List<String> asked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MENUS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                var matcher = BUY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    asked.add(UpgradeId.of(matcher.group(1)).key());
                }
            }
        }

        assertThat(asked).describedAs("menu slots that buy an upgrade").isNotEmpty();
        assertThat(defined)
                .describedAs("upgrades the shipped configuration defines")
                .containsAll(asked);
    }

    @Test
    @DisplayName("Every upgrade the shipped configuration defines can be bought from a shipped menu")
    void everyDefinedUpgradeHasASlot() throws IOException {
        List<String> asked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MENUS)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                var matcher = BUY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    asked.add(UpgradeId.of(matcher.group(1)).key());
                }
            }
        }

        assertThat(asked)
                .describedAs("an upgrade an operator pays for but no shipped menu sells")
                .containsAll(definedKeys());
    }
}
