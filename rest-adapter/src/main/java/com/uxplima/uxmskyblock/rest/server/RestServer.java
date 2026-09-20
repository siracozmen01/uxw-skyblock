package com.uxplima.uxmskyblock.rest.server;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinGson;
import org.jspecify.annotations.Nullable;

/**
 * Embedded lightweight REST server providing health, metrics, island queries,
 * bank operations, and leaderboard access (Section 2.44).
 */
public final class RestServer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RestServer.class.getName());

    private final RestConfiguration config;
    private final ServerNodeId serverNodeId;
    private final IslandStoragePort islandStoragePort;
    private final IslandBankService bankService;
    private final IslandLeaderboardService leaderboardService;
    private @Nullable Javalin app;

    public RestServer(
            RestConfiguration config,
            ServerNodeId serverNodeId,
            IslandStoragePort islandStoragePort,
            IslandBankService bankService,
            IslandLeaderboardService leaderboardService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.bankService = Objects.requireNonNull(bankService, "bankService must not be null");
        this.leaderboardService = Objects.requireNonNull(leaderboardService, "leaderboardService must not be null");
    }

    public synchronized void start() {
        if (app != null) {
            return;
        }

        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinGson());
        });

        // Bearer Token Authentication Filter
        app.before("/api/*", ctx -> {
            if (ctx.path().equals("/api/v1/health")) {
                return;
            }
            String authHeader = ctx.header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Missing or invalid Authorization header"));
                ctx.skipRemainingHandlers();
                return;
            }
            String token = authHeader.substring("Bearer ".length()).trim();
            if (!token.equals(config.bearerToken())) {
                ctx.status(HttpStatus.UNAUTHORIZED).json(Map.of("error", "Invalid bearer token"));
                ctx.skipRemainingHandlers();
            }
        });

        // 1. Health
        app.get("/api/v1/health", this::handleHealth);

        // 2. Island Details
        app.get("/api/v1/islands/{id}", this::handleGetIsland);

        // 3. Island Members
        app.get("/api/v1/islands/{id}/members", this::handleGetMembers);

        // 4. Leaderboards
        app.get("/api/v1/leaderboards/{metric}", this::handleGetLeaderboard);

        // 5. Bank Deposit (Tebex / CraftingStore Webstore)
        app.post("/api/v1/islands/{id}/bank/deposit", this::handleBankDeposit);

        app.start(config.host(), config.port());
        LOGGER.info(() -> "REST server started on " + config.host() + ":" + config.port());
    }

    private void handleHealth(Context ctx) {
        ctx.status(HttpStatus.OK)
                .json(Map.of(
                        "status", "UP",
                        "nodeId", serverNodeId.value(),
                        "timestamp", Instant.now().toString()));
    }

    private void handleGetIsland(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Island not found"));
            return;
        }

        Island island = optIsland.get();
        ctx.status(HttpStatus.OK)
                .json(Map.of(
                        "islandId", island.id().value().toString(),
                        "ownerProfileId", island.ownerProfileId().value().toString(),
                        "memberCount", island.members().size(),
                        "createdAt", island.createdAt().toString()));
    }

    private void handleGetMembers(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Island not found"));
            return;
        }

        Island island = optIsland.get();
        List<Map<String, Object>> memberList = island.members().entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "profileId", entry.getKey().value().toString(),
                        "role", entry.getValue().role().id(),
                        "joinedAt", entry.getValue().joinedAt().toString()))
                .toList();

        ctx.status(HttpStatus.OK).json(memberList);
    }

    private void handleGetLeaderboard(Context ctx) {
        String metric = ctx.pathParam("metric").toUpperCase(Locale.ROOT);
        LeaderboardCategory category;
        try {
            category = LeaderboardCategory.valueOf(metric);
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Unknown leaderboard metric: " + metric));
            return;
        }

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(10);
        List<LeaderboardEntry> entries = leaderboardService.getTop(category, limit);
        List<Map<String, Object>> response = entries.stream()
                .map(entry -> Map.<String, Object>of(
                        "rank", entry.rank(),
                        "islandId", entry.islandId().value().toString(),
                        "islandName", entry.islandName(),
                        "score", entry.score(),
                        "formattedScore", entry.formattedScore()))
                .toList();

        ctx.status(HttpStatus.OK).json(response);
    }

    private static final java.util.regex.Pattern UUID_V4_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final java.util.regex.Pattern ULID_PATTERN =
            java.util.regex.Pattern.compile("^[0123456789ABCDEFGHJKMNPQRSTVWXYZabcdefghjkmnpqrstvwxyz]{26}$");

    private static boolean isValidIdempotencyKey(String key) {
        if (key == null) {
            return false;
        }
        return UUID_V4_PATTERN.matcher(key).matches()
                || ULID_PATTERN.matcher(key).matches();
    }

    public record IdempotentDepositRecord(
            IslandId islandId, long amount, String reason, HttpStatus status, Map<String, Object> responseBody) {}

    private final java.util.concurrent.ConcurrentMap<String, IdempotentDepositRecord> idempotencyCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void handleBankDeposit(Context ctx) {
        String rawId = ctx.pathParam("id");
        IslandId islandId;
        try {
            islandId = IslandId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid island UUID format"));
            return;
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(ctx.body()).getAsJsonObject();
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Malformed JSON body"));
            return;
        }

        if (!body.has("amount")) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing required field: amount"));
            return;
        }

        var amountElem = body.get("amount");
        if (!amountElem.isJsonPrimitive() || !amountElem.getAsJsonPrimitive().isNumber()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount must be a numeric integer"));
            return;
        }

        long amount;
        try {
            java.math.BigDecimal bd = new java.math.BigDecimal(amountElem.getAsString());
            if (bd.scale() > 0 && bd.stripTrailingZeros().scale() > 0) {
                ctx.status(HttpStatus.BAD_REQUEST)
                        .json(Map.of("error", "Amount must be an integer, fractional values are not allowed"));
                return;
            }
            java.math.BigInteger bi = bd.toBigIntegerExact();
            if (bi.compareTo(java.math.BigInteger.ZERO) <= 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount must be strictly positive"));
                return;
            }
            if (bi.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Amount exceeds maximum supported value"));
                return;
            }
            amount = bi.longValueExact();
        } catch (Exception e) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Invalid numeric amount: " + e.getMessage()));
            return;
        }

        String reason = body.has("reason") ? body.get("reason").getAsString() : "web_store_deposit";

        String rawIdempotencyKey = ctx.header("Idempotency-Key");
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
            ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", "Missing required header: Idempotency-Key"));
            return;
        }

        String idempotencyKey = rawIdempotencyKey.trim();
        if (!isValidIdempotencyKey(idempotencyKey)) {
            ctx.status(HttpStatus.BAD_REQUEST)
                    .json(Map.of("error", "Invalid Idempotency-Key format: must be UUIDv4 or ULID"));
            return;
        }

        IdempotentDepositRecord cached = idempotencyCache.get(idempotencyKey);
        if (cached != null) {
            if (cached.islandId().equals(islandId)
                    && cached.amount() == amount
                    && Objects.equals(cached.reason(), reason)) {
                // Replay cached response
                ctx.status(cached.status()).json(cached.responseBody());
                return;
            } else {
                // Conflict detected
                ctx.status(HttpStatus.CONFLICT)
                        .json(Map.of(
                                "error",
                                "Idempotency conflict: request payload differs from original request for key "
                                        + idempotencyKey,
                                "idempotencyKey",
                                idempotencyKey));
                return;
            }
        }

        UUID operationId = UUID.randomUUID();
        BankTransactionOutcome outcome = bankService.depositToIsland(
                islandId,
                PlayerUuid.WEBSTORE,
                amount,
                reason,
                serverNodeId,
                operationId,
                idempotencyKey,
                "REST_BANK_DEPOSIT");

        if (outcome instanceof BankTransactionOutcome.Success success) {
            Map<String, Object> resp = Map.of(
                    "status", "SUCCESS",
                    "islandId", islandId.value().toString(),
                    "newBalanceMinorUnits", success.updatedBank().primaryBalanceMinorUnits(),
                    "transactionId", success.transaction().transactionId().toString());
            idempotencyCache.put(
                    idempotencyKey, new IdempotentDepositRecord(islandId, amount, reason, HttpStatus.OK, resp));
            ctx.status(HttpStatus.OK).json(resp);
        } else if (outcome instanceof BankTransactionOutcome.DuplicateOperation dup) {
            if ("APPLIED".equalsIgnoreCase(dup.status()) && dup.resultPayload() != null) {
                try {
                    JsonObject cachedJson =
                            JsonParser.parseString(dup.resultPayload()).getAsJsonObject();
                    if (cachedJson.has("islandId")
                            && cachedJson
                                    .get("islandId")
                                    .getAsString()
                                    .equals(islandId.value().toString())) {
                        Map<String, Object> resp = Map.of(
                                "status", "SUCCESS",
                                "islandId", islandId.value().toString(),
                                "newBalanceMinorUnits",
                                        cachedJson.get("newBalanceMinorUnits").getAsLong(),
                                "transactionId", cachedJson.get("transactionId").getAsString());
                        idempotencyCache.put(
                                idempotencyKey,
                                new IdempotentDepositRecord(islandId, amount, reason, HttpStatus.OK, resp));
                        ctx.status(HttpStatus.OK).json(resp);
                        return;
                    }
                } catch (Exception ignored) {
                    // Fallthrough to conflict
                }
            }
            if ("PENDING".equalsIgnoreCase(dup.status())) {
                ctx.status(HttpStatus.CONFLICT)
                        .json(Map.of(
                                "error",
                                "Operation in progress for idempotency key: " + idempotencyKey,
                                "idempotencyKey",
                                idempotencyKey));
                return;
            }
            ctx.status(HttpStatus.CONFLICT)
                    .json(Map.of("error", "Idempotency conflict: " + dup.message(), "idempotencyKey", idempotencyKey));
        } else {
            Map<String, Object> resp = Map.of(
                    "status", "FAILED",
                    "islandId", islandId.value().toString(),
                    "error", outcome.toString());
            idempotencyCache.put(
                    idempotencyKey,
                    new IdempotentDepositRecord(islandId, amount, reason, HttpStatus.UNPROCESSABLE_CONTENT, resp));
            ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(resp);
        }
    }

    public int port() {
        return app != null ? app.port() : config.port();
    }

    @Override
    public synchronized void close() {
        if (app != null) {
            app.stop();
            app = null;
        }
    }
}
