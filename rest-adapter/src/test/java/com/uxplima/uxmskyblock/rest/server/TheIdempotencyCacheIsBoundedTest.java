package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import io.javalin.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A node remembers only so many deposits.
 *
 * <p>The idempotency key comes from the caller, and nothing ever removed one. A web store retrying,
 * or a caller holding the token sending fresh keys on purpose, grew the map for the life of the
 * process. It is bounded twice now: by how long a key is worth remembering, and by how many a node
 * will hold at once whatever the clock says.
 */
class TheIdempotencyCacheIsBoundedTest {

    private static RestServer serverWith(Duration retention, int capacity) {
        return new RestServer(
                new RestConfiguration(true, "127.0.0.1", 0, "a-token", retention, capacity),
                ServerNodeId.of("node-1"),
                mock(IslandStoragePort.class),
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                null);
    }

    private static RestServer.IdempotentDepositRecord anyDeposit() {
        return new RestServer.IdempotentDepositRecord(
                IslandId.of(UUID.randomUUID()), 100L, "web_store_deposit", HttpStatus.OK, Map.of());
    }

    @Test
    @DisplayName("A caller sending more keys than the ceiling does not grow the map past it")
    void theCeilingHolds() {
        RestServer server = serverWith(Duration.ofHours(24), 50);

        for (int i = 0; i < 500; i++) {
            server.rememberDepositForTest(UUID.randomUUID().toString(), anyDeposit());
        }

        assertThat(server.idempotencyEntries())
                .describedAs("deposits remembered after five hundred different keys")
                .isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("A key still inside the window is still answered")
    void aRecentKeyIsKept() {
        RestServer server = serverWith(Duration.ofHours(24), 50);
        String key = UUID.randomUUID().toString();

        server.rememberDepositForTest(key, anyDeposit());

        assertThat(server.idempotencyEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("A window of almost nothing drops what it remembered a moment ago")
    void anExpiredKeyIsDropped() throws Exception {
        RestServer server = serverWith(Duration.ofMillis(1), 50);

        server.rememberDepositForTest(UUID.randomUUID().toString(), anyDeposit());
        Thread.sleep(5);
        server.rememberDepositForTest(UUID.randomUUID().toString(), anyDeposit());

        assertThat(server.idempotencyEntries())
                .describedAs("only the one written after the window passed")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A configuration that remembers nothing at all is refused rather than accepted")
    void aWindowOfNothingIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RestConfiguration(true, "127.0.0.1", 0, "a-token", Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RestConfiguration(true, "127.0.0.1", 0, "a-token", Duration.ofHours(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
