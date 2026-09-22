package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bearer token is compared in a time nobody can read.
 *
 * <p>{@code String.equals} stops at the first byte that differs, so how long a refusal takes says
 * how much of the token was right. An attacker who can time the answers walks the token out one byte
 * at a time, and this is the door to an endpoint that moves money into an island bank.
 *
 * <p>A test cannot measure that reliably on a shared machine, so this pins the two things it can:
 * that the comparison still answers correctly, including for the prefixes a byte-at-a-time attack
 * walks through, and that no comparison of the token by equality is left in the source.
 */
class TheBearerTokenIsNotComparedByteByByteTest {

    private static final String TOKEN = "a-real-token-nobody-should-guess";

    private static RestServer serverWith(String token) {
        return new RestServer(
                new RestConfiguration(true, "127.0.0.1", 0, token),
                ServerNodeId.of("node-1"),
                mock(IslandStoragePort.class),
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                null);
    }

    @Test
    @DisplayName("The right token is the only one accepted")
    void onlyTheRightTokenIsAccepted() {
        RestServer server = serverWith(TOKEN);

        assertThat(server.tokenMatches(TOKEN)).isTrue();
        assertThat(server.tokenMatches("")).isFalse();
        assertThat(server.tokenMatches(TOKEN + "x")).isFalse();
        assertThat(server.tokenMatches(TOKEN.toUpperCase(java.util.Locale.ROOT)))
                .describedAs("a token differing only in case")
                .isFalse();
    }

    @Test
    @DisplayName("Every prefix a byte-at-a-time attack would walk through is refused")
    void everyPrefixIsRefused() {
        RestServer server = serverWith(TOKEN);

        for (int length = 0; length < TOKEN.length(); length++) {
            String prefix = TOKEN.substring(0, length);
            assertThat(server.tokenMatches(prefix))
                    .describedAs("a token cut at %d characters", length)
                    .isFalse();
            assertThat(server.tokenMatches(prefix + "\u0000"))
                    .describedAs("that prefix with one wrong byte after it")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("No comparison of the token by equality is left in the source")
    void nothingComparesTheTokenByEquality() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/uxplima/uxmskyblock/rest/server/RestServer.java"), StandardCharsets.UTF_8);

        assertThat(source)
                .describedAs("equals stops at the first byte that differs, which is the leak")
                .doesNotContain(".equals(config.bearerToken())")
                .doesNotContain("config.bearerToken().equals(");
        assertThat(source)
                .describedAs("the comparison every caller goes through")
                .contains("MessageDigest.isEqual(");
    }
}
