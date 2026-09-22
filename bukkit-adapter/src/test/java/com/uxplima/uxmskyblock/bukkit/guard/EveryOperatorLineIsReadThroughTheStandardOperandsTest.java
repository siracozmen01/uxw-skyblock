package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An operator's line is read through the family's standard operands.
 *
 * <p>The effects context read every line with the identity resolver, so {@code {if=%player_level% >=
 * 10}} compared the text of the token and was never true, and nothing logged it.
 */
class EveryOperatorLineIsReadThroughTheStandardOperandsTest {

    private static final List<String> BUILDERS = List.of("ActionContext.builder(", "ConditionRequest.builder(");

    private static final List<String> BYPASSES = List.of(
            "builder(OperandResolver.identity())", "builder(OperandResolver.ofBiFunction(PlaceholderApi::apply))");

    private static final Pattern MCMMO_FIRST =
            Pattern.compile("\\n\\s+mcMMO:\\s*\\n\\s+load: BEFORE\\s*\\n\\s+required: false");

    @Test
    @DisplayName("Every context about a player reads an operator's line through Operands")
    void everyContextReadsThroughTheStandardOperands() throws IOException {
        List<String> bypassing = new ArrayList<>();
        for (Path file : productionSources()) {
            String source = Files.readString(file);
            if (BUILDERS.stream().noneMatch(source::contains) || !source.contains(".player(")) {
                continue;
            }
            for (String bypass : BYPASSES) {
                if (source.contains(bypass)) {
                    bypassing.add(file.getFileName() + " reads its lines with " + bypass);
                }
            }
            if (!source.contains("Operands.")) {
                bypassing.add(file.getFileName() + " builds a context and never reads through Operands");
            }
        }

        assertThat(bypassing)
                .describedAs("a level an operator names is never true in a context that skips Operands")
                .isEmpty();
    }

    @Test
    @DisplayName("The skill source is installed at enable and forgotten at disable")
    void theSkillSourceIsInstalledAndForgotten() throws IOException {
        String all = everySource();

        assertThat(all)
                .describedAs("uxmLib is relocated, so no other plugin installs the skill source for this one")
                .contains("Operands.readingSkills(");
        assertThat(all)
                .describedAs("a reload that keeps the old source holds the old server's plugins")
                .contains("Operands.forgetEverything(");
    }

    @Test
    @DisplayName("The server is asked to load mcMMO first")
    void mcMmoLoadsFirst() throws IOException {
        String descriptor = Files.readString(Path.of("src", "main", "resources", "paper-plugin.yml"));

        assertThat(MCMMO_FIRST.matcher(descriptor).find())
                .describedAs("a source installed before mcMMO enables finds no skill plugin;"
                        + " paper-plugin.yml needs mcMMO with load: BEFORE and required: false")
                .isTrue();
    }

    private static String everySource() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path file : productionSources()) {
            all.append(Files.readString(file)).append('\n');
        }
        return all.toString();
    }

    private static List<Path> productionSources() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            return sources.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
