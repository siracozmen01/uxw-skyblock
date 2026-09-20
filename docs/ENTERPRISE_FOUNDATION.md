# Enterprise Foundation: API, REST, Commands, i18n & Permissions

Following the engineering benchmark established by `uxm-essentials`, this specification mandates an enterprise-grade developer surface, command architecture, REST API, localization, and permission registry.

---

## 1. Dual-Layer Public Developer API

To ensure third-party plugins, web dashboards, and internal modules interact with the Skyblock engine safely without leaking internal Bukkit state, the developer API is partitioned into two distinct tiers:

### 1.1 Pure Domain API (`:api`)
* **Zero Platform Dependencies:** Contains **no** references to `org.bukkit.*` or `io.papermc.*`.
* **Command-Query Separation (CQS):**
  - **Query Port (`UxmSkyblockQuery`):** Read-only operations returning immutable Java records and `CompletableFuture<Optional<IslandSnapshot>>`:
    ```java
    public interface UxmSkyblockQuery {
        CompletableFuture<Optional<IslandSnapshot>> getIsland(IslandId id);
        CompletableFuture<Optional<IslandSnapshot>> getPlayerIsland(UUID playerId);
        CompletableFuture<List<IslandLeaderboardEntry>> getTopIslands(int limit);
        CompletableFuture<Optional<IslandBankBalance>> getBankBalance(IslandId id);
    }
    ```
  - **Action Port (`UxmSkyblockActions`):** Mutating operations protected by sealed result types:
    ```java
    public interface UxmSkyblockActions {
        CompletableFuture<IslandResult<Island>> createIsland(UUID ownerId, String typeKey);
        CompletableFuture<IslandResult<IslandBankBalance>> depositBank(IslandId id, UUID actorId, BigDecimal amount);
        CompletableFuture<IslandResult<UpgradeTier>> purchaseUpgrade(IslandId id, UpgradeKey key);
    }
    ```
  - **Service Capability Model & Zero Runtime Exceptions:**
    - Optional subsystems are resolved via `services.find(Service.class) -> Optional<T>`.
    - If a subsystem is toggled off in `modules.conf`, `find()` returns `Optional.empty()` and direct action methods return `new IslandResult.FeatureUnavailable<>(featureKey)`.
    - Expected absence of a feature MUST NEVER throw runtime exceptions; callers handle absence safely via `Optional` or compiler-checked pattern matching.
* **Immutable Domain Records:** `IslandSnapshot`, `MemberSnapshot`, `IslandLevelScore`, `IslandBankBalance`.

### 1.2 Bukkit API Shell (`:bukkit-api`) [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
> [!NOTE]
> This module is planned for future phases to expose a legacy Bukkit-facing API shell and Paper lifecycle events. In the current phase, public APIs are provided platform-neutrally via `:api`.

* Exposes the Bukkit service entry point:
  ```java
  public final class UxmSkyblockApi {
      public static UxmSkyblock getInstance();
  }
  ```
* Publishes standard Paper lifecycle events (`IslandCreatedEvent`, `IslandDisbandedEvent`, `IslandLevelCalculatedEvent`, `IslandUpgradePurchasedEvent`).

---

## 2. Embedded REST API (`:rest-adapter`) [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
> [!NOTE]
> This module is planned for future external integration phases. It is NOT physically implemented in the current repository scaffold.

Modeled after `uxm-essentials-rest`, the plugin is designed to ship with a lightweight, embedded HTTP/WebSocket server for web leaderboards, Discord bots, and remote administrative panels.

### 2.1 Security & Authentication
* **Bearer Token Authentication (`AuthFilter`):** Granular scopes (`islands:read`, `islands:write`, `bank:admin`).
* **Sliding-Window Rate Limiting (`RateLimiter`):** Application-level request-rate and abuse mitigation. (Infrastructure-level DDoS protection remains the responsibility of external network firewalls and edge reverse proxies).

### 2.2 Core REST Endpoints
* `GET /api/v1/health` — Server TPS, active islands count, cache hit ratio.
* `GET /api/v1/islands/{id}` — Full metadata, boundaries, level, role hierarchy.
* `GET /api/v1/islands/{id}/members` — Current members, roles, join dates.
* `GET /api/v1/top/levels` — Real-time top 100 island levels.
* `GET /api/v1/top/worth` — Real-time top 100 island economic worth.
* `POST /api/v1/islands/{id}/bank/deposit` — Remote bank deposit for web stores.
* `WS /api/v1/events` — Real-time WebSocket event feed for live map overlays.

### 2.3 REST Economic Mutation Idempotency Contract
All mutating economic endpoints (such as `POST /api/v1/islands/{id}/bank/deposit`):
1. **Mandatory `Idempotency-Key` Header:** Callers MUST provide a unique UUIDv4/ULID `Idempotency-Key` HTTP header. Requests lacking this header are rejected with `400 Bad Request`.
2. **Model A Scoped Idempotency:** The REST adapter routes mutations through `processed_operations` with `operation_scope = 'REST_BANK_DEPOSIT'`, executing within the canonical atomic transaction boundary.
3. **Audit & Outbox Recording:** Successful mutations record `bank_transactions` audit logs and emit `outbox_events` within the exact same database commit.
4. **Deterministic Replay:** Concurrent or retry requests with the same key block on the unique index or return the cached HTTP 200/409 result without double-crediting.


---

## 3. Brigadier Command Engine (`uxmlib-command`)

Commands use Paper's native Brigadier engine through `uxmlib-command`:

* **Annotation DSL:** Commands declare clear `@Command`, `@Subcommand`, `@Arg`, `@Range`, and `@Cooldown` attributes:
  ```java
  @Command("island|is")
  public final class IslandCommand {
      @Subcommand("create")
      @Cooldown(seconds = 30)
      public void create(@Sender Player player, @Arg IslandType type) { ... }
  }
  ```
* **Configurable Command Aliases:** Command literals, aliases, and descriptions are read from `commands.conf` and can be disabled or renamed by operators without recompiling.
* **Auto-Generated Tab Completion:** Brigadier syntax tree guarantees zero runtime reflection overhead and instant client-side argument validation.

---

## 4. Adventure MiniMessage & Multi-Language i18n

* **Zero Legacy Formatting:** `§` and `&` codes are strictly prohibited.
* **Per-Player Client Locale Resolution:** Players receive messages according to their Minecraft client language setting, falling back to server default:
  - `messages/messages_en.conf` (English default)
  - `messages/messages_tr.conf` (Turkish catalog)
* **Semantic Theme Style Tokens:**
  - `<primary>`: Primary descriptive text.
  - `<secondary>`: Secondary details or values.
  - `<accent>`: Player names, island coordinates, level numbers.
  - `<success>`: Confirmation, level up, deposit success.
  - `<error>`: Denials, permission errors, cooldown timers.

---

## 5. Comprehensive Placeholder System

* **PlaceholderAPI & MiniPlaceholders Support:**
  - Soft-dependency integration registered past a plugin-present guard.
* **Standard Placeholder Matrix:**
  - `%uxmskyblock_has_island%` — `true`/`false`
  - `%uxmskyblock_island_id%` — Canonical UUID
  - `%uxmskyblock_island_level%` — Cached numerical level
  - `%uxmskyblock_island_worth%` — Formatted economy value
  - `%uxmskyblock_island_rank%` — Leaderboard position (#1, #2...)
  - `%uxmskyblock_island_role%` — Player role (Owner, Admin, Member, Visitor)
  - `%uxmskyblock_island_members%` — Active member count
  - `%uxmskyblock_island_bank%` — Formatted bank balance
  - `%uxmskyblock_island_size%` — Boundary diameter (e.g. 150x150)

---

## 6. Programmatic Permission Catalog (`CatalogPermissions`)

* Following `uxm-essentials`, all permission nodes are declared in a centralized `CatalogPermissions` enum.
* Every permission node is explicitly registered with Paper's `PluginManager` during startup with its description and default accessibility (`TRUE`, `FALSE`, `OP`), ensuring permission managers (like LuckPerms) display the complete tree with tab-completion without relying on stale text files.
