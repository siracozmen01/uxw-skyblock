package com.uxplima.uxmskyblock.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No rank is named in our code.
 *
 * <p>There is no vip branch, no premium tier and no pool the plugin knows the name of. A plugin
 * reads a number off whichever permission node the player holds, and the operator names the node. A
 * server with three ranks and a server with nine must be the same plugin.
 *
 * <p>The mechanism was already right: {@code HomeConfiguration} reads a map of permission node to
 * number that the operator writes. The vocabulary was not. A parameter called {@code vipTier} runs
 * through a correct mechanism and still tells the next reader that a rank called vip exists, which
 * is how the branch gets written.
 */
class NoRankIsNamedInCodeTest {

    /** Words that name a rank rather than describe a mechanism. */
    private static final Set<String> RANK_WORDS =
            Set.of("vip", "premium", "donator", "donor", "mvp", "elite", "platinum");

    /** A run of letters, which is as much of an identifier or a sentence as matters here. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    /** Where one camel case or underscore separated part of an identifier ends and the next begins. */
    private static final Pattern PARTS = Pattern.compile("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /** The source roots this guard reads, relative to the module it runs in. */
    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"),
            Path.of("../bukkit-adapter/src/main/java"),
            Path.of("../persistence-adapter/src/main/java"),
            Path.of("../rest-adapter/src/main/java"),
            Path.of("../api/src/main/java"));

    /**
     * A currency is not a rank. The bank holds a currency the docs call premium crystals, and its
     * javadoc says so; that is a name for money, not for a group of players.
     */
    private static final List<String> ALLOWED = List.of("crystal");

    /**
     * Whether a line names a rank.
     *
     * <p>A regex with word boundaries is the wrong test, because the way this rule actually gets
     * broken is a camel case identifier: {@code vipTier} and {@code isPremium} are each one word to
     * a regex and a named rank to a reader. So every run of letters is split the way a reader splits
     * it, on the case changes, and each part is compared whole. {@code equipment} and {@code vipers}
     * are one part each and neither is a rank; {@code vipTier} is two and the first one is.
     */
    private static boolean namesARank(String line) {
        Matcher words = WORD.matcher(line);
        while (words.find()) {
            for (String part : PARTS.split(words.group(), -1)) {
                if (RANK_WORDS.contains(part.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("No production source names a rank")
    void noProductionSourceNamesARank() throws IOException {
        List<String> offences = new ArrayList<>();
        int scanned = 0;

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scanned++;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (ALLOWED.stream()
                                .anyMatch(
                                        allowed -> line.toLowerCase(Locale.ROOT).contains(allowed))) {
                            continue;
                        }
                        if (namesARank(line)) {
                            offences.add(file + ":" + (i + 1) + "  " + line.strip());
                        }
                    }
                }
            }
        }

        assertThat(scanned).describedAs("the guard really read the source").isGreaterThan(100);
        assertThat(offences)
                .describedAs("a rank named in code is a branch waiting to be written; "
                        + "read the number off a permission node the operator names instead")
                .isEmpty();
    }
}
