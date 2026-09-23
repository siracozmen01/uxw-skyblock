package com.uxplima.uxmskyblock.bukkit.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No join event handler asks for the player's profile.
 *
 * <p>A session is made off the join thread, after the join event, so at join a player has no
 * profile yet. Four handlers asked for one there and quietly did nothing: offline notices were never
 * read out, a member never heard their island's chat, a debt warning was mostly lost and an island's
 * boosters stayed paused. Work that needs the profile belongs on the session coordinator's
 * {@code whenSessionActive} hook.
 */
class NoJoinHandlerAsksForTheProfileTest {

    private static final Pattern JOIN_HANDLER =
            Pattern.compile("public void (\\w+)\\((?:org\\.bukkit\\.event\\.player\\.)?PlayerJoinEvent \\w+\\) \\{");
    private static final Pattern ASKS_FOR_A_PROFILE =
            Pattern.compile("activeProfile\\(|activeProfileProvider|findIslandIdForPlayer\\(|getActiveSession\\(");

    @Test
    @DisplayName("A join handler reads nothing that only a made session holds")
    void noJoinHandlerReadsTheSession() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file :
                    sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher handler = JOIN_HANDLER.matcher(source);
                while (handler.find()) {
                    String body = bodyFrom(source, handler.end());
                    if (ASKS_FOR_A_PROFILE.matcher(body).find()) {
                        offenders.add(file.getFileName() + "#" + handler.group(1));
                    }
                }
            }
        }
        assertThat(offenders)
                .describedAs("join handlers that ask for a profile")
                .isEmpty();
    }

    /** The method body that starts after its opening brace, up to the brace that closes it. */
    private static String bodyFrom(String source, int afterOpeningBrace) {
        int depth = 1;
        int at = afterOpeningBrace;
        while (at < source.length() && depth > 0) {
            char c = source.charAt(at);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            at++;
        }
        return source.substring(afterOpeningBrace, at);
    }
}
