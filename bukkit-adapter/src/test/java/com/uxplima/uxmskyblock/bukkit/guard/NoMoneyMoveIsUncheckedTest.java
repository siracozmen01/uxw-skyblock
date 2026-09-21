package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every move of money is checked.
 *
 * <p>A deposit or a withdrawal answers whether it happened. Calling one as a statement and dropping
 * the answer is how money disappears quietly: the code carries on as though it worked, and the
 * player is told so.
 *
 * <p>Both directions of the wallet bridge had this. The deposit path discarded the wallet refund, so
 * a player could pay and get nothing. The withdraw path discarded the bank refund and told them
 * "island bank funds refunded" whether or not it had been: the money gone from the bank, never in
 * their wallet, and a line on screen saying otherwise. The first was found by reading, the second
 * only after this guard was written, which is the argument for the guard.
 */
class NoMoneyMoveIsUncheckedTest {

    private static final List<Path> ROOTS = List.of(Path.of("src/main/java"), Path.of("../core/src/main/java"));

    /**
     * A money move called as a bare statement: the call starts the statement and ends it.
     *
     * <p>Whether the call starts a statement is the whole question. An assignment broken over two
     * lines puts the call at the start of the second one, and a line based match calls that
     * unchecked: the first version of this guard reported two of them, both correct code, which is
     * the kind of noise that gets a guard deleted.
     */
    private static final Pattern MONEY_MOVE =
            Pattern.compile("\\w*(?:economyBridge|bankService|wallet|walletPort)\\.(?:deposit|withdraw)\\([^;]*\\);");

    @Test
    @DisplayName("No deposit or withdrawal has its answer thrown away")
    void everyMoveIsRead() throws IOException {
        TreeSet<String> unchecked = new TreeSet<>();
        int filesRead = 0;

        for (Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    filesRead++;
                    String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                    Matcher matcher = MONEY_MOVE.matcher(source);
                    while (matcher.find()) {
                        if (!startsAStatement(source, matcher.start())) {
                            continue;
                        }
                        int line = source.substring(0, matcher.start()).split("\n", -1).length;
                        unchecked.add(file.getFileName() + ":" + line + "  "
                                + matcher.group().strip());
                    }
                }
            }
        }

        assertThat(filesRead).describedAs("the guard really read the source").isGreaterThan(100);
        assertThat(unchecked)
                .describedAs("a move of money that nobody reads the answer to is how money disappears "
                        + "quietly, with the player told it worked")
                .isEmpty();
    }

    /**
     * The same source with every comment blanked out, character for character.
     *
     * <p>What comes before a call is how this guard decides whether somebody reads its answer, and a
     * comment is not code. A javadoc line ending in a full stop made the statement under it look
     * like a continuation, which is how the first version of this guard passed an unchecked refund
     * planted on purpose. Blanking rather than deleting keeps every offset, so a line number still
     * points at the line.
     */
    private static String withoutComments(String source) {
        char[] out = source.toCharArray();
        boolean inLine = false;
        boolean inBlock = false;
        boolean inText = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            char next = i + 1 < out.length ? out[i + 1] : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                } else {
                    out[i] = ' ';
                }
            } else if (inBlock) {
                boolean closing = c == '*' && next == '/';
                if (c != '\n') {
                    out[i] = ' ';
                }
                if (closing) {
                    out[i + 1] = ' ';
                    i++;
                    inBlock = false;
                }
            } else if (inText) {
                if (c == '"' && out[i - 1] != '\\') {
                    inText = false;
                }
            } else if (c == '/' && next == '/') {
                inLine = true;
                out[i] = ' ';
            } else if (c == '/' && next == '*') {
                inBlock = true;
                out[i] = ' ';
            } else if (c == '"') {
                inText = true;
            }
        }
        return new String(out);
    }

    /**
     * Whether the call at {@code at} begins its statement, rather than continuing one.
     *
     * <p>What comes before decides it: a semicolon or a brace means a new statement, and anything
     * else, an {@code =} or a {@code return} or an open parenthesis, means somebody is reading the
     * answer.
     */
    private static boolean startsAStatement(String source, int at) {
        for (int i = at - 1; i >= 0; i--) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == ';' || c == '{' || c == '}';
        }
        return true;
    }

    @Test
    @DisplayName("The scan can still fail, so a clean result means something")
    void theScanCanStillFail() {
        String dropped = "void f() { bankService.deposit(profileId, uuid, amount, nodeId); }";
        Matcher matcher = MONEY_MOVE.matcher(dropped);
        assertThat(matcher.find()).isTrue();
        assertThat(startsAStatement(dropped, matcher.start()))
                .describedAs("a bare call is an unchecked move")
                .isTrue();
    }

    @Test
    @DisplayName("A comment above a call does not make it look like a continuation")
    void aCommentDoesNotHideAnUncheckedMove() {
        String commented = "        // the refund can fail too.\n"
                + "        bankService.deposit(profileId, uuid, amount, nodeId);";
        String blanked = withoutComments(commented);
        Matcher matcher = MONEY_MOVE.matcher(blanked);
        assertThat(matcher.find()).isTrue();
        assertThat(startsAStatement(blanked, matcher.start()))
                .describedAs("the full stop belongs to a comment, not to a statement")
                .isTrue();
    }

    @Test
    @DisplayName("An answer read across two lines is not an unchecked move")
    void aWrappedAssignmentIsChecked() {
        String wrapped = "        BankTransactionOutcome refund =\n"
                + "                bankService.deposit(profileId, uuid, amount, nodeId);";
        Matcher matcher = MONEY_MOVE.matcher(wrapped);
        assertThat(matcher.find()).isTrue();
        assertThat(startsAStatement(wrapped, matcher.start()))
                .describedAs("the call continues an assignment, so its answer is read")
                .isFalse();
    }
}
