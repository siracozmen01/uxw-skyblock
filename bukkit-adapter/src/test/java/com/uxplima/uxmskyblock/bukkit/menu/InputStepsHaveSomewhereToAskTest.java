package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A menu that asks for a word must have somewhere to ask it.
 *
 * <p>An {@code input:} step needs the text prompt seam. An engine wired without one cannot prompt,
 * so it runs the step's cancel refs instead: the player clicks, nothing happens, and the only trace
 * is one line in the log. That is the quietest way a menu button can be broken, and four of them
 * were about to ship that way.
 *
 * <p>It is also what a Bedrock player needs. The seam sends them a native Cumulus form rather than
 * an anvil, which is what "Bedrock is the floor" means for a menu that wants a word typed.
 */
class InputStepsHaveSomewhereToAskTest {

    private static final Path MENUS = Path.of("src/main/resources/menus");

    private static final Path ENGINE =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/menu/SkyblockMenuEngine.java");

    /** An input step and the key its per-viewer prompt mode is looked up by. */
    private static final Pattern INPUT_STEP = Pattern.compile("do = \"input:([a-z.]+)\"");

    private static List<String> inputSteps() throws IOException {
        List<String> steps = new ArrayList<>();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                Matcher matcher = INPUT_STEP.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    steps.add(file.getFileName() + " :: " + matcher.group(1));
                }
            }
        }
        return steps;
    }

    @Test
    @DisplayName("The engine is wired with a text prompt, so an input step can ask")
    void theEngineCanAsk() throws IOException {
        assertThat(inputSteps())
                .describedAs("the shipped menus ask for a word somewhere")
                .isNotEmpty();

        String engine = Files.readString(ENGINE, StandardCharsets.UTF_8);
        assertThat(engine)
                .describedAs("without this the engine runs the cancel refs and the button does nothing")
                .contains("TextInputInstaller.install");
        assertThat(engine)
                .describedAs("the listener must be handed the prompt, not just built next to one")
                .contains("textInput.textInput()::promptResolved");
        assertThat(engine)
                .describedAs("a Bedrock player gets a native form through the resolved screen")
                .contains("BedrockScreen.forServer");
    }

    @Test
    @DisplayName("Every input step names a prompt, so the player is told what to type")
    void everyInputStepNamesAPrompt() throws IOException {
        MenuSpecLoader loader = new MenuSpecLoader();
        try (Stream<Path> files = Files.list(MENUS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".conf"))
                    .sorted()
                    .toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("do = \"input:")) {
                    continue;
                }
                // It parses as a menu, which is what the engine will do with it.
                assertThat(loader.load(file)).isNotNull();
                Matcher matcher = INPUT_STEP.matcher(source);
                while (matcher.find()) {
                    int stepAt = matcher.start();
                    int lineEnd = source.indexOf('\n', stepAt);
                    String step = source.substring(stepAt, lineEnd < 0 ? source.length() : lineEnd);
                    assertThat(step)
                            .describedAs("%s asks for a word without saying which word", file.getFileName())
                            .contains("prompt =");
                }
            }
        }
    }
}
