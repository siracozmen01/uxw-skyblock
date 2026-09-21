package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestServerTest {

    private static final String TEST_TOKEN = "secret-token-12345";
    private static final ServerNodeId NODE_ID = ServerNodeId.of("skyblock-test-01");

    private IslandStoragePort islandStoragePort;
    private IslandBankService bankService;
    private IslandLeaderboardService leaderboardService;
    private RestServer restServer;
    private HttpClient httpClient;
    private String baseUrl;

    private IslandId testIslandId;
    private ProfileId ownerProfileId;

    @BeforeEach
    void setUp() {
        islandStoragePort = mock(IslandStoragePort.class);
        bankService = mock(IslandBankService.class);
        leaderboardService = mock(IslandLeaderboardService.class);

        RestConfiguration config = new RestConfiguration(true, "127.0.0.1", 0, TEST_TOKEN);
        restServer = new RestServer(config, NODE_ID, islandStoragePort, bankService, leaderboardService);
        restServer.start();

        baseUrl = "http://127.0.0.1:" + restServer.port();
        httpClient = HttpClient.newHttpClient();

        testIslandId = IslandId.of(UUID.randomUUID());
        ownerProfileId = new ProfileId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        if (restServer != null) {
            restServer.close();
        }
    }

    @Test
    @DisplayName("GET /api/v1/health is public and returns 200 OK")
    void testHealthEndpointPublic() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/health"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("status").getAsString()).isEqualTo("UP");
        assertThat(json.get("nodeId").getAsString()).isEqualTo(NODE_ID.value());
    }

    @Test
    @DisplayName("Protected endpoint without Bearer token returns 401 Unauthorized")
    void testAuthMissingToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Protected endpoint with invalid Bearer token returns 401 Unauthorized")
    void testAuthInvalidToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value()))
                .header("Authorization", "Bearer wrong-token")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /api/v1/islands/{id} returns island metadata")
    void testGetIsland() throws Exception {
        PlayerUuid ownerPlayerUuid = PlayerUuid.of(UUID.randomUUID());
        Island island = Island.create(
                testIslandId,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                ownerPlayerUuid,
                ownerProfileId,
                Instant.now());
        when(islandStoragePort.findIslandById(testIslandId)).thenReturn(Optional.of(island));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value()))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("islandId").getAsString())
                .isEqualTo(testIslandId.value().toString());
        assertThat(json.get("ownerProfileId").getAsString())
                .isEqualTo(ownerProfileId.value().toString());
        assertThat(json.get("memberCount").getAsInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /api/v1/islands/{id}/members returns list of members")
    void testGetMembers() throws Exception {
        PlayerUuid ownerPlayerUuid = PlayerUuid.of(UUID.randomUUID());
        Island island = Island.create(
                testIslandId,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                ownerPlayerUuid,
                ownerProfileId,
                Instant.now());
        when(islandStoragePort.findIslandById(testIslandId)).thenReturn(Optional.of(island));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/members"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
        assertThat(arr.size()).isEqualTo(1);
        JsonObject member = arr.get(0).getAsJsonObject();
        assertThat(member.get("profileId").getAsString())
                .isEqualTo(ownerProfileId.value().toString());
        assertThat(member.get("role").getAsString()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("GET /api/v1/leaderboards/{metric} returns top entries")
    void testGetLeaderboards() throws Exception {
        LeaderboardEntry entry = new LeaderboardEntry(1, testIslandId, "Genesis", 9999L, "9,999");
        when(leaderboardService.getTop(LeaderboardCategory.LEVEL, 10)).thenReturn(List.of(entry));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/leaderboards/LEVEL?limit=10"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
        assertThat(arr.size()).isEqualTo(1);
        JsonObject item = arr.get(0).getAsJsonObject();
        assertThat(item.get("rank").getAsInt()).isEqualTo(1);
        assertThat(item.get("islandId").getAsString())
                .isEqualTo(testIslandId.value().toString());
        assertThat(item.get("islandName").getAsString()).isEqualTo("Genesis");
        assertThat(item.get("score").getAsLong()).isEqualTo(9999L);
        assertThat(item.get("formattedScore").getAsString()).isEqualTo("9,999");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit successfully deposits to bank with valid Idempotency-Key")
    void testBankDepositSuccess() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        IslandBank bank = new IslandBank(testIslandId, 5000L, 0L, 0L, 1L, Instant.now());
        BankTransaction tx = new BankTransaction(
                txId,
                UUID.randomUUID(),
                testIslandId,
                PlayerUuid.WEBSTORE.value(),
                "PRIMARY",
                2,
                5000L,
                5000L,
                "tebex_package_42",
                Instant.now());
        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(5000L),
                        eq("tebex_package_42"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));

        String body = "{\"amount\": 5000, \"reason\": \"tebex_package_42\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("status").getAsString()).isEqualTo("SUCCESS");
        assertThat(json.get("newBalanceMinorUnits").getAsLong()).isEqualTo(5000L);
        assertThat(json.get("transactionId").getAsString()).isEqualTo(txId.toString());
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit missing Idempotency-Key returns 400 Bad Request")
    void testBankDepositMissingIdempotencyKey() throws Exception {
        String body = "{\"amount\": 5000, \"reason\": \"tebex_package_42\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Missing required header: Idempotency-Key");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit invalid Idempotency-Key format returns 400 Bad Request")
    void testBankDepositInvalidIdempotencyKeyFormat() throws Exception {
        String body = "{\"amount\": 5000, \"reason\": \"tebex_package_42\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "not-a-uuid-or-ulid")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Invalid Idempotency-Key format: must be UUIDv4 or ULID");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with negative amount returns 400 Bad Request")
    void testBankDepositInvalidAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": -50}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Amount must be strictly positive");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with fractional amount returns 400 Bad Request")
    void testBankDepositFractionalAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": 12.5}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString())
                .contains("Amount must be an integer, fractional values are not allowed");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with string amount returns 400 Bad Request")
    void testBankDepositStringAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": \"5000\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Amount must be a numeric integer");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with boolean amount returns 400 Bad Request")
    void testBankDepositBooleanAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": true}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Amount must be a numeric integer");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with zero amount returns 400 Bad Request")
    void testBankDepositZeroAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": 0}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Amount must be strictly positive");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with out-of-range amount returns 400 Bad Request")
    void testBankDepositOutOfRangeAmount() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": 99999999999999999999999999999999}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Amount exceeds maximum supported value");
    }

    @Test
    @DisplayName(
            "POST /api/v1/islands/{id}/bank/deposit passes PlayerUuid.WEBSTORE as actor with REST_BANK_DEPOSIT scope")
    void testBankDepositPassesWebstoreActor() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        IslandBank bank = new IslandBank(testIslandId, 1000L, 0L, 0L, 1L, Instant.now());
        BankTransaction tx = new BankTransaction(
                txId,
                UUID.randomUUID(),
                testIslandId,
                PlayerUuid.WEBSTORE.value(),
                "PRIMARY",
                2,
                1000L,
                1000L,
                "webstore_credit",
                Instant.now());
        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(1000L),
                        eq("webstore_credit"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));

        String body = "{\"amount\": 1000, \"reason\": \"webstore_credit\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        verify(bankService)
                .depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(1000L),
                        eq("webstore_credit"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/islands/{id}/bank/deposit with Idempotency-Key replays cached response without re-executing")
    void testBankDepositIdempotencyKeyReplay() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        IslandBank bank = new IslandBank(testIslandId, 2500L, 0L, 0L, 1L, Instant.now());
        BankTransaction tx = new BankTransaction(
                txId,
                UUID.randomUUID(),
                testIslandId,
                PlayerUuid.WEBSTORE.value(),
                "PRIMARY",
                2,
                2500L,
                2500L,
                "tx-order-888",
                Instant.now());
        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(2500L),
                        eq("tx-order-888"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));

        String body = "{\"amount\": 2500, \"reason\": \"tx-order-888\"}";
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(200);
        JsonObject json1 = JsonParser.parseString(resp1.body()).getAsJsonObject();
        assertThat(json1.get("status").getAsString()).isEqualTo("SUCCESS");
        assertThat(json1.get("transactionId").getAsString()).isEqualTo(txId.toString());

        // Replay with identical key & body
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(200);
        JsonObject json2 = JsonParser.parseString(resp2.body()).getAsJsonObject();
        assertThat(json2.get("status").getAsString()).isEqualTo("SUCCESS");
        assertThat(json2.get("transactionId").getAsString()).isEqualTo(txId.toString());

        // Verify depositToIsland was called EXACTLY ONCE
        verify(bankService, times(1))
                .depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(2500L),
                        eq("tx-order-888"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT"));
    }

    @Test
    @DisplayName(
            "POST /api/v1/islands/{id}/bank/deposit with same Idempotency-Key but different payload returns 409 Conflict")
    void testBankDepositIdempotencyConflict() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        IslandBank bank = new IslandBank(testIslandId, 2500L, 0L, 0L, 1L, Instant.now());
        BankTransaction tx = new BankTransaction(
                txId,
                UUID.randomUUID(),
                testIslandId,
                PlayerUuid.WEBSTORE.value(),
                "PRIMARY",
                2,
                2500L,
                2500L,
                "first_reason",
                Instant.now());
        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(2500L),
                        eq("first_reason"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));

        String body1 = "{\"amount\": 2500, \"reason\": \"first_reason\"}";
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body1))
                .build();

        HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(200);

        // Different amount with the same idempotency key
        String body2 = "{\"amount\": 5000, \"reason\": \"first_reason\"}";
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body2))
                .build();

        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(409);
        JsonObject json2 = JsonParser.parseString(resp2.body()).getAsJsonObject();
        assertThat(json2.get("error").getAsString()).contains("Idempotency conflict");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit replaying from SQL processed_operations returns 200 OK")
    void testBankDepositReplayFromSqlProcessedOperations() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        String payload = String.format(
                "{\"status\":\"SUCCESS\",\"islandId\":\"%s\",\"newBalanceMinorUnits\":7777,\"transactionId\":\"%s\"}",
                testIslandId.value(), txId);

        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(7777L),
                        eq("sql_replay"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.DuplicateOperation(
                        UUID.randomUUID(), "Already processed", "APPLIED", "SUCCESS", payload));

        String body = "{\"amount\": 7777, \"reason\": \"sql_replay\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertThat(json.get("status").getAsString()).isEqualTo("SUCCESS");
        assertThat(json.get("newBalanceMinorUnits").getAsLong()).isEqualTo(7777L);
        assertThat(json.get("transactionId").getAsString()).isEqualTo(txId.toString());
    }

    @Test
    @DisplayName("The board answers on the documented path as well as the code's own")
    void testLeaderboardAnswersOnBothPaths() throws Exception {
        when(leaderboardService.getTop(LeaderboardCategory.LEVEL, 10))
                .thenReturn(List.of(new LeaderboardEntry(1, testIslandId, "Sky", 42L, "42")));

        for (String path : List.of("/api/v1/leaderboards/level", "/api/v1/top/levels", "/api/v1/top/level")) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + TEST_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).describedAs("path %s", path).isEqualTo(200);
            assertThat(response.body()).describedAs("path %s", path).contains("\"islandName\":\"Sky\"");
        }
    }

    @Test
    @DisplayName("A metric no board holds is still refused rather than guessed at")
    void testUnknownLeaderboardMetricIsRefused() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/top/reputation"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit invalid numeric amount string returns stable error message")
    void testBankDepositInvalidNumericAmountStableMessage() throws Exception {
        String idempKey = UUID.randomUUID().toString();
        String body = "{\"amount\": \"abc\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).isEqualTo("Amount must be a numeric integer");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit replaying from SQL with divergent amount returns 409 Conflict")
    void testBankDepositReplayFromSqlWithDivergentAmountReturnsConflict() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        JsonObject sqlPayload = new JsonObject();
        sqlPayload.addProperty("status", "SUCCESS");
        sqlPayload.addProperty("islandId", testIslandId.value().toString());
        sqlPayload.addProperty("actorId", PlayerUuid.WEBSTORE.toString());
        sqlPayload.addProperty("amount", 5000L);
        sqlPayload.addProperty("reason", "original_order");
        sqlPayload.addProperty("newBalanceMinorUnits", 5000L);
        sqlPayload.addProperty("transactionId", txId.toString());

        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(9999L),
                        eq("original_order"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.DuplicateOperation(
                        UUID.randomUUID(), "Already processed", "APPLIED", "SUCCESS", sqlPayload.toString()));

        String body = "{\"amount\": 9999, \"reason\": \"original_order\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(409);
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Idempotency conflict");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit replaying from SQL with divergent reason returns 409 Conflict")
    void testBankDepositReplayFromSqlWithDivergentReasonReturnsConflict() throws Exception {
        UUID txId = UUID.randomUUID();
        String idempKey = UUID.randomUUID().toString();
        JsonObject sqlPayload = new JsonObject();
        sqlPayload.addProperty("status", "SUCCESS");
        sqlPayload.addProperty("islandId", testIslandId.value().toString());
        sqlPayload.addProperty("actorId", PlayerUuid.WEBSTORE.toString());
        sqlPayload.addProperty("amount", 5000L);
        sqlPayload.addProperty("reason", "original_order");
        sqlPayload.addProperty("newBalanceMinorUnits", 5000L);
        sqlPayload.addProperty("transactionId", txId.toString());

        when(bankService.depositToIsland(
                        eq(testIslandId),
                        eq(PlayerUuid.WEBSTORE),
                        eq(5000L),
                        eq("different_order"),
                        eq(NODE_ID),
                        any(),
                        eq(idempKey),
                        eq("REST_BANK_DEPOSIT")))
                .thenReturn(new BankTransactionOutcome.DuplicateOperation(
                        UUID.randomUUID(), "Already processed", "APPLIED", "SUCCESS", sqlPayload.toString()));

        String body = "{\"amount\": 5000, \"reason\": \"different_order\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(409);
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertThat(json.get("error").getAsString()).contains("Idempotency conflict");
    }
}
