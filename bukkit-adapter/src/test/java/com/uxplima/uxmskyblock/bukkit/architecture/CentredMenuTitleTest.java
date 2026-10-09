package com.uxplima.uxmskyblock.bukkit.architecture;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails when a menu window is titled without centring the title.
 *
 * <p>The client draws the title of a chest from a fixed origin and offers no alignment, so a title sits in
 * the middle only when leading spaces put it there. {@code MenuTitles.centre} holds that arithmetic, and
 * calling it is otherwise a habit: a menu that titles itself directly compiles, runs, and looks wrong. No
 * other guard can see the difference, because both spellings are valid Java.
 *
 * <p>A screen title is a different thing. {@code Title.title(...)} builds the text the client draws over the
 * world, and the client centres that itself, so this guard leaves it alone.
 */
final class CentredMenuTitleTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    private static final Pattern TITLE_CALL = Pattern.compile("\\.title\\(\\s*");

    private static final String CENTRE = "MenuTitles.centre(";

    @Test
    @DisplayName("every menu window is titled through MenuTitles.centre")
    void everyMenuTitleIsCentred() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path file :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                offenders.addAll(rawTitles(file));
            }
        }

        assertThat(offenders)
                .describedAs("Wrap each menu title in MenuTitles.centre, so the window reads from the middle.")
                .isEmpty();
    }

    @Test
    @DisplayName("the reader sees both spellings, so a clean pass means there are none rather than none seen")
    void thereaderSeesBothSpellings(@TempDir Path folder) throws IOException {
        // The test above passes when the scan finds nothing, and it finds nothing the day the title call
        // is spelled another way or the centring helper is renamed. A plugin whose windows are all
        // centred and a reader that recognises no title read the same.
        Path raw = Files.writeString(folder.resolve("Raw.java"), "spec.title(words.of(MenuKeys.TITLE));");
        Path centred = Files.writeString(
                folder.resolve("Centred.java"), "spec.title(MenuTitles.centre(words.of(MenuKeys.TITLE)));");

        assertThat(rawTitles(raw))
                .describedAs("a title set without the centring helper is the whole subject of this guard,"
                        + " so a reader that cannot see one guards nothing")
                .isNotEmpty();
        assertThat(rawTitles(centred))
                .describedAs("a title that is centred is not an offender")
                .isEmpty();
    }

    private static List<String> rawTitles(Path file) throws IOException {
        List<String> offenders = new ArrayList<>();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher call = TITLE_CALL.matcher(text);
        while (call.find()) {
            if (isAccessor(text, call.end()) || isScreenTitle(text, call.start())) {
                continue;
            }
            if (!text.startsWith(CENTRE, call.end())) {
                offenders.add(file.getFileName() + ", line " + lineOf(text, call.start()));
            }
        }
        return offenders;
    }

    /** Whether the call reads a title rather than sets one, as {@code feedback.title()} does. */
    private static boolean isAccessor(String text, int argument) {
        return argument < text.length() && text.charAt(argument) == ')';
    }

    /** Whether the receiver is Adventure {@code Title}, which the client centres on its own. */
    private static boolean isScreenTitle(String text, int dot) {
        int start = dot;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        return "Title".equals(text.substring(start, dot));
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int at = 0; at < index; at++) {
            if (text.charAt(at) == '\n') {
                line++;
            }
        }
        return line;
    }
}
