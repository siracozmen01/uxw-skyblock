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
 * Whoever asks a profile's ruleset is told the real one.
 *
 * <p>Two listeners and a command ask which ruleset a profile plays under, and the answer decides
 * whether the Ironman barrier refuses. Nothing set the provider, so every profile read as CLASSIC
 * and the barrier could not refuse anything: an invariant the documents state plainly was inert
 * because one setter had no caller.
 *
 * <p>Every place that offers a setter for it has to be handed one, and nothing may go back to
 * naming a ruleset in the code where a profile's own is what matters.
 */
class TheRulesetReachesEveryCheckTest {

    private static final Path MAIN = Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit");

    @Test
    @DisplayName("Every listener that can be told a profile's ruleset is told one")
    void everylistenerIsTold() throws IOException {
        String wiring = readAll(MAIN.resolve("bootstrap"));
        TreeSet<String> untold = new TreeSet<>();

        for (String listener : List.of("protectionListener", "categoricalInteractablesListener")) {
            if (!wiring.contains(listener + ".setProfileTypeProvider(")) {
                untold.add(listener);
            }
        }

        assertThat(untold)
                .describedAs("a check told CLASSIC for everybody is a barrier that cannot refuse anything")
                .isEmpty();
    }

    @Test
    @DisplayName("No command names a ruleset where the profile's own is what decides")
    void nocommandNamesARuleset() throws IOException {
        String commands = readAll(MAIN.resolve("command"));

        assertThat(commands)
                .describedAs("the grantee's ruleset decides whether a grant crosses a boundary")
                .doesNotContain("ProfileType.CLASSIC,");
    }

    private static String readAll(Path root) throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }
}
