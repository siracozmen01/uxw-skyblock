package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.uxplima.uxmlib.condition.action.ActionParser;
import com.uxplima.uxmlib.condition.action.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The header of {@code modules/effects.conf} is what an operator learns the grammar from.
 *
 * <p>A header that teaches a form the engine refuses is worse than none: the operator copies it, the
 * line is logged and skipped, and nothing in the file says why. Elsewhere in the family a header
 * taught options separated by spaces, which the engine reads as one broken option.
 */
class TheEffectsFileTeachesLinesThatParseTest {

    private static final Pattern EXAMPLE = Pattern.compile("^#\\s{3}(\\[[a-z-]+\\] \\{.*)$");
    private static final Pattern VERB = Pattern.compile("^#\\s{3}\\[([a-z-]+)\\]");

    @Test
    @DisplayName("Every example the header gives is a line the engine reads")
    void everyExampleParses() throws Exception {
        List<String> examples = new ArrayList<>();
        for (String line : header()) {
            Matcher example = EXAMPLE.matcher(line);
            if (example.matches()) {
                examples.add(example.group(1));
            }
        }

        assertThat(examples).describedAs("the header shows options in use").hasSizeGreaterThanOrEqualTo(3);
        for (String example : examples) {
            assertThat(ActionParser.parse(example).type()).describedAs(example).isNotNull();
        }
    }

    @Test
    @DisplayName("The header names every verb the engine offers")
    void everyVerbIsNamed() throws Exception {
        Set<String> named = new TreeSet<>();
        for (String line : header()) {
            Matcher verb = VERB.matcher(line);
            if (verb.find()) {
                named.add(verb.group(1));
            }
        }

        assertThat(named)
                .containsAll(Arrays.stream(ActionType.values())
                        .map(ActionType::prefix)
                        .toList());
    }

    private static List<String> header() throws Exception {
        try (InputStream in = Objects.requireNonNull(
                TheEffectsFileTeachesLinesThatParseTest.class
                        .getClassLoader()
                        .getResourceAsStream("modules/effects.conf"),
                "modules/effects.conf")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }
}
