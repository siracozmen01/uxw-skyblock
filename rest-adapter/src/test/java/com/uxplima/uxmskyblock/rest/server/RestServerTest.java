package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
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
        assertThat(json.get("islandId").getAsString()).isEqualTo(testIslandId.value().toString());
        assertThat(json.get("ownerProfileId").getAsString()).isEqualTo(ownerProfileId.value().toString());
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
        assertThat(member.get("profileId").getAsString()).isEqualTo(ownerProfileId.value().toString());
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
        assertThat(item.get("islandId").getAsString()).isEqualTo(testIslandId.value().toString());
        assertThat(item.get("islandName").getAsString()).isEqualTo("Genesis");
        assertThat(item.get("score").getAsLong()).isEqualTo(9999L);
        assertThat(item.get("formattedScore").getAsString()).isEqualTo("9,999");
    }

    @Test
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit successfully deposits to bank")
    void testBankDepositSuccess() throws Exception {
        UUID txId = UUID.randomUUID();
        IslandBank bank = new IslandBank(testIslandId, 5000L, 0L, 0L, 1L, Instant.now());
        BankTransaction tx = new BankTransaction(
                txId,
                UUID.randomUUID(),
                testIslandId,
                UUID.randomUUID(),
                "PRIMARY",
                2,
                5000L,
                5000L,
                "tebex_package_42",
                Instant.now()
        );
        when(bankService.depositToIsland(eq(testIslandId), any(), eq(5000L), eq("tebex_package_42"), eq(NODE_ID)))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));

        String body = "{\"amount\": 5000, \"reason\": \"tebex_package_42\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
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
    @DisplayName("POST /api/v1/islands/{id}/bank/deposit with negative amount returns 400 Bad Request")
    void testBankDepositInvalidAmount() throws Exception {
        String body = "{\"amount\": -50}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/islands/" + testIslandId.value() + "/bank/deposit"))
                .header("Authorization", "Bearer " + TEST_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }
}
