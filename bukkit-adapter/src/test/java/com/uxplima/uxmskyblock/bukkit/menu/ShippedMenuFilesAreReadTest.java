package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shipped menu file must be a menu, not a decoration.
 *
 * <p>Three menu files shipped with this plugin from the beginning, and nothing read one. Every slot,
 * material, title and lore was decided in Java, so an operator who opened the file, moved a slot and
 * restarted saw no change. The file even spelled placeholders, {@code {bank_level}} and
 * {@code {max_members}}, that existed nowhere in the code.
 *
 * <p>Nothing else in the build could see it. The files parsed as HOCON, the Java compiled, and every
 * test passed, because no test opened a file and compared it to a window.
 */
class ShippedMenuFilesAreReadTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    /** The live values IslandControlMenu binds when it opens island-main. */
    private static final Path CONTROL_MENU =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/menu/IslandControlMenu.java");

    private static final Pattern ARGUMENT = Pattern.compile("%argument_([a-z_]+)%");

    private static List<Path> menuFiles() throws IOException {
        try (Stream<Path> files = Files.list(MENUS)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".conf"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("Every shipped menu file parses as a menu the engine can open")
    void everyShippedFileParses() throws IOException {
        List<Path> files = menuFiles();
        assertThat(files).describedAs("the plugin ships menu files").isNotEmpty();

        MenuSpecLoader loader = new MenuSpecLoader();
        for (Path file : files) {
            assertThatCode(() -> loader.load(file))
                    .describedAs("%s must be a menu the engine can read", file.getFileName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Every shipped menu names a slot, a material and a click for every item")
    void everyShippedMenuIsComplete() throws IOException {
        for (Path file : menuFiles()) {
            assertEveryItemIsUsable(file, new MenuSpecLoader().load(file));
        }
    }

    private static void assertEveryItemIsUsable(Path file, MenuSpec spec) {
        assertThat(spec.items()).describedAs("items in %s", file.getFileName()).isNotEmpty();
        spec.items().forEach((id, item) -> {
            if (id.startsWith("__")) {
                return; // the engine's own fill item
            }
            assertThat(item.slots().slots())
                    .describedAs("item %s in %s must claim a slot", id, file.getFileName())
                    .isNotEmpty();
            assertThat(item.material())
                    .describedAs("item %s in %s must name a material", id, file.getFileName())
                    .isNotBlank();
            assertThat(item.click().hasAnyAction())
                    .describedAs("item %s in %s must do something when clicked", id, file.getFileName())
                    .isTrue();
        });
    }

    @Test
    @DisplayName("Every menu a shipped file opens is a shipped file")
    void everyOpenReachesAMenuThatExists() throws IOException {
        List<String> ids = new ArrayList<>();
        for (Path file : menuFiles()) {
            String name = file.getFileName().toString();
            ids.add(name.substring(0, name.length() - ".conf".length()));
        }

        Pattern opens = Pattern.compile("\"open:([a-z0-9-]+)\"");
        for (Path file : menuFiles()) {
            Matcher matcher = opens.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                assertThat(ids)
                        .describedAs(
                                "%s opens %s, which must be a menu that exists", file.getFileName(), matcher.group(1))
                        .contains(matcher.group(1));
            }
        }
    }

    @Test
    @DisplayName("Every shipped menu is unpacked next to the server")
    void everyShippedMenuIsUnpacked() throws IOException {
        String loader = Files.readString(
                Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/ConfigurationLoader.java"),
                StandardCharsets.UTF_8);

        for (Path file : menuFiles()) {
            assertThat(loader)
                    .describedAs(
                            "%s ships in the jar, so it must be written next to the server too", file.getFileName())
                    .contains("\"" + file.getFileName() + "\"");
        }
    }

    @Test
    @DisplayName("The main menu is the six rows it says it is")
    void theMainMenuKeepsItsShape() {
        assertThat(new MenuSpecLoader().load(MENUS.resolve("island-main.conf")).rows())
                .isEqualTo(6);
    }

    @Test
    @DisplayName("Every placeholder a shipped menu spells is one the code fills")
    void noMenuSpellsAPlaceholderNothingFills() throws IOException {
        String control = Files.readString(CONTROL_MENU, StandardCharsets.UTF_8);

        for (Path file : menuFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = ARGUMENT.matcher(source);
            while (matcher.find()) {
                String token = matcher.group(1);
                assertThat(control)
                        .describedAs("%s spells %%argument_%s%%, so the code must bind it", file.getFileName(), token)
                        .contains("\"" + token + "\"");
            }
        }
    }
}
