# Persistence Architecture & Relational Database Specification

**Document Version:** 1.3.0
**Date:** 2026-09-13
**Status:** FROZEN BY PRODUCT OWNER — 2026-09-13
**Platform Target:** SQLite, MySQL 8.0+, MariaDB 10.6+, PostgreSQL 15+

---

## 1. Overview & Architectural Policy

In adherence to the core design specification ([`docs/superpowers/specs/2026-09-12-skyblock-core-design.md`](superpowers/specs/2026-09-12-skyblock-core-design.md)), the persistence tier is governed by four non-negotiable architectural invariants:

1. **SQL as Sole Canonical Source of Truth:**
   All persistent state (identities, memberships, financial balances, profiles, upgrades, boundaries, vault contents) is canonically persisted in SQL databases. Redis operates strictly as an ephemeral L2 cache, cluster routing directory, and event transport bus.
2. **Authority Epoch Fencing & Optimistic Concurrency Control (OCC / CAS):**
   To resolve stale-owner hazards across distributed nodes (e.g. node GC pauses or network partitions), every state mutation must verify both `version` and `authority_epoch`.
   *Invariant:* **A stale authority holder MUST NEVER be able to commit a mutation after a newer authority epoch has been issued.**
3. **Atomic Transactional Operation Boundary (`processed_operations`):**
   Idempotency reservation, critical state mutation, audit trail recording, and outbox event emission MUST execute within the exact same atomic SQL transaction boundary (`BEGIN ... COMMIT`).
4. **Transactional Outbox & Consumer Inbox Deduplication:**
   Domain events are written to `outbox_events` locally within the business transaction. In clustered environments, events are streamed to consumers; consuming nodes maintain a `consumer_inbox` table to enforce guaranteed idempotent handling over at-least-once deliveries.
5. **Dependency Ownership & Upstream Capability Gaps:**
   Foundational infrastructure is centrally owned by `uxm-lib` 0.88.0 (`uxmlib-redis` provides binary `byte[]` Pub/Sub only). Direct Lettuce calls in Skyblock are strictly prohibited. The persistence tier provides complete V1 canonical SQL and local fallbacks for all tracked capability gaps:
   - **`UXM-LIB CAPABILITY GAP — DURABLE REDIS STREAMS`:** Required for cross-node outbox streaming. **SQL Fallback:** Local transactional polling on `outbox_events` (`WHERE status = 'PENDING' ORDER BY created_at ASC`). *Classification:* `BLOCKING BEFORE PHASE 1 CLUSTER IMPLEMENTATION` (Non-blocking for single-node development).
   - **`UXM-LIB CAPABILITY GAP — REDIS KV/TTL ROUTING DIRECTORY`:** Required for low-latency proxy routing cache (`uxmskyblock:route:island:<id>`). **SQL Fallback:** Authoritative SQL queries against canonical `island_authorities` and `player_sessions` tables. *Classification:* `BLOCKING BEFORE MULTI-NODE PROXY ROUTING` (Non-blocking for single-node development).
   - **`UXM-LIB CAPABILITY GAP — REDIS SORTED-SET LEADERBOARD`:** Required for real-time $O(\log N)$ leaderboards (`ZADD`, `ZREVRANGE`). **SQL Fallback:** Relational index scan (`ORDER BY level_score DESC LIMIT :k`) with local Caffeine caching in `:persistence-adapter`. *Classification:* `OPTIONAL / NON-BLOCKING`.
   - **`UXM-LIB CAPABILITY GAP — METRICS`:** Required for bStats/telemetry. Direct dependency removed. **Fallback:** Standard SLF4J audit logging and JVM MBeans. *Classification:* `OPTIONAL / NON-BLOCKING`.
   - **`UXM-LIB CAPABILITY GAP — GENERAL OBJECT STORAGE / S3-COMPATIBLE STORAGE`:** Required for general object storage abstraction across world snapshots, database backup exports, reports, and durable binary artifacts with S3-compatible integration (AWS S3 & Cloudflare R2). **Fallback:** Local filesystem storage adapter (`LocalFilesystemStorageAdapter`). *Classification:* `BLOCKING BEFORE OBJECT-STORAGE IMPLEMENTATION` (ultimately required for V1 completion).
   - *Distributed Correctness Invariant:* SQL authority leasing, OCC, and fencing provide complete distributed safety independently of Redis. Redis is never promoted to canonical authority.

---

## 2. Core Relational Schema (DDL)

The schema is maintained through automated, versioned SQL migration scripts (`V1__initial_schema.sql`, etc.) executed via `MigrationRunner`.

### 2.1 Island Core Entity (`islands`)
```sql
CREATE TABLE islands (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_profile_id VARCHAR(36) NOT NULL,
    owner_account_uuid VARCHAR(36) NOT NULL, -- Retained for player lookup, audit, and offline display
    custom_name VARCHAR(32) NULL,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    economic_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
    administrative_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
    freeze_reason VARCHAR(255) NULL,
    level_score BIGINT NOT NULL DEFAULT 0,
    net_worth_minor_units BIGINT NOT NULL DEFAULT 0, -- Canonical exact minor units; scale defined by currency metadata (BIGINT or declared DECIMAL(18, scale); SQLite REAL strictly BANNED)
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_islands_owner_profile ON islands (owner_profile_id);
CREATE INDEX idx_islands_owner_account ON islands (owner_account_uuid);
CREATE INDEX idx_islands_lifecycle ON islands (lifecycle);
CREATE INDEX idx_islands_states ON islands (economic_state, administrative_state);
CREATE INDEX idx_islands_level ON islands (level_score DESC);
```

### 2.2 Distributed Authority & Fencing Leases (`island_authorities`)
```sql
CREATE TABLE island_authorities (
    island_id VARCHAR(36) NOT NULL PRIMARY KEY,
    authoritative_node VARCHAR(64) NOT NULL,
    authority_epoch BIGINT NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMP NOT NULL,
    last_heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_island_authorities_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE INDEX idx_island_authorities_lease ON island_authorities (lease_expires_at ASC);
CREATE INDEX idx_island_authorities_node ON island_authorities (authoritative_node);
```

#### Authority Lifecycle Contracts (ACQUIRE, TAKEOVER, RENEW)
* **Invariant 1 (Monotonic Advance):** *Authority epoch advances only when authority ownership is newly acquired or transferred. Lease renewal by the current valid owner never advances the fencing epoch.*
* **Invariant 2 (Canonical Row Lock Serialization):** *Any authoritative mutation and any authority takeover MUST serialize on the same canonical `island_authorities` row via `WHERE island_id = :id FOR UPDATE` (without filtering by node or timestamp in SQL). Application-level validation inside the transaction verifies node, epoch, and lease validity.*
* **Invariant 3 (Authority Transaction Validity Semantics):** *Lease expiration prevents NEW authoritative transactions from acquiring or initiating mutations. It does NOT revoke an already-authorized transaction that was valid when the canonical authority row was locked and still exclusively holds that authority row lock within its operational duration budget. Any concurrent takeover on that island blocks on the canonical row lock until the transaction commits or rolls back.*
* **Canonical Clock:** Database clock (`CURRENT_TIMESTAMP`) is the sole reference clock for all lease expiration comparisons.
* **Operational Transaction Duration Enforcement (`max-authoritative-transaction-duration`):**
  - **Statement Timeout:** Explicitly enforced via connection session settings (`SET statement_timeout = 2000;` in PostgreSQL; read-only `SET max_execution_time = 2000;` in MySQL, with DML statement deadlines enforced via JDBC query timeout `Statement.setQueryTimeout`).
  - **Lock Acquisition Timeout (Waiter Deadline):** Enforced via `SET lock_timeout = 1000;` (PostgreSQL) / `SET innodb_lock_wait_timeout = 1;` (MySQL).
    *Critical Semantic Rule:* **`lock_timeout` applies strictly to WAITING statements attempting to acquire a lock. It NEVER terminates, cancels, or rolls back an active transaction that already OWNS a lock.**
  - **Orphaned Lock Retention & Node Crash Severance Protocol:**
    - If a node crashes ungracefully (e.g. JVM `SIGKILL`, host kernel panic, power loss, or severed network cord) while holding an exclusive row lock on `island_authorities`:
      1. The database engine retains the exclusive row lock until the underlying client connection/socket is terminated.
      2. The connection is severed exclusively via engine-level detection mechanisms:
         - **Dialect / OS TCP Keepalive:** Operating system and database keepalives detect broken sockets (`keepalives_idle = 2`, `keepalives_interval = 1`, `keepalives_count = 3` in PostgreSQL; OS-level keepalive in Linux/Windows).
         - **Driver Socket Timeout:** HikariCP JDBC `socketTimeout = 5000` terminates dead socket reads.
         - **Database Server Idle Transaction Timeout:** Server-side timeouts actively abort abandoned transactions (`idle_in_transaction_session_timeout = 5000` in PostgreSQL, `interactive_timeout = 10` / `wait_timeout = 10` in MySQL).
      3. When the engine terminates the dead connection, the uncommitted transaction is rolled back by the database, releasing the canonical row lock.
    - **Fail-Closed Waiter Behavior During Crash Window:**
      - Any concurrent node attempting an authority takeover or mutation while the orphaned lock is retained will wait up to its own `lock_timeout` (e.g. 1000ms).
      - When `lock_timeout` expires without acquiring the lock, the statement fails with a lock wait timeout exception. The takeover transaction rolls back and enters a fail-closed exponential backoff retry.
      - *Safety Invariant:* **Waiters MUST FAIL CLOSED. Under no circumstance may a takeover attempt bypass the canonical row lock or assume ownership while an orphaned lock is retained.**
  - **Application Deadline & Driver Timeout:** HikariCP / virtual thread monitor enforces a strict 2-second ceiling, cancelling connections that exceed the boundary.
  - **Preemptive Safety Check:** If `lease_expires_at - DB_CURRENT_TIME < 1000ms`, mutations reject before execution (`AUTHORITY_LEASE_ABOUT_TO_EXPIRE`).
  - **Strict Architectural Ban:** NO HTTP calls, NO Redis network roundtrips, NO filesystem I/O, NO S3 uploads, and NO schematic operations are permitted inside the authoritative SQL transaction.
* **Multi-Island Deadlock Ordering Protocol:**
  - Operations locking multiple islands (e.g. cross-island bank transfers or co-op merges) MUST acquire row locks in strict order of `island_id`:
    1. **Exact Comparator Contract:** Sort involved `IslandId`s by **UUID Unsigned 128-Bit Binary Order** (comparing `Long.compareUnsigned(mostSigBits)` followed by `Long.compareUnsigned(leastSigBits)`).
    2. Lock `island_authorities` rows in sorted order (`WHERE island_id = id_A FOR UPDATE`, then `id_B`).
    3. Lock business aggregate rows (`island_banks`, `islands`) in that exact same sorted order.
  - **Deadlock Hierarchy Guarantee:** This ordering protocol is *specifically designed to eliminate lock inversion cycles among UXM transactions following the hierarchy*.
  - On SQL deadlock detection (error code `40P01` in PG, `1213` in MySQL) triggered by external tools or anomalous interleaving, the transaction immediately rolls back and retries with bounded exponential backoff and jitter (max 3 retries, $10\text{ms} \dots 50\text{ms}$).

1. **ACQUIRE (Unowned / Fresh Island):**
   ```sql
   -- Logical Contract
   INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at, updated_at)
   VALUES (:islandId, :nodeId, 1, :leaseExpiresAt, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
   ```

2. **TAKEOVER (Lease Expired on Prior Node):**
   ```sql
   -- Logical Contract: Serializes on canonical row lock
   UPDATE island_authorities
   SET authoritative_node = :newNodeId,
       authority_epoch = authority_epoch + 1,
       lease_expires_at = :leaseExpiresAt,
       last_heartbeat_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
   WHERE island_id = :islandId
     AND authority_epoch = :expectedAuthorityEpoch
     AND lease_expires_at < CURRENT_TIMESTAMP;
   -- NOTE: Targets canonical row; blocks until any active mutation transaction holding FOR UPDATE commits or rolls back!
   ```

3. **RENEW (Heartbeat by Current Active Owner):**
   ```sql
   -- Logical Contract
   UPDATE island_authorities
   SET lease_expires_at = :leaseExpiresAt,
       last_heartbeat_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
   WHERE island_id = :islandId
     AND authoritative_node = :currentNodeId
     AND authority_epoch = :currentAuthorityEpoch
     AND lease_expires_at >= CURRENT_TIMESTAMP;
   -- NOTE: authority_epoch is NOT incremented during heartbeat renewal!
   ```

### 2.3 Grid Coordinates & Bounds (`island_locations`)
```sql
CREATE TABLE island_locations (
    island_id VARCHAR(36) NOT NULL PRIMARY KEY,
    world_name VARCHAR(64) NOT NULL,
    center_x INT NOT NULL,
    center_z INT NOT NULL,
    min_x INT NOT NULL,
    min_z INT NOT NULL,
    max_x INT NOT NULL,
    max_z INT NOT NULL,
    spawn_x DOUBLE NOT NULL,
    spawn_y DOUBLE NOT NULL,
    spawn_z DOUBLE NOT NULL,
    spawn_yaw FLOAT NOT NULL DEFAULT 0.0,
    spawn_pitch FLOAT NOT NULL DEFAULT 0.0,
    CONSTRAINT fk_island_locations_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE INDEX idx_island_locations_coords ON island_locations (world_name, center_x, center_z);
```

### 2.4 Multi-Profile & Player Session Authority Subsystem (`player_accounts`, `player_profiles`, `player_sessions`, `profile_inventories`, `inventory_mutation_journals`, `profile_switch_operations`)
```sql
CREATE TABLE player_accounts (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    active_profile_id VARCHAR(36) NULL, -- Nullable initially to permit clean non-circular initial profile creation lifecycle
    active_switch_operation_id VARCHAR(36) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Enforces ownership: active profile must belong to the exact same player_uuid
    CONSTRAINT fk_player_accounts_active_profile FOREIGN KEY (player_uuid, active_profile_id)
        REFERENCES player_profiles (player_uuid, profile_id)
);

CREATE TABLE player_profiles (
    profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL,
    profile_type VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_player_profiles_account FOREIGN KEY (player_uuid)
        REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
    CONSTRAINT uq_player_profiles_ownership UNIQUE (player_uuid, profile_id)
);

CREATE INDEX idx_player_profiles_player ON player_profiles (player_uuid);

-- Distributed Player Session Authority (Single-Writer Invariant per Player)
CREATE TABLE player_sessions (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    active_profile_id VARCHAR(36) NOT NULL,
    authoritative_node VARCHAR(64) NOT NULL,
    session_epoch BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, DRAINING, HANDOFF_READY, RECOVERING, OFFLINE, LOCAL_FENCED
    handoff_id VARCHAR(36) NULL,
    handoff_target_node VARCHAR(64) NULL,
    handoff_expires_at TIMESTAMP NULL,
    last_durable_inventory_version BIGINT NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_player_sessions_account FOREIGN KEY (player_uuid)
        REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
    -- Enforces ownership: session active profile must belong to the same player_uuid
    CONSTRAINT fk_player_sessions_active_profile FOREIGN KEY (player_uuid, active_profile_id)
        REFERENCES player_profiles (player_uuid, profile_id)
);

CREATE INDEX idx_player_sessions_state ON player_sessions (state, lease_expires_at);

-- Canonical Durable Player Inventory & State Store
CREATE TABLE profile_inventories (
    profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
    profile_inventory_version BIGINT NOT NULL DEFAULT 1, -- Monotonic durable version surviving server restarts
    inventory_nbt BLOB NOT NULL,
    enderchest_nbt BLOB NOT NULL,
    experience_points INT NOT NULL DEFAULT 0,
    health DOUBLE NOT NULL DEFAULT 20.0,
    food_level INT NOT NULL DEFAULT 20,
    saturation FLOAT NOT NULL DEFAULT 5.0,
    active_potion_effects_nbt BLOB NULL,
    logout_world VARCHAR(64) NULL,
    logout_x DOUBLE NULL,
    logout_y DOUBLE NULL,
    logout_z DOUBLE NULL,
    gamemode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',
    flight_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_inventories_profile FOREIGN KEY (profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

-- Universal Generic Write-Ahead Inventory Mutation Journal (Header: Supports 1..N Compound Participants)
CREATE TABLE inventory_mutation_journals (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    operation_type VARCHAR(64) NOT NULL, -- VAULT_TRANSFER, PLAYER_TRADE, VESSEL_TRANSFER, AUCTION_CLAIM, SHOP_PURCHASE
    state VARCHAR(24) NOT NULL DEFAULT 'INTENT', -- INTENT, APPLYING, APPLIED, COMMITTED, ABORTED, RECOVERY_REQUIRED
    participant_count INT NOT NULL DEFAULT 1,
    payload JSON NOT NULL, -- Structured operation-level metadata
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inv_journal_state ON inventory_mutation_journals (state, expires_at);

-- Normalized Generic Journal Participants (Tracks individual inventory owners & durable apply evidence)
CREATE TABLE inventory_mutation_participants (
    operation_id VARCHAR(36) NOT NULL,
    participant_index INT NOT NULL,
    inventory_type VARCHAR(32) NOT NULL, -- PLAYER_INVENTORY, ISLAND_VAULT, VESSEL_CARGO, CONTAINER
    owner_root_type VARCHAR(32) NOT NULL, -- PROFILE, ISLAND, VESSEL, COURSE, CLAIM
    owner_root_id VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    authority_type VARCHAR(32) NOT NULL, -- PLAYER_SESSION, ISLAND, GAME_MODE_INSTANCE
    authority_id VARCHAR(64) NOT NULL,
    authority_epoch BIGINT NOT NULL,
    before_fingerprint VARCHAR(64) NOT NULL,
    after_fingerprint VARCHAR(64) NOT NULL,
    durable_apply_state VARCHAR(24) NOT NULL DEFAULT 'PENDING', -- PENDING, APPLIED, REVERTED
    mutation_delta_payload JSON NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, participant_index),
    CONSTRAINT fk_inv_participant_journal FOREIGN KEY (operation_id)
        REFERENCES inventory_mutation_journals (operation_id) ON DELETE CASCADE
);

CREATE INDEX idx_inv_participant_lookup ON inventory_mutation_participants (owner_root_type, owner_root_id, durable_apply_state);

CREATE TABLE profile_switch_operations (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    player_uuid VARCHAR(36) NOT NULL,
    from_profile_id VARCHAR(36) NOT NULL,
    to_profile_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
    source_snapshot_blob BLOB NULL,
    target_snapshot_blob BLOB NULL,
    failure_reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_switch_player FOREIGN KEY (player_uuid)
        REFERENCES player_accounts (player_uuid) ON DELETE CASCADE
);

CREATE INDEX idx_profile_switch_player ON profile_switch_operations (player_uuid, state);
```

* **Active Profile Single-Truth & Creation Lifecycle Contract:**
  1. **Canonical Active Authority (`player_sessions.active_profile_id`):** When a player is online on a backend node, `player_sessions.active_profile_id` is the sole canonical authority for active profile identity, locked via `SELECT ... FOR UPDATE` and fenced by `session_epoch` and `lease_expires_at`.
  2. **Account Default Profile (`player_accounts.active_profile_id`):** Serves strictly as the offline persistent default profile loaded upon initial login.
  3. **Non-Circular Creation Lifecycle:**
     - Step 1: Insert `player_accounts` with `active_profile_id = NULL`.
     - Step 2: Insert initial `player_profiles` row referencing `player_uuid`.
     - Step 3: Update `player_accounts.active_profile_id` to the newly created `profile_id`.
     - Step 4: When a session is established, insert/update `player_sessions` pointing `active_profile_id` to that profile.
  4. **Referential & Ownership Invariant:**
     - **Ownership Invariant:** An active profile reference (`active_profile_id`) in `player_accounts` or `player_sessions` MUST satisfy both: (a) the referenced profile exists, and (b) `player_profiles.player_uuid == account/session.player_uuid`. An account or session can NEVER point to a profile owned by another player.
     - **Relational Enforcement:** Modeled portably via composite foreign keys `(player_uuid, active_profile_id) REFERENCES player_profiles (player_uuid, profile_id)` backed by `UNIQUE (player_uuid, profile_id)`, complemented by strict application-layer transaction validation across all database dialects.
     - **Deletion Lifecycle Invariant:** Deletion of a profile that is currently referenced as `active_profile_id` by `player_accounts` or an active `player_sessions` row is strictly rejected (`CANNOT_DELETE_ACTIVE_PROFILE`), or must atomically repoint `active_profile_id` to another valid profile owned by that player within the same transaction prior to row deletion. Dangling references are prohibited.
     - Generic `player_profiles` contains **zero hardcoded `island_id` or `is_active` columns**.

* **Generic Compound Journal Protocol & Crash Recovery Invariant:**
  1. **Phase 1 — Durable INTENT Commit:**
     - Collect and lock authorities in canonical global total order via `SELECT ... FOR UPDATE`.
     - Read and validate expected versions and before-fingerprints.
     - Insert `inventory_mutation_journals` (`state = 'INTENT'`) and `inventory_mutation_participants` (`durable_apply_state = 'PENDING'`).
     - Commit SQL transaction. Externally visible live slot mutation cannot begin before this commit.
  2. **Phase 2 — Live / Apply:**
     - Dispatch to respective Folia execution contexts (`EntityScheduler` for Player, `RegionScheduler` owning context for Vessel/Island).
     - Apply live in-memory slot mutations.
  3. **Phase 3 — Durable Participant Apply Evidence:**
     - Each completed participant live apply commits `durable_apply_state = 'APPLIED'` to `inventory_mutation_participants`.
     - When all participants report applied, `inventory_mutation_journals` transitions to `state = 'APPLIED'`.
     - *Crash Window Contract:* Durable participant evidence does NOT magically eliminate crash windows: if a process crashes between live slot apply and committing `APPLIED` evidence, recovery inspects the participant's before-fingerprint, after-fingerprint, and version against current durable state. If ambiguous or externally modified, it fails closed into `RECOVERY_REQUIRED`.
  4. **Phase 4 — Final Durable OCC State Commit:**
     - Lock authorities via `SELECT ... FOR UPDATE`.
     - Persist canonical durable inventory states incrementing versions (`profile_inventories`, `vessel_inventories`, etc.).
     - Mark journal `state = 'COMMITTED'`.
     - Commit SQL transaction.
  5. **Crash Recovery & Reconciliation:**
     - Evaluates dual before/after fingerprints and durable participant states. Ambiguous state or slot drift fails closed into `RECOVERY_REQUIRED`. Zero blind refunds or blind destination applies.
```

#### Player Session Authority & Single-Writer Invariant
* **Core Invariant:** *At most one backend node may hold write authority over a player's canonical profile and inventory state at any given moment.*
* **Canonical Storage:** Dağıtık oturum sahipliğinin tek kanonik kaynağı SQL veritabanındaki `player_sessions` tablosudur.
* **Dialect-Neutral Clock Primitive:** `DB_NOW_PLUS(:seconds)` represents dialect-specific database time advancement (`CURRENT_TIMESTAMP + INTERVAL :s SECOND` in MySQL, `CURRENT_TIMESTAMP + (:s || ' seconds')::interval` in PostgreSQL, `DATETIME('now', '+' || :s || ' seconds')` in SQLite).
* **Exact Player Session Authority SQL Predicates:**
  1. **RENEW (Heartbeat by Current Active Node — Epoch does NOT increment):**
     ```sql
     UPDATE player_sessions
     SET lease_expires_at = DB_NOW_PLUS(15),
         updated_at = CURRENT_TIMESTAMP
     WHERE player_uuid = :playerUuid
       AND authoritative_node = :currentNode
       AND session_epoch = :currentEpoch
       AND state = 'ACTIVE'
       AND lease_expires_at >= CURRENT_TIMESTAMP;
     ```
  2. **DRAIN (Source Node Starts Handoff — Freezes Player Mutations):**
     ```sql
     UPDATE player_sessions
     SET state = 'DRAINING',
         updated_at = CURRENT_TIMESTAMP
     WHERE player_uuid = :playerUuid
       AND authoritative_node = :currentNode
       AND session_epoch = :currentEpoch
       AND state = 'ACTIVE'
       AND lease_expires_at >= CURRENT_TIMESTAMP;
     ```
  3. **HANDOFF_READY (Source Node Finished Snapshot & Journal Flush — Destination Bound & Expiry):**
     ```sql
     UPDATE player_sessions
     SET state = 'HANDOFF_READY',
         handoff_id = :handoffId,
         handoff_target_node = :targetNode,
         handoff_expires_at = DB_NOW_PLUS(30),
         lease_expires_at = DB_NOW_PLUS(30),
         updated_at = CURRENT_TIMESTAMP
     WHERE player_uuid = :playerUuid
       AND authoritative_node = :currentNode
       AND session_epoch = :currentEpoch
       AND state = 'DRAINING'
       AND lease_expires_at >= CURRENT_TIMESTAMP;
     ```
  4. **PLANNED ACQUIRE (Destination Node Claims Session under Bounded Destination-Scoped Handoff):**
     ```sql
     UPDATE player_sessions
     SET authoritative_node = :destinationNode,
         session_epoch = session_epoch + 1,
         state = 'ACTIVE',
         handoff_id = NULL,
         handoff_target_node = NULL,
         handoff_expires_at = NULL,
         lease_expires_at = DB_NOW_PLUS(15),
         updated_at = CURRENT_TIMESTAMP
     WHERE player_uuid = :playerUuid
       AND state = 'HANDOFF_READY'
       AND handoff_id = :expectedHandoffId
       AND handoff_target_node = :destinationNode
       AND authoritative_node = :expectedSourceNode
       AND session_epoch = :expectedSourceEpoch
       AND handoff_expires_at >= CURRENT_TIMESTAMP;
     -- affectedRows MUST == 1. Capability-scoped to one intended destination and bounded handoff window.
     -- Expired planned handoffs cannot be acquired via this path; they fall back to failure takeover.
     ```
  5. **FAILURE TAKEOVER (Destination Node Claims Expired Session after Crash/Timeout):**
     ```sql
     UPDATE player_sessions
     SET authoritative_node = :destinationNode,
         session_epoch = session_epoch + 1,
         state = 'RECOVERING',
         handoff_id = NULL,
         handoff_target_node = NULL,
         handoff_expires_at = NULL,
         lease_expires_at = DB_NOW_PLUS(15),
         updated_at = CURRENT_TIMESTAMP
     WHERE player_uuid = :playerUuid
       AND session_epoch = :expectedEpoch
       AND lease_expires_at < CURRENT_TIMESTAMP;
     -- affectedRows MUST == 1. Strictly distinct from PLANNED ACQUIRE!
     ```
  6. **AUTHORITATIVE PLAYER MUTATION & COMMIT-TIME ROW LOCK SERIALIZATION:**
     Rather than relying on a statement-time `EXISTS` check that fails to hold locks until `COMMIT`, every authoritative player/profile-state mutation transaction MUST serialize on the canonical `player_sessions` row:
     ```sql
     -- Step 1: Serialize on canonical session row (holds exclusive row lock until COMMIT/ROLLBACK)
     SELECT authoritative_node,
            session_epoch,
            state,
            lease_expires_at
     FROM player_sessions
     WHERE player_uuid = :playerUuid
     FOR UPDATE;

     -- Step 2: Application validates within the exact same transaction:
     --   authoritative_node == currentNode
     --   session_epoch == expectedEpoch
     --   state == 'ACTIVE' for routine periodic ambient checkpoints (or 'DRAINING' strictly for dedicated handoff/controlled shutdown flushes)
     --   lease_expires_at >= DB_CURRENT_TIME
     -- If validation fails (e.g. state != 'ACTIVE' during routine ambient checkpoint): transaction aborts/rolls back without canonical write; dirty state is NOT cleared.

     -- Step 3: Critical business mutation guarded by OCC version check
     UPDATE profile_inventories
     SET profile_inventory_version = profile_inventory_version + 1,
         inventory_nbt = :nbt,
         updated_at = CURRENT_TIMESTAMP
     WHERE profile_id = :profileId
       AND profile_inventory_version = :expectedVersion;
     -- affectedRows MUST == 1.

     -- Step 4: Atomic Commit
     COMMIT;
     ```
     - *Critical Invariant:* **Every authoritative player-state mutation and every player-session ACQUIRE/TAKEOVER transition MUST serialize on the same canonical `player_sessions` row.**
     - *Lease Expiry during an Already-Authorized Transaction:* If owner, epoch, and lease were valid when the canonical row lock was acquired, nominal lease expiration during statement execution does NOT revoke the transaction mid-flight, provided it completes within its operational deadline. Any concurrent `TAKEOVER` query targets the exact same canonical row and blocks on the lock until the transaction commits or rolls back.
       > *Invariant:* Lease expiration rejects new authoritative transactions; it does not bypass an already-authorized bounded transaction holding the canonical session row lock.
     - *Universal Application Scope:* This canonical row lock serialization is strictly mandatory across:
       - Final quit / profile flush
       - Profile switch critical persistence
       - Critical inventory journal finalization
       - Vault player-inventory durable commit
       - Trade durable player-state commit
       - Cross-server handoff final flush
       - Ambient checkpoint under Hybrid durability (targets 60s default configurable cadence; strictly requires `state == 'ACTIVE'`; if `state != 'ACTIVE'` e.g. `DRAINING`, `HANDOFF_READY`, `RECOVERING`, `OFFLINE`, or `LOCAL_FENCED`, write is rejected without clearing dirty state).
     - *Player-State Lock Hierarchy:*
       To eliminate lock inversion deadlocks:
       1. `player_sessions` (Row lock via `FOR UPDATE`)
       2. `player_accounts` / `player_profiles`
       3. `profile_inventories`
       4. `inventory_mutation_journals` / `outbox_events` / `processed_operations`
       - **Multi-Player Deadlock Ordering:** Operations involving multiple players (e.g. trades) MUST sort `Player UUID`s in **UUID Unsigned 128-Bit Binary Order** (`Long.compareUnsigned`), acquiring `player_sessions` row locks in ascending order before locking child entities.
* **Source Node Conservative Local Monotonic Self-Fencing Protocol:**
  - If a source node loses database connectivity, it cannot query the database to discover whether its lease has expired.
  - To prevent network/JDBC response delays from extending the local authority assumption beyond the database lease, Node A records a monotonic timestamp **BEFORE** dispatching the renewal request:
    ```text
    renewAttemptStartedNanos = System.nanoTime();
    send RENEW to database;
    if (RENEW acknowledged successfully) {
        localSelfFenceDeadline = renewAttemptStartedNanos + leaseDurationNanos - safetyMarginNanos;
        if (System.nanoTime() >= localSelfFenceDeadline) {
            // Response arrived too late; local authority window already expired!
            // Node MUST NOT assume continued mutable authority; fail-closed or re-renew immediately.
            transitionTo(LOCAL_FENCED);
        }
    }
    ```
  - *Invariant:* **The locally assumed authority window MUST never intentionally extend beyond the conservatively estimated database lease window.**
  - If DB renewal is not successfully confirmed before `localSelfFenceDeadline`:
    - The player session transitions locally to `LOCAL_FENCED`.
    - All inventory mutations, item drops/pickups, trades, vault transactions, shops, and profile switches are blocked locally.
    - Player transfer is initiated or player is disconnected with `DISCONNECT_STALE_AUTHORITY` before its database lease can expire.
* **Folia Execution & Player Access Terminology:**
  - *Context Rule:* **Folia events may execute in parallel on their valid owning region context. Any immediate player access must be valid for the current event execution context. Any deferred or later access/mutation to the Player MUST use that Player's `EntityScheduler`.**




### 2.5 Canonical GameMode Aggregate & Participation Subsystem (`game_mode_instances`, `game_mode_dimension_instances`, `game_mode_participations`, `game_mode_instance_authorities`, `mode_owned_authorities`)

* **Canonical Cross-Mode Aggregate Model:**
  - `game_mode_instances` is the canonical aggregate root representing an instantiated gameplay session for any supported game mode archetype.
  - `game_mode_dimension_instances` persists the instantiated Minecraft worlds/dimensions linked to the game mode instance.
  - `game_mode_participations` is the canonical cross-mode participation entity binding a `profile_id` to an `instance_id`.
  - `game_mode_instance_authorities` manages distributed single-writer leases for non-island primary roots.
  - `mode_owned_authorities` manages distributed single-writer leases for extensible mode-owned roots under core-owned persistence semantics.
  - Runtime Java objects/class names are NEVER persisted; only stable IDs, version pins, and root references are stored.

```sql
CREATE TABLE game_mode_instances (
    instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
    game_mode_id VARCHAR(64) NOT NULL,
    implementation_version VARCHAR(32) NOT NULL,
    schema_version INT NOT NULL,
    content_definition_version VARCHAR(32) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- CREATING, ACTIVE, SUSPENDED, ARCHIVED
    primary_root_type_id VARCHAR(64) NOT NULL, -- Namespaced open root ID: uxm:island, uxm:vessel, uxm:course_plot, etc.
    primary_root_aggregate_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_game_mode_instances_mode ON game_mode_instances (game_mode_id, lifecycle_state);
CREATE INDEX idx_game_mode_instances_root ON game_mode_instances (primary_root_type_id, primary_root_aggregate_id);

CREATE TABLE game_mode_dimension_instances (
    dimension_instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
    instance_id VARCHAR(36) NOT NULL,
    dimension_id VARCHAR(64) NOT NULL, -- Namespaced ID: e.g. uxm:overworld, uxm:nether, uxm:the_end, uxm:void
    world_name VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- CREATING, ACTIVE, UNLOADED, ARCHIVED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dim_instances_game_mode FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_dim_instances_mode ON game_mode_dimension_instances (instance_id, dimension_id);

CREATE TABLE game_mode_instance_authorities (
    instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
    authoritative_node VARCHAR(64) NOT NULL,
    authority_epoch BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    lease_expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_instance_auth_instance FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_instance_auth_node ON game_mode_instance_authorities (authoritative_node);

CREATE TABLE mode_owned_authorities (
    provider_id VARCHAR(64) NOT NULL,
    root_key VARCHAR(128) NOT NULL,
    authoritative_node VARCHAR(64) NOT NULL,
    authority_epoch BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    lease_expires_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider_id, root_key)
);

CREATE INDEX idx_mode_owned_auth_node ON mode_owned_authorities (authoritative_node);

CREATE TABLE game_mode_participations (
    instance_id VARCHAR(36) NOT NULL,
    profile_id VARCHAR(36) NOT NULL,
    participation_role VARCHAR(32) NOT NULL DEFAULT 'MEMBER', -- Cross-mode lifecycle: OWNER, MEMBER, INVITED, VISITOR
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ruleset_at_join VARCHAR(32) NOT NULL,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (instance_id, profile_id),
    CONSTRAINT fk_participation_instance FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE,
    CONSTRAINT fk_participation_profile FOREIGN KEY (profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_participation_profile ON game_mode_participations (profile_id);
```

* **AuthorityBinding Derivation & Provider Resolution Contract:**
  Each `GameModeInstance` resolves its single-writer distributed authority lease dynamically based on its primary root definition:
  1. **Island-Derived Modes (`primary_root_type_id = 'uxm:island'`):**
     - Modes: `SkyBlock`, `OneBlock`, `ChunkBlock`, `AcidIsland`, `CaveBlock`, `SkyGrid`.
     - Authority binds to `ISLAND` authority (`island_authorities`) keyed by `primary_root_aggregate_id` (`island_id`).
  2. **Non-Island / Root-Generic Modes:**
     - Modes: `Boxed`, `Poseidon`, `StrangerRealms`, `TradeWinds`, `Parkour`, `Brix`.
     - Authority binds to `GAME_MODE_INSTANCE` authority (`game_mode_instance_authorities`) keyed by `instance_id`.
  3. **Mode-Owned Extension Modes:**
     - Strictly an extension path when truly custom authority ownership is required.
     - Third-party extensible modes resolve via registered `ModeOwnedAuthorityProvider` supplying logical identity (`providerId` + `rootAggregateKey`).
     - Authority binds to core-owned `mode_owned_authorities` table with composite key `(provider_id, root_key)`. The core persistence engine owns row serialization, epoch fencing, and lease validation; external plugins never inject arbitrary SQL tables.
  4. **Compound Multi-Owner Mutations:** Multi-inventory mutations (e.g. Player Profile $\leftrightarrow$ Vessel Cargo) bind each participating inventory to its canonical authority (`PLAYER_SESSION` via `player_sessions`, `GAME_MODE_INSTANCE` via `game_mode_instance_authorities`, or `MODE_OWNED` via `mode_owned_authorities`).

### 2.6 Island Membership & Role Projection (`island_members`)

* **Participation Role vs Island Role Semantics & Joint Transaction Boundary:**
  - **`game_mode_participations.participation_role`:** Governs generic, cross-mode participation lifecycle status (`OWNER`, `MEMBER`, `INVITED`, `VISITOR`).
  - **`island_members.role_id`:** Governs island-specific authorization roles, permission trees, and administrative powers (`LEADER`, `CO_LEADER`, `MODERATOR`, `MEMBER`, `RECRUIT`).
  - **Joint Transaction Boundary Invariant:**
    - For Island-based modes (`primary_root_type_id = 'uxm:island'`), inserting `game_mode_participations` and inserting the corresponding `island_members` projection MUST execute within the exact same atomic SQL transaction. Neither row may exist without the other during active island membership.
    - When a player leaves, is kicked, or an island is disbanded, deleting from `game_mode_participations` cascades to `island_members` via compound FK `(instance_id, profile_id)`.
    - Membership authorization is strictly **profile-scoped** (`profile_id`), ensuring multi-profile accounts (Classic, Ironman, Hardcore) maintain clean independent co-op memberships.
    - `account_uuid` is persisted purely for account lookup, staff audit, and offline display, NEVER as the primary authorization key.

```sql
CREATE TABLE island_members (
    island_id VARCHAR(36) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    profile_id VARCHAR(36) NOT NULL,
    account_uuid VARCHAR(36) NOT NULL, -- Retained for lookup, audit, and offline display
    role_id VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (island_id, profile_id),
    CONSTRAINT fk_island_members_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE,
    CONSTRAINT fk_island_members_profile FOREIGN KEY (profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_island_members_participation FOREIGN KEY (instance_id, profile_id)
        REFERENCES game_mode_participations (instance_id, profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_island_members_profile ON island_members (profile_id);
CREATE INDEX idx_island_members_account ON island_members (account_uuid);
```

### 2.7 Island Bank & Financial Wallets (`island_banks`)
```sql
CREATE TABLE island_banks (
    island_id VARCHAR(36) NOT NULL PRIMARY KEY,
    primary_balance_minor_units BIGINT NOT NULL DEFAULT 0, -- Canonical exact minor units (BIGINT across all dialects, or declared DECIMAL(18, scale); SQLite REAL strictly BANNED)
    crystals_balance BIGINT NOT NULL DEFAULT 0,
    exp_balance BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_island_banks_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);
```

#### Authority Row Lock Serialization for Mutations (OCC / CAS)
To guarantee that a takeover cannot commit while an authoritative mutation is in flight, every mutating business transaction explicitly locks the canonical `island_authorities` row:
```sql
-- Step 1: Acquire exclusive row lock on canonical authority lease
SELECT authoritative_node, authority_epoch, lease_expires_at
FROM island_authorities
WHERE island_id = :islandId
FOR UPDATE;

-- Step 2: Application verifies:
--   authoritative_node == currentNode
--   authority_epoch == expectedEpoch
--   lease_expires_at > DB_CURRENT_TIME
-- If check fails: transaction records deterministic rejection and commits.

-- Step 3: Execute Business Mutation on island_banks (affected rows MUST == 1)
UPDATE island_banks
SET primary_balance_minor_units = primary_balance_minor_units + :deltaMinorUnits,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE island_id = :islandId
  AND version = :expectedVersion
  AND primary_balance_minor_units + :deltaMinorUnits >= 0;
```

### 2.8 Financial Audit Log (`bank_transactions`)
```sql
CREATE TABLE bank_transactions (
    transaction_id VARCHAR(36) NOT NULL PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    island_id VARCHAR(36) NOT NULL,
    actor_uuid VARCHAR(36) NOT NULL,
    currency_id VARCHAR(32) NOT NULL DEFAULT 'PRIMARY', -- Namespaced currency: PRIMARY, CRYSTALS, EXP, or custom token
    currency_scale INT NOT NULL DEFAULT 2, -- Provider-defined decimal scale (e.g. 2 for cents/kuruş, 0 for integers)
    delta_amount_minor_units BIGINT NOT NULL, -- Exact integer minor units
    resulting_balance_minor_units BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bank_transactions_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE INDEX idx_bank_transactions_island_date ON bank_transactions (island_id, created_at DESC);
CREATE INDEX idx_bank_transactions_operation ON bank_transactions (operation_id);
```

### 2.9 Shared Island Vault Pages & Durable Edit Sessions (`island_vault_pages`, `vault_edit_sessions`)
```sql
CREATE TABLE island_vault_pages (
    island_id VARCHAR(36) NOT NULL,
    page INT NOT NULL,
    page_version BIGINT NOT NULL DEFAULT 1,
    lease_epoch BIGINT NOT NULL DEFAULT 1,
    active_session_id VARCHAR(36) NULL,
    contents_nbt BLOB NOT NULL,
    last_modified_by VARCHAR(36) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (island_id, page),
    CONSTRAINT fk_island_vault_pages_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE TABLE vault_edit_sessions (
    session_id VARCHAR(36) NOT NULL PRIMARY KEY,
    island_id VARCHAR(36) NOT NULL,
    page INT NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    base_page_version BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    escrow_journal JSON NULL,
    opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP NULL,
    CONSTRAINT fk_vault_sessions_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE INDEX idx_vault_sessions_expiry ON vault_edit_sessions (state, expires_at);
```

#### Vault Mutation Predicate & Write-Ahead Escrow Protocol
```sql
UPDATE island_vault_pages
SET contents_nbt = :contents,
    page_version = page_version + 1,
    active_session_id = NULL,
    last_modified_by = :playerId,
    updated_at = CURRENT_TIMESTAMP
WHERE island_id = :islandId
  AND page = :pageNumber
  AND page_version = :expectedPageVersion
  AND lease_epoch = :expectedLeaseEpoch
  AND active_session_id = :sessionId;
```

* **Exact Write-Ahead Vault Transfer Protocol & Dual-Slot Version Contract:**
  Every item transfer between the island vault GUI and player inventory follows a strict write-ahead sequence:
  1. `TRANSFER_INTENT`: Prior to modifying visible slot contents, an intent record is staged into `vault_edit_sessions.escrow_journal`:
     - **Dual-Slot Journal Entry Schema:**
       ```json
       {
         "transfer_id": "UUID",
         "session_id": "UUID",
         "source": "VAULT|PLAYER",
         "destination": "PLAYER|VAULT",
         "source_slot": 14,
         "destination_slot": 2,
         "source_before_fingerprint": "a3f8e02b...",
         "source_after_fingerprint": "EMPTY",
         "destination_before_fingerprint": "EMPTY",
         "destination_after_fingerprint": "a3f8e02b...",
         "source_expected_version": 12,
         "destination_expected_version": 4,
         "source_container_version": 105,
         "destination_container_version": 32,
         "serialized_item_nbt": "H4sIC...",
         "quantity": 64,
         "state": "INTENT",
         "created_at": "2026-09-12T12:00:00Z"
       }
       ```
  2. **Slot Content Mutation:** The player and vault GUI slot views are updated on the Folia entity thread, incrementing local slot versions and container generations.
  3. `TRANSFER_APPLIED`: The journal entry state is updated to `APPLIED`.
  4. `SESSION_COMMIT`: When the GUI closes, the final page NBT is committed to `island_vault_pages`, the session state transitions to `COMMITTED`, and escrow journal entries are finalized.

* **Canonical Item Ownership & Invariants:**
  - **Core Invariant:** *No player-visible vault transfer may occur before its durable transfer intent exists.*
  - **Prohibition of Blind Reversals:** Blindly restoring items on crash without inspecting both source and destination slot states is strictly prohibited.
  - **Comprehensive Dual-Slot Recovery Decision Table:**
    Upon server reboot or player reconnect with an uncommitted session (`state != 'COMMITTED'` and `expires_at < CURRENT_TIMESTAMP`), the recovery worker inspects the physical slot states of **both source and destination**:

    | Source Slot State | Destination Slot State | Recovery Action | Resulting State | Inventory & Accounting Outcome |
    | :--- | :--- | :--- | :--- | :--- |
    | `BEFORE` (Item in source) | `BEFORE` (Destination empty/pre-item) | `ABORT` | `ABORTED` | Crash in `INTENT` pre-mutation. Zero items moved or refunded. No duplication. |
    | `AFTER` (Source cleared) | `AFTER` (Item in destination) | `CONDITIONAL_ROLLBACK` | `ABORTED` | In-memory transfer occurred but GUI never committed. Reverts item to source slot, increments slot/container versions. |
    | `BEFORE` (Item in source) | `AFTER` (Item in destination) | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | **Duplication Hazard!** Both slots contain item. Refuse repair; quarantine slot and vault page; emit critical staff alert. |
    | `AFTER` (Source cleared) | `BEFORE` (Destination empty) | `CONDITIONAL_ROLLBACK` | `ABORTED` | **Item Loss Hazard!** Item cleared from source but missing at destination. Restores item from journal NBT to source; increments version. |
    | `UNKNOWN` (Drift/Tampering) | *ANY* | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | Source slot has unrecognized fingerprint (external mutation). Quarantine page; manual operator inspection required. |
    | *ANY* | `UNKNOWN` (Drift/Tampering) | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | Destination slot has unrecognized fingerprint. Quarantine page; manual operator inspection required. |

* **Vault Commit Atomic SQL Transaction Boundary:**
  When the vault GUI closes, all changes persist within a **single atomic SQL transaction**:
  ```sql
  START TRANSACTION;
  -- 1. Canonical authority row lock serialization
  SELECT authoritative_node, authority_epoch, lease_expires_at
  FROM island_authorities WHERE island_id = :islandId FOR UPDATE;

  -- 2. Atomic CAS update of vault page
  UPDATE island_vault_pages
  SET contents_nbt = :contents,
      page_version = page_version + 1,
      active_session_id = NULL,
      last_modified_by = :playerId,
      updated_at = CURRENT_TIMESTAMP
  WHERE island_id = :islandId
    AND page = :pageNumber
    AND page_version = :expectedPageVersion
    AND lease_epoch = :expectedLeaseEpoch
    AND active_session_id = :sessionId;

  -- 3. Atomic flush of player inventory ledger (if player state is durably coordinated)
  UPDATE profile_inventories
  SET inventory_nbt = :playerInventoryNbt,
      version = version + 1,
      updated_at = CURRENT_TIMESTAMP
  WHERE profile_id = :playerProfileId;

  -- 4. Mark session COMMITTED and clear active lock
  UPDATE vault_edit_sessions
  SET state = 'COMMITTED',
      closed_at = CURRENT_TIMESTAMP
  WHERE session_id = :sessionId
    AND state = 'ACTIVE';
  COMMIT;
  ```
  If `affectedRows == 0` on any update statement, the transaction rolls back completely. The player's uncommitted in-memory transfers are safely reverted using conditional repair logic, and the player is notified (`<error>Vault session expired or was modified concurrently. Changes reverted.</error>`).

* **Session-Owned Slots & External Inventory Mutation Policy:**
  - To prevent concurrent writers from mutating the same inventory slot:
    - An active vault session declares **Session-Owned Slots**: vault slots ($0..53$) and player inventory slots actively involved in in-flight transfers.
    - **UXM-Owned vs Third-Party Boundary Guarantee:**
      - **UXM Subsystems:** All internal plugins (Quests, Daily Rewards, Missions, Drops) MUST route player inventory mutations through `CentralInventoryMutationService`. If a targeted slot is session-owned, the mutation is queued until vault GUI close or relocated to a free slot.
      - **Third-Party Bukkit Plugins:** Direct calls to Bukkit API (`player.getInventory().setItem(...)`) by third-party plugins bypass custom events and cannot be intercepted synchronously without bytecode rewriting. **Direct third-party Bukkit API mutations are explicitly outside strong vault consistency guarantees.** If a third-party plugin corrupts an active slot, Conditional Repair detects the fingerprint mismatch and safely flags `RECOVERY_REQUIRED`.

---

### 2.10 Transactional Operation Boundary & Scoped Idempotency (`processed_operations`)
```sql
CREATE TABLE processed_operations (
    operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    operation_scope VARCHAR(32) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    result_code VARCHAR(64) NULL,
    result_payload JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    CONSTRAINT uq_processed_ops UNIQUE (operation_scope, actor_id, idempotency_key)
);

CREATE INDEX idx_processed_operations_resource ON processed_operations (resource_id);
```

* **Scoped Idempotency Contract (Eliminating Key Collisions):**
  - Uniqueness is scoped to `(operation_scope, actor_id, idempotency_key)`:
    - `operation_scope`: Subsystem namespace (e.g. `ISLAND_BANK`, `ISLAND_UPGRADE`, `VAULT_PAGE`).
    - `actor_id`: Player UUID or Island UUID executing the request.
    - `idempotency_key`: Client-supplied UUID/ULID token, strictly preserved across logical retries. (Timestamp-based keys are banned).

* **Concurrency Model: Fully Atomic Single Transaction (Model A):**
  - All operations (idempotency reservation, business mutation, result finalization) execute within a single ACID transaction.
  - **Unique Index Lock Contention Semantics:**
    - Request A begins transaction and inserts `(scope, actor, key)` with `status = 'PENDING'`.
    - Concurrent duplicate Request B begins transaction and attempts the same insert. In standard RDBMS engines (PostgreSQL, MySQL, MariaDB), Request B's insert **blocks on the unique index** until Request A commits or rolls back.
    - When Request A commits (`status = 'APPLIED'` or `'REJECTED'`), Request B unblocks with a unique constraint violation (`DuplicateKeyException`).
    - Request B rolls back its draft, executes a clean read on `processed_operations`, finds Request A's committed record, and returns the cached outcome directly.
    - If Request A's transaction holds the lock beyond the lock acquisition timeout (`lock_timeout = 1000ms`), Request B aborts with `IDEMPOTENCY_OPERATION_IN_PROGRESS`.
    - Uncommitted `PENDING` states are NEVER assumed to be visible across transactions under READ COMMITTED / REPEATABLE READ isolation.

* **Single-Transaction Atomic Rejection Protocol:**
  - When an authoritative mutation fails (`affectedRows == 0` due to OCC version mismatch or insufficient funds), the transaction is **NOT** rolled back to an unrecorded void.
  - Within the **exact same transaction**, `processed_operations` is updated to `status = 'REJECTED'`, the failure `result_code` is recorded, and the transaction commits (without inserting audit or outbox records).
  - Eliminates the crash window of multi-transaction rejection patterns.

### 2.11 Transactional Outbox Pipeline & Dispatcher Claiming (`outbox_events`)
```sql
CREATE TABLE outbox_events (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING, CLAIMED, PROCESSED, DEAD_LETTER
    claim_owner VARCHAR(64) NULL,
    claim_token VARCHAR(36) NULL,
    claim_expires_at TIMESTAMP NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    last_error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP NULL
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, next_attempt_at, created_at ASC);
CREATE INDEX idx_outbox_events_claim ON outbox_events (status, claim_expires_at);
```

* **Worker Claiming Model & Exact Transaction Boundaries (`SKIP LOCKED` / Lease Claim):**
  - **TX1 (Claim Transaction — Database Lock Boundary & Expired Lease Reclaim):**
    ```sql
    START TRANSACTION;
    -- Select pending events or expired claims ready for retry
    SELECT event_id, payload, retry_count
    FROM outbox_events
    WHERE (status = 'PENDING' OR (status = 'CLAIMED' AND claim_expires_at < CURRENT_TIMESTAMP))
      AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
      AND status != 'DEAD_LETTER'
    ORDER BY created_at ASC
    LIMIT 100
    /* PostgreSQL / MySQL 8.0+: FOR UPDATE SKIP LOCKED | SQLite: single-writer BEGIN IMMEDIATE */;

    -- Generate a unique claim_token (UUIDv4) per worker claim batch
    UPDATE outbox_events
    SET status = 'CLAIMED',
        claim_owner = :workerId,
        claim_token = :newClaimToken,
        claim_expires_at = DB_NOW_PLUS(30),
        retry_count = retry_count + 1
    WHERE event_id IN (:claimedIds);
    COMMIT;
    ```
  - **Outside DB Transaction Boundary (Network I/O):**
    ```text
    -- Database row locks are NEVER held during network streaming!
    FOR EACH event IN claimedEvents:
        TRY:
            XADD uxmskyblock:stream:<topic> * event_id payload
        CATCH TransientNetworkException:
            SCHEDULE_RETRY_WITH_EXPONENTIAL_BACKOFF(event)
        CATCH MalformedPayloadException:
            MOVE_TO_DEAD_LETTER(event)
    ```
  - **TX2 (Completion Transaction — Database Boundary with Tightened Fencing):**
    ```sql
    START TRANSACTION;
    UPDATE outbox_events
    SET status = 'PROCESSED',
        processed_at = CURRENT_TIMESTAMP
    WHERE event_id = :eventId
      AND status = 'CLAIMED'
      AND claim_token = :claimToken
      AND claim_owner = :workerId;
    COMMIT;
    ```
  - **Stale Worker Completion Fencing & `STALE_CLAIM`:**
    - If `affectedRows == 0`, the completion attempt is diagnosed as a `STALE_CLAIM`. The worker knows its claim expired, and another worker reclaimed the row or it was moved to dead-letter. The worker immediately aborts, logs `STALE_OUTBOX_WORKER_FENCED`, and discards the completion.
  - **Poison Event & Dead-Letter Policy (DLQ):**
    - **Transient vs Permanent Failure Separation:**
      - *Transient Failures (Redis connectivity down):* Exponential backoff (`next_attempt_at = NOW() + (2^retry_count * 5s)`), up to `max_retries = 5`.
      - *Permanent / Poison Failures (malformed JSON payload or serialization bug):* Moved immediately to `DEAD_LETTER`.
      - When `retry_count >= 5` on unrecoverable failure, status transitions to `DEAD_LETTER`, `last_error` is durably stored, and a critical operational alert is emitted.
    - **Zero Data Loss Invariant:** Outbox events are **NEVER deleted** upon failure. The dead-letter queue retains the immutable event payload permanently. An administrative CLI tool (`/is admin outbox replay <event_id>`) allows operators to review the error and re-queue the event to `PENDING` once the underlying issue is resolved.
  - **Publication Semantics & Crash Safety:**
    - At-least-once publish guarantee.
    - If a worker crashes after `XADD` but before completing TX2, the 30-second claim expires. A subsequent worker claims the row and re-issues `XADD`.
    - Downstream consumers deduplicate via `consumer_inbox` on `event_id`, ensuring side-effects execute at most once.

### 2.12 Idempotent Consumer Processing & Dialect-Safe Inbox (`consumer_inbox`)
```sql
CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX idx_consumer_inbox_processed ON consumer_inbox (processed_at);
```
* **Dialect-Safe Idempotent Claiming:**
  - Plain `INSERT` that violates PK aborts the entire transaction in PostgreSQL. Therefore, consumers use dialect-safe conditional claiming:
    - **PostgreSQL:** `INSERT INTO consumer_inbox (consumer_name, event_id, processed_at) VALUES (...) ON CONFLICT (consumer_name, event_id) DO NOTHING;`
    - **MySQL / MariaDB:** `INSERT INTO consumer_inbox (consumer_name, event_id, processed_at) VALUES (...) ON DUPLICATE KEY UPDATE consumer_name = consumer_name;`
    - **SQLite:** `INSERT OR IGNORE INTO consumer_inbox (consumer_name, event_id, processed_at) VALUES (...);`
  - If affected rows == 1: apply local projection / state mutation and `COMMIT`; then send `XACK`.
  - If affected rows == 0: event was already processed; do not execute projection, `COMMIT` and send `XACK` immediately.

### 2.13 Spiral Grid Coordinate Recycling Pool (`spiral_slot_pool`)
```sql
CREATE TABLE spiral_slot_pool (
    slot_index INT NOT NULL PRIMARY KEY,
    grid_x INT NOT NULL,
    grid_z INT NOT NULL,
    is_allocated BOOLEAN NOT NULL DEFAULT TRUE,
    vacated_at TIMESTAMP NULL
);

CREATE INDEX idx_spiral_slot_pool_free ON spiral_slot_pool (is_allocated, slot_index ASC);
```

### 2.14 Upgrades, Permissions Overrides & Flags
```sql
CREATE TABLE island_upgrades (
    island_id VARCHAR(36) NOT NULL,
    upgrade_key VARCHAR(64) NOT NULL,
    tier INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (island_id, upgrade_key),
    CONSTRAINT fk_island_upgrades_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE TABLE island_permissions_override (
    island_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(32) NOT NULL,
    permission_bitmask BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (island_id, role_id),
    CONSTRAINT fk_island_perms_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE TABLE island_flags (
    island_id VARCHAR(36) NOT NULL PRIMARY KEY,
    flag_bitmask BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_island_flags_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

### 2.15 Disaster Recovery & Restore Operations (`restore_operations`, `restore_unit_progress`)
```sql
CREATE TABLE restore_operations (
    restore_id VARCHAR(36) NOT NULL PRIMARY KEY,
    island_id VARCHAR(36) NOT NULL,
    snapshot_id VARCHAR(36) NOT NULL,
    mode VARCHAR(32) NOT NULL, -- GEOMETRY_ONLY, WORLD_CONTENT_SAFE, FULL_ISLAND
    state VARCHAR(32) NOT NULL DEFAULT 'PREPARING', -- PREPARING, QUARANTINED, APPLYING_WORLD, WORLD_APPLIED, APPLYING_METADATA, VERIFYING, COMMITTED, FAILED, RECOVERY_REQUIRED
    generation BIGINT NOT NULL DEFAULT 1,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_completed_unit VARCHAR(64) NULL, -- Monitoring optimization (NOT the sole source of truth)
    failure_reason VARCHAR(255) NULL,
    CONSTRAINT fk_restore_operations_island FOREIGN KEY (island_id)
        REFERENCES islands (id) ON DELETE CASCADE
);

CREATE INDEX idx_restore_operations_island ON restore_operations (island_id, state);

CREATE TABLE restore_unit_progress (
    restore_id VARCHAR(36) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING', -- PENDING, APPLY_INTENT, APPLIED, VERIFIED, FAILED
    source_checksum VARCHAR(64) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    PRIMARY KEY (restore_id, unit_id),
    CONSTRAINT fk_restore_progress_op FOREIGN KEY (restore_id)
        REFERENCES restore_operations (restore_id) ON DELETE CASCADE
);

CREATE INDEX idx_restore_unit_progress_lookup ON restore_unit_progress (restore_id, state);
```

* **Restore Unit Write-Ahead Protocol & Crash Safety:**
  - `last_completed_unit` in `restore_operations` serves solely as an indexing/monitoring optimization; the canonical progress state machine resides in `restore_unit_progress`.
  - For each world restore unit (chunk or region section):
    1. **Persist `APPLY_INTENT`:** Transaction commits with unit marked `APPLY_INTENT` and `source_checksum`.
    2. **Apply Unit State in Valid Folia Region Context:** World chunk data is applied using **idempotent/deterministic replacement** (target state is replaced with snapshot unit state; incremental or additive application is prohibited).
    3. **Verify Expected State:** Calculate target chunk checksum against `source_checksum`.
    4. **Persist `APPLIED` / `VERIFIED`:** Transaction commits with `state = 'VERIFIED'` and `completed_at = CURRENT_TIMESTAMP`.
  - **Crash Replay:** If a crash occurs during `APPLY_INTENT`, upon restart the recovery worker discovers the unverified unit and safely re-executes the replacement idempotently.
* **Quarantine Enforcement:**
  - An island entering restore is locked under `quarantine_state = 'RESTORING'`.
  - Visitors are ejected to spawn, and interactive block/inventory edits are blocked.
  - **Invariant:** *The island MUST NOT exit quarantine until all units are `VERIFIED`, metadata application is complete, and the restore state machine transitions durably to `COMMITTED`.*
* **`ECONOMIC_ITEM_STATE` Taxonomy & Non-Rollback Boundary:**
  - **Definition:** Any physical world entity or tile state carrying economic value/items:
    1. Container block entities (chests, double chests, trapped chests, shulker boxes, barrels, hoppers, droppers, dispensers, furnaces, blast furnaces, smokers, brewing stands, chiseled bookshelves, crafters).
    2. Player-created or temporary container inventories.
    3. Shulker box item contents nested within other blocks.
    4. Item-holding entities (item frames, glowing item frames, armor stands, minecarts with chests/hoppers).
    5. Dropped `Item` entities on the ground.
    6. Entity equipment and mob inventories (mobs holding or wearing armor/weapons/items).
    7. Other platform-specific item-bearing adapters.
  - *Invariant:* **A snapshot that is not DB/world point-in-time consistent MUST NOT restore historical economic item state on top of current durable economic state without ledger-aware reconciliation.**
* **Deterministic Restore Modes:**
  1. **`GEOMETRY_ONLY`:** Restores blocks, biomes, and non-economic block state. Restores NO historical item contents (`ECONOMIC_ITEM_STATE` skipped/cleared).
  2. **`WORLD_CONTENT_SAFE`:** Restores geometry and explicitly non-economic supported entities and tile states. Restores NO historical economic item state.
  3. **`FULL_ISLAND`:** Restores geometry, allowed island metadata, membership/flags/upgrades where permitted by contract. **STILL DOES NOT** automatically rewind: bank balances (`island_banks`), player inventories (`profile_inventories`), vault pages (`island_vault_pages`), transaction history (`bank_transactions`), or economic item-bearing world contents (`ECONOMIC_ITEM_STATE`).
  4. **`ECONOMIC_RECONCILIATION_RESTORE`:** Future ledger-aware historical container reconciliation capability. Status in current architecture: **`NOT SUPPORTED`**. Historical container items are NEVER quietly restored.

* **BackupSet Logical Architecture & General Object Storage Integration:**
  - **Durable Backup Catalog Schema (`backup_operations`):**
    Canonical operational metadata resides in SQL persistence, owning the mutable operational state machine:
    ```sql
    CREATE TABLE backup_operations (
        backup_set_id VARCHAR(36) NOT NULL PRIMARY KEY,
        backup_type VARCHAR(32) NOT NULL, -- ROOT_BACKUP, DATABASE_DISASTER_BACKUP
        target_root_type_id VARCHAR(64) NULL, -- Required for ROOT_BACKUP (e.g. uxm:island, uxm:vessel)
        target_root_key VARCHAR(128) NULL, -- Stable root identity (e.g. island UUID or vessel key)
        state VARCHAR(32) NOT NULL DEFAULT 'PLANNED', -- PLANNED, CAPTURING, STAGED, UPLOADING, VERIFYING, AVAILABLE, FAILED, PARTIAL, RECOVERY_REQUIRED, DELETING, DELETED
        storage_policy VARCHAR(32) NOT NULL, -- LOCAL, REMOTE, MIRRORED
        consistency_coordination VARCHAR(32) NOT NULL, -- QUIESCED, VERSION_FENCED, TRANSACTION_SNAPSHOT, ROOT_MUTATION_FENCE
        consistency_guarantee VARCHAR(32) NOT NULL, -- FULL_RESTORE_CONSISTENT, ROOT_CONSISTENT, BEST_EFFORT
        generation BIGINT NOT NULL DEFAULT 1,
        authority_epoch BIGINT NULL,
        db_version BIGINT NULL,
        manifest_object_key VARCHAR(255) NULL,
        failure_reason VARCHAR(255) NULL,
        started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        completed_at TIMESTAMP NULL,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

    CREATE INDEX idx_backup_operations_root ON backup_operations (target_root_type_id, target_root_key, state);
    CREATE INDEX idx_backup_operations_state ON backup_operations (state);
    ```
  - **Operational State vs Immutable Manifest vs Publication Marker:**
    - **`BackupOperation` / `BackupCatalogRecord` (SQL Catalog):** Owns continuous mutable lifecycle progression (`PLANNED` $\to$ `CAPTURING` $\to$ `STAGED` $\to$ `UPLOADING` $\to$ `VERIFYING` $\to$ `AVAILABLE`, plus `FAILED`, `PARTIAL`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`).
    - **`BackupManifest` (`manifest.json`):** Immutable, self-contained disaster-recovery document published/finalized only for a completed capture generation. Contains stable facts (`backup_set_id`, `backup_type`, `root_type_id`, `root_key`, `created_at`, `authority_epoch`, `db_version`, `schema_version`, `plugin_version`, `game_mode_id`, `game_mode_version`, `consistency_result`, and `artifacts` map with strong SHA-256 checksums). It **NEVER** contains continuously mutable lifecycle state fields (`status = UPLOADING`).
    - **`BackupPublicationMarker` (`AVAILABLE.marker` / typed discovery marker):** Small discoverability marker uploaded to storage proving that the backup generation was completely published, verified, and remains an eligible restore candidate.
    - **Disaster Discovery Without Live Database:** Remote/local `manifest.json` files and `AVAILABLE.marker` allow disaster-recovery discovery even if the live SQL database is lost. A backup is considered an `AVAILABLE` restore candidate if and only if: (1) immutable manifest exists; (2) availability marker exists; (3) manifest and artifact checksum verification succeeds.
  - **Driven Infrastructure Ports (Defined in `:core` Application Boundary):**
    - **`ObjectStoragePort`:** Neutral outbound port defined in `:core`, consumed by Application Use Cases (`BackupApplicationService`, `SnapshotRestoreService`, etc.), NOT directly invoked by pure domain aggregates. Reusably serves world snapshots, database backup exports, reports, and durable binary artifacts.
      - Operation families: put/upload, get/download, streaming upload/download, head/metadata lookup, existence lookup, delete, list with pagination tokens, range reads, multipart upload/abort, server-side copy, and conditional operations.
      - Typed capabilities (`ObjectStorageCapabilities`): `MULTIPART_UPLOAD`, `RANGE_READ`, `SERVER_SIDE_COPY`, `CONDITIONAL_WRITE`, `PRESIGNED_URL`, `NATIVE_OBJECT_VERSIONING`, `OBJECT_LOCK`, `PROVIDER_LIFECYCLE_RULES`, `CHECKSUM_ALGORITHMS`.
    - **`DatabaseBackupPort`:** Database-wide disaster recovery outbound port defined in `:core`, implemented by `:persistence-adapter` or dedicated database-backup adapter. Decoupled from CLI command strings; enforces a dialect-correct consistent export and restore validation semantic contract.
    - **Root Snapshot Hexagonal Adapter Ownership:**
      - **`RootRelationalSnapshotPort`:** Outbound port in `:core`, implemented by `:persistence-adapter` for root-scoped relational data (island level, custom name, flags, permissions, upgrades). Pure relational persistence without Minecraft world or Bukkit dependencies.
      - **`WorldDimensionSnapshotPort`:** Outbound port in `:core`, implemented by `:bukkit-adapter` (or dedicated world snapshot adapter) for world/dimension chunk extraction and Folia thread coordination.
      - **`RootStateSnapshotPort`:** Coarse application-level coordinator façade in `:core` composing `RootRelationalSnapshotPort` and `WorldDimensionSnapshotPort`.
      - *Strict Invariant:* Database persistence adapter (`:persistence-adapter`) $\ne$ Minecraft world snapshot adapter (`:bukkit-adapter`). The persistence adapter MUST NEVER depend on Bukkit, Paper, or world classes.
  - **Provider Compatibility & Real Verification Boundary:**
    - *V1 Required Verified-Compatibility Targets:* Amazon AWS S3 and Cloudflare R2.
    - *Current Implementation Status:* NOT YET IMPLEMENTED / NOT YET COMPATIBILITY-VERIFIED (zero production Java or test suite execution yet written).
    - *Canonical Verification Distinction:*
      - Generic S3 Adapter Tests: Local emulators / compatible test servers MAY be used to test generic S3 adapter behavior.
      - AWS S3 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderAwsCompatibilityContractTest`) MUST pass against a real AWS S3 endpoint/account.
      - Cloudflare R2 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderR2CompatibilityContractTest`) MUST pass against a real Cloudflare R2 endpoint/account.
      - *Emulator Rule:* Passing against an emulator can NEVER upgrade AWS S3 or Cloudflare R2 to VERIFIED status.
    - *Scope Boundary:* General object storage provides reusable semantics and capability discovery; it does NOT claim full AWS SDK feature coverage or complete R2 parity with AWS. Commercial pricing properties are non-architectural and excluded.
    - *Adapters:* `LocalFilesystemStorageAdapter` and `S3CompatibleObjectStorageAdapter` (AWS S3 & Cloudflare R2 target endpoints, plus generic S3 endpoints with capability negotiation).
    - *Storage Topology Policy:* Configurable as `LOCAL`, `REMOTE`, or `MIRRORED` (requires both local and remote completion before marking `AVAILABLE`).
  - **Backup Classification & Restore Scope Safety:**
    - **`ROOT_BACKUP`:** Targets a single stable gameplay root (`PrimaryGameplayRootRef`: Island, Vessel, Course, etc.). Restores only the target root and its explicitly declared dependent state. It **NEVER** triggers a full database restore.
    - **`DATABASE_DISASTER_BACKUP`:** Catastrophic whole-database disaster recovery via `DatabaseBackupPort`. A whole database is NEVER restored as an implicit side effect of an individual root rollback (`/is admin rollback`).
  - **Orthogonal Consistency Taxonomy:**
    - *Backup Lifecycle / Operational State:* `PLANNED`, `CAPTURING`, `STAGED`, `UPLOADING`, `VERIFYING`, `AVAILABLE`, `PARTIAL`, `FAILED`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`.
    - *Capture Coordination Mechanism / Evidence:* `QUIESCED` (capture performed during a bounded mutation-quiesce window, without unverified numeric latency constants), `VERSION_FENCED`, `TRANSACTION_SNAPSHOT`, `ROOT_MUTATION_FENCE`.
    - *Consistency Guarantee:* `FULL_RESTORE_CONSISTENT`, `ROOT_CONSISTENT`, `BEST_EFFORT / PARTIAL_NOT_RESTORABLE` (partial captures are never advertised as restorable unless an explicit restore mode supports that partial artifact set).
  - **Publication & Recoverable Deletion Ordering (Marker / Tombstone Protocol):**
    - *Publication Ordering:* (1) Capture artifacts $\to$ (2) Upload artifacts $\to$ (3) Publish immutable manifest $\to$ (4) Verify all required artifacts/checksums $\to$ (5) Publish `AVAILABLE.marker` LAST $\to$ (6) SQL `BackupCatalogRecord` transitions to `AVAILABLE`.
    - *Deletion Ordering:* (1) SQL `BackupCatalogRecord` transitions to `DELETING` $\to$ (2) Remove/invalidate `AVAILABLE.marker` FIRST (or publish explicit deletion tombstone) $\to$ (3) Perform per-destination idempotent artifact cleanup $\to$ (4) Remove manifest when destination policy allows $\to$ (5) SQL transitions to `DELETED` only when configured deletion policy is satisfied across all destinations.
    - *Invariant:* Once deletion intent is durably published for a destination, that `BackupSet` ceases to appear as an `AVAILABLE` restore candidate, even if some artifacts still remain on storage and SQL is lost.
    - *No Cross-Destination Atomicity:* No distributed ACID transaction spans local filesystems, AWS S3, Cloudflare R2, and generic object stores. In `MIRRORED` backups, each destination tracks its marker publication and cleanup independently; partial destination failures persist progress and safely retry without impossible atomic rollbacks.
  - **Cryptographic Checksum & Integrity:**
    - Relying solely on provider `ETag`s is prohibited. Application-level **SHA-256** checksums are computed and persisted for every artifact.
  - **Safety-Critical Fail-Closed Restore Pipeline:**
    $$\text{Locate BackupSet} \to \text{Verify Manifest} \to \text{Verify Compatibility} \to \text{Download Artifacts} \to \text{Verify Checksums} \to \text{Fence Authority} \to \text{Enter Quarantine} \to \text{Restore State/World} \to \text{Reconciliation} \to \text{Commit Version/Authority} \to \text{Return Active}$$
    - Pipeline fails closed if: (1) any required artifact is missing; (2) availability marker is missing or tombstoned; (3) any SHA-256 checksum mismatches; (4) schema/plugin/mode version is incompatible; (5) target authority epoch or DB version changes during restore preparation; (6) required storage provider capability is unavailable.
    - Best-effort destructive partial restore is strictly forbidden.
  - **Retention Policy (`RetentionPolicy`):**
    - Evaluated strictly on complete `BackupSet`s (`keep-last-n`, `age-based`, `scheduled-generations`, `protected-pins`).
  - **Credential Security:**
    - Access keys, secret keys, and tokens are treated as infrastructure secrets. Never stored in domain models, manifests, database rows, logs, or diagnostic dumps. All diagnostic logging sanitizes and redacts credentials.
  - **Large Object Streaming:**
    - Direct `byte[]` in-memory loading of multi-gigabyte files is prohibited. Transfers use bounded streaming buffers, disk staging, and multipart upload chunks.

### 2.16 Temporary Access Grants (`temporary_access_grants`, `temporary_access_grant_permissions`)
Persists temporary trust/co-op grants across player sessions and server restarts with distributed termination anchors:
```sql
CREATE TABLE temporary_access_grants (
    grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
    instance_id VARCHAR(36) NOT NULL,
    target_root_type_id VARCHAR(64) NOT NULL, -- Namespaced root type: uxm:island, uxm:vessel, uxm:course_plot, etc.
    target_root_key VARCHAR(128) NOT NULL, -- Fully qualified root aggregate key (never assumed to be an Island UUID)
    grantee_profile_id VARCHAR(36) NOT NULL,
    granted_by_profile_id VARCHAR(36) NOT NULL,
    termination_policy VARCHAR(32) NOT NULL, -- UNTIL_REVOKED, UNTIL_SESSION_END, NODE_PROCESS_RESTART, UNTIL_TIMESTAMP
    anchor_player_uuid VARCHAR(36) NULL, -- Required for UNTIL_SESSION_END
    anchor_session_epoch BIGINT NULL, -- Tied to player_sessions.session_epoch to prevent session reuse across logins
    anchor_node_id VARCHAR(64) NULL, -- Node ID anchor for node-local NODE_PROCESS_RESTART policy
    anchor_process_generation_id VARCHAR(64) NULL, -- Process boot generation ID for NODE_PROCESS_RESTART
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, REVOKED, EXPIRED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_temp_grant_instance FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE,
    CONSTRAINT fk_temp_grant_grantee FOREIGN KEY (grantee_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_temp_grant_grantor FOREIGN KEY (granted_by_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
    -- Enforces ownership: anchor player must own the grantee profile
    CONSTRAINT fk_temp_grant_anchor_profile FOREIGN KEY (anchor_player_uuid, grantee_profile_id)
        REFERENCES player_profiles (player_uuid, profile_id)
);

CREATE INDEX idx_temp_grants_grantee ON temporary_access_grants (grantee_profile_id, state);
CREATE INDEX idx_temp_grants_instance ON temporary_access_grants (instance_id, state);

CREATE TABLE temporary_access_grant_permissions (
    grant_id VARCHAR(36) NOT NULL,
    permission_key VARCHAR(128) NOT NULL, -- Stable namespaced PermissionKey (e.g. uxm:container.open)
    PRIMARY KEY (grant_id, permission_key),
    CONSTRAINT fk_temp_grant_perms FOREIGN KEY (grant_id)
        REFERENCES temporary_access_grants (grant_id) ON DELETE CASCADE
);
```
* **Exact Expiry Predicates & Distributed Termination Semantics:**
  - `UNTIL_SESSION_END`: Session-bound access evaluated against canonical player session authority:
    - **Anchor Invariant:** `anchor_player_uuid` MUST belong to `grantee_profile_id` (enforced relationally by `fk_temp_grant_anchor_profile`).
    - **Exact Validity Predicate:** A grant remains session-valid if and only if canonical `player_sessions` represents the *exact same live session generation* identified by `(anchor_player_uuid, anchor_session_epoch)`:
      1. **Session row absent** in `player_sessions` $\implies$ **EXPIRED**.
      2. **Session epoch different** (`player_sessions.session_epoch != temporary_access_grants.anchor_session_epoch`) $\implies$ **EXPIRED**.
      3. **Same epoch but session is not actively live** (e.g. `state != 'ACTIVE'` such as `DRAINING`, `HANDOFF_READY`, `RECOVERING`, `OFFLINE`, `LOCAL_FENCED`, or `lease_expires_at < CURRENT_TIMESTAMP`) $\implies$ **EXPIRED**.
    - Reuses approved canonical session lifecycle/lease semantics directly; never invents new session states or lifecycles.
  - `NODE_PROCESS_RESTART`: Node-local convenience policy:
    - **Node Runtime Identity:** Evaluated against canonical node-local runtime identity: `CurrentNodeProcessIdentity(nodeId, processGenerationId)`.
    - **Exact Validity Predicate:** `current node/process identity != stored anchor identity` (i.e. `currentNodeProcessIdentity.nodeId() != anchor_node_id || !currentNodeProcessIdentity.processGenerationId().equals(anchor_process_generation_id)`) $\implies$ **EXPIRED**.
    - Strictly a node-local policy that does NOT become distributed authority and does NOT require Redis for correctness.
  - `UNTIL_REVOKED`: Persists until explicitly marked `REVOKED` by an authorized profile.
  - `UNTIL_TIMESTAMP`: Evaluated against canonical database clock (`CURRENT_TIMESTAMP > expires_at` $\implies$ **EXPIRED**).
* **Separation Invariant:** `TemporaryAccessGrant` is strictly distinct from `GameModeParticipation` and `IslandMember`. A temporary grant grants temporary permission sets without conferring team ownership, voting rights, bank access, or permanent member status.

### 2.17 Durable Reward Grants & Component Progress (`reward_grants`, `reward_grant_components`)
Decouples reward generation from live physical inventory insertion, orchestrating component delivery across established economic and domain protocols.

* **Identity Hierarchy & Isolation Invariant:**
  - `RewardGrantId`: Parent aggregate / correlation identity for the overall reward package.
  - `RewardComponentOperationId`: Durable idempotency identity of one specific component delivery.
  - **Isolation Invariant:** Different reward components MUST NOT blindly reuse the parent grant ID across `InventoryMutationJournal`, `processed_operations`, SQL economic OCC, or external economy sagas.
  - **Component Operation Stability:** Each component receives one stable operation ID (`component_operation_id`) identical across retries of that component, deterministically derived from `grantId + componentIndex` (or explicitly persisted).
  - Invariant:
    - `same grant + same component` $\implies$ `same component operation ID`
    - `different components` $\implies$ `different component operation IDs`
  - Relational uniqueness: `UNIQUE (grant_id, component_index)` and `UNIQUE (component_operation_id)`.
  - Protocol operation references (`journal_operation_id` or equivalent) MUST represent the component's operation (`component_operation_id`), never ambiguously the parent grant ID.
  - Parent `RewardGrant` transitions to `CLAIMED` only when all required components are durably completed (`state = 'COMMITTED'`).

```sql
CREATE TABLE reward_grants (
    grant_id VARCHAR(36) NOT NULL PRIMARY KEY, -- Parent aggregate / correlation identity
    recipient_profile_id VARCHAR(36) NOT NULL,
    source_type VARCHAR(64) NOT NULL, -- missions, challenges, seasons, admin_compensation, events, votes, progression, social
    source_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, CLAIMING, CLAIMED, EXPIRED, RECOVERY_REQUIRED
    claimed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reward_grant_recipient FOREIGN KEY (recipient_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_reward_grants_recipient ON reward_grants (recipient_profile_id, state);

CREATE TABLE reward_grant_components (
    component_id VARCHAR(36) NOT NULL PRIMARY KEY,
    grant_id VARCHAR(36) NOT NULL,
    component_index INT NOT NULL,
    component_operation_id VARCHAR(36) NOT NULL, -- Durable idempotency identity of one component delivery
    component_type VARCHAR(32) NOT NULL, -- ITEM, SQL_CURRENCY, EXTERNAL_VAULT, COSMETIC
    payload_type_id VARCHAR(64) NOT NULL, -- Namespaced payload identifier
    payload_schema_version INT NOT NULL DEFAULT 1,
    payload_data TEXT NOT NULL, -- Strongly typed, schema-validated versioned payload
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING, COMMITTED, FAILED
    journal_operation_id VARCHAR(36) NULL, -- Protocol-operation reference representing component_operation_id
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reward_comp_grant FOREIGN KEY (grant_id)
        REFERENCES reward_grants (grant_id) ON DELETE CASCADE,
    CONSTRAINT uq_reward_grant_comp_idx UNIQUE (grant_id, component_index),
    CONSTRAINT uq_reward_grant_comp_op UNIQUE (component_operation_id)
);

CREATE INDEX idx_reward_comp_grant ON reward_grant_components (grant_id, state);
CREATE UNIQUE INDEX idx_reward_comp_op ON reward_grant_components (component_operation_id);
```
* **Protocol Orchestration Architecture:**
  - `RewardClaimCoordinator` orchestrates delivery by dispatching each component to its owning, already-approved protocol using `component_operation_id`:
    1. **Minecraft / Durable Economic Inventory Items:** Executed through `InventoryMutationJournal` (expected version, OCC, authority fencing, before/after fingerprints, keyed by `component_operation_id`).
    2. **SQL-Owned Currencies & Bank Balances:** Executed through canonical economic OCC + `processed_operations` / `bank_transactions` (keyed by `component_operation_id`).
    3. **External Economy Side Effects:** Executed through existing saga / idempotency recovery contracts (keyed by `component_operation_id`).
    4. **Cosmetics / Permissions / Non-Economic Rewards:** Delivered via their respective owning bounded contexts.
  - **Crash Resilience:** Per-component state is tracked in `reward_grant_components`. If a server crashes mid-claim, completed components are never re-granted, preventing item duplication. The overall `RewardGrant` transitions to `CLAIMED` only after all components are durably completed.
  - Does NOT invent a new transaction protocol; reuses existing approved protocols strictly.

### 2.18 Durable Offline Notifications (`notifications`)
Stores profile-scoped offline alerts delivered upon player login:
```sql
CREATE TABLE notifications (
    notification_id VARCHAR(36) NOT NULL PRIMARY KEY,
    recipient_profile_id VARCHAR(36) NOT NULL,
    category VARCHAR(32) NOT NULL, -- INVITE, KICK, ROLE_CHANGED, TRUST_GRANTED, TRUST_REVOKED, REWARD_AVAILABLE, BANK_ACTIVITY, MODERATION, RESET, SYSTEM
    payload_type_id VARCHAR(64) NOT NULL, -- Namespaced notification payload identifier
    payload_schema_version INT NOT NULL DEFAULT 1,
    payload_data TEXT NOT NULL, -- Strongly typed, schema-validated versioned payload
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_notifications_recipient ON notifications (recipient_profile_id, is_read, created_at DESC);
```
* **Transactional Outbox Delivery:** Asynchronous domain notifications are written via the transactional outbox (`outbox_events`). Relational SQL is the canonical source of truth; Redis Pub/Sub accelerates real-time online delivery only.

### 2.19 Activity Feed Events (`activity_events`)
Maintains a user-facing event digest for islands and co-ops:
```sql
CREATE TABLE activity_events (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    instance_id VARCHAR(36) NOT NULL,
    actor_profile_id VARCHAR(36) NULL, -- NULL for system-dispatched events
    event_type VARCHAR(64) NOT NULL, -- MEMBER_JOINED, MEMBER_LEFT, ROLE_CHANGED, TRUST_GRANTED, TRUST_REVOKED, BANK_DEPOSIT, BANK_WITHDRAW, UPGRADE_PURCHASED, BOOSTER_ACTIVATED, WARP_CREATED, TEMPLATE_APPLIED, MISSION_COMPLETED
    visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBERS_ONLY', -- PUBLIC, MEMBERS_ONLY, PRIVATE
    payload_type_id VARCHAR(64) NOT NULL,
    payload_schema_version INT NOT NULL DEFAULT 1,
    payload_data TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_activity_instance FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_activity_events_instance ON activity_events (instance_id, created_at DESC);
```
* **Outbox-Driven Projection:** Activity events are projected through the transactional outbox pipeline:
  $$\text{Domain Commit} \longrightarrow \text{Durable Outbox Event} \longrightarrow \text{Retryable Activity Projection}$$
  Projection failures do not roll back domain business transactions AND do not silently drop activity events.

### 2.20 Social & Discovery Subsystem (`social_ratings`, `guestbook_reviews`, `subject_visits`, `social_bookmarks`)
Provides cross-archetype discovery and social interaction keyed by generic `SocialSubjectRef`:
```sql
CREATE TABLE social_ratings (
    subject_type_id VARCHAR(64) NOT NULL, -- Namespaced subject type: uxm:island, uxm:course, uxm:plot, etc.
    subject_key VARCHAR(128) NOT NULL, -- Fully qualified subject identifier
    rater_profile_id VARCHAR(36) NOT NULL,
    score INT NOT NULL, -- Abstract numeric score interpreted by RatingPolicy
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_type_id, subject_key, rater_profile_id),
    CONSTRAINT fk_social_ratings_rater FOREIGN KEY (rater_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_social_ratings_subject ON social_ratings (subject_type_id, subject_key);

CREATE TABLE guestbook_reviews (
    review_id VARCHAR(36) NOT NULL PRIMARY KEY,
    subject_type_id VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    author_profile_id VARCHAR(36) NOT NULL,
    message VARCHAR(256) NOT NULL,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_guestbook_author FOREIGN KEY (author_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_guestbook_subject ON guestbook_reviews (subject_type_id, subject_key, created_at DESC);

CREATE TABLE subject_visits (
    subject_type_id VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    visitor_profile_id VARCHAR(36) NOT NULL,
    visit_count INT NOT NULL DEFAULT 1,
    first_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (subject_type_id, subject_key, visitor_profile_id),
    CONSTRAINT fk_subject_visits_visitor FOREIGN KEY (visitor_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_subject_visits_subject ON subject_visits (subject_type_id, subject_key);

CREATE TABLE social_bookmarks (
    profile_id VARCHAR(36) NOT NULL,
    subject_type_id VARCHAR(64) NOT NULL,
    subject_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (profile_id, subject_type_id, subject_key),
    CONSTRAINT fk_bookmarks_profile FOREIGN KEY (profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE
);

CREATE INDEX idx_social_bookmarks_subject ON social_bookmarks (subject_type_id, subject_key);
```
* **Decoupled Subject Identity & Neutral Rating Policy:**
  - Social subjects are identified by `(subject_type_id, subject_key)`, never assuming social subject == `GameModeInstance`. A game mode instance may host multiple discoverable subjects.
  - Concrete scoring scales and aggregation formulas are governed by `RatingPolicy` / `RatingAggregationPolicy`. Unapproved Bayesian 1-5 star hardcodings are excluded from the core relational model.

### 2.21 Player Multi-Home Subsystem (`player_homes`)
Persists profile-scoped homes referencing stable domain world identities:
```sql
CREATE TABLE player_homes (
    home_id VARCHAR(36) NOT NULL,
    owner_profile_id VARCHAR(36) NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    home_name VARCHAR(32) NOT NULL,
    dimension_instance_id VARCHAR(36) NOT NULL, -- Canonical stable WorldRef / dimension instance ID
    x DOUBLE PRECISION NOT NULL,
    y DOUBLE PRECISION NOT NULL,
    z DOUBLE PRECISION NOT NULL,
    yaw REAL NOT NULL,
    pitch REAL NOT NULL,
    scope VARCHAR(32) NOT NULL DEFAULT 'PERSONAL', -- PERSONAL, CO_OP
    display_world_name VARCHAR(64) NULL, -- Non-authoritative diagnostic / display label
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_profile_id, home_name),
    CONSTRAINT fk_player_homes_profile FOREIGN KEY (owner_profile_id)
        REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
    CONSTRAINT fk_player_homes_instance FOREIGN KEY (instance_id)
        REFERENCES game_mode_instances (instance_id) ON DELETE CASCADE,
    CONSTRAINT fk_player_homes_dim FOREIGN KEY (dimension_instance_id)
        REFERENCES game_mode_dimension_instances (dimension_instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_player_homes_instance ON player_homes (instance_id);
```
* **Stable World Identity Invariant:** `player_homes` references `dimension_instance_id` as its canonical domain world anchor. Bukkit/Paper `world_name` mapping is delegated entirely to platform adapters; changes in runtime world naming do not corrupt persisted player homes.

### 2.22 Non-Canonical Projections & Runtime Optimizations
* **Compiled Permission Dense IDs:** Dense integer IDs (0..N) are purely in-memory runtime compilation artifacts. Only stable namespaced keys (`PermissionKey`) are persisted in relational tables and configuration files.
* **Leaderboard Source-of-Truth Invariant:** The Leaderboard subsystem does NOT become the canonical source of truth for every metric merely because it ranks it. `LeaderboardMetricProvider` exposes the authoritative source/read contract (e.g. Level context owns `uxm:level`, Economy context owns `uxm:bank_balance`, Social context owns `uxm:rating`, external plugins own custom metrics). Leaderboard SQL materializations and Redis Sorted Sets are non-canonical projections/caches.
* **Routing & Placement States:** Ephemeral routing directories (`HOSTED`, `MOVING`, `UNHOSTED`) and node placement recommendations are cached in Redis and memory. They **NEVER grant authority or override SQL authority fencing leases**.

---

---

## 3. High-Performance Indexing & Query Isolation

To support **100,000+ players** and **250,000+ persisted islands**:
1. **Unload-on-Idle Flushing:** When an island aggregate unloads, dirty state flushes in a single atomic SQL transaction with version and epoch fencing.
2. **Covering Indexes for Fast Routing:** `idx_player_profiles_player` and `idx_islands_owner` provide microsecond index lookups for player login routing without scanning heavy blob columns.
3. **Segregated Inventory & Vault Blobs:** Player inventories (`profile_inventories`) and island vaults (`island_vault_pages`) are placed in segregated tables so routine island metadata queries never read megabytes of binary data over the network socket.
4. **Outbox Polling Index:** `idx_outbox_events_status_created` ensures the background Redis Streams dispatcher queries pending rows with zero table-scan penalties (`SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 100`).

---

## 4. Multi-Dialect Compatibility Matrix & Migration Organization

The migration sets are partitioned by database dialect:
* `migrations/sqlite/` — Standalone single-node deployments.
* `migrations/mysql/` — Clustered high-throughput deployments.
* `migrations/mariadb/` — Clustered high-throughput deployments.
* `migrations/postgresql/` — Enterprise clustered deployments.

| Dimension | SQLite | MySQL | MariaDB | PostgreSQL |
| :--- | :--- | :--- | :--- | :--- |
| **Standalone Mode** | Supported | Supported | Supported | Supported |
| **Cluster Mode** | Unsupported | Supported | Supported | Supported |
| **Authority Lease (`island_authorities`)** | Single-Writer `BEGIN IMMEDIATE` (no `SELECT ... FOR UPDATE` or `SKIP LOCKED`; multi-node unsupported) | InnoDB Row Lock | InnoDB Row Lock | MVCC / `FOR UPDATE` |
| **Outbox Streaming** | Durable Table + Local Dispatch (Option A) | InnoDB Table + Worker | InnoDB Table + Worker | Table + `SKIP LOCKED` |
| **JSON Payload** | `TEXT` (JSON1) | `JSON` | `LONGTEXT` / `JSON` | `JSONB` |
| **Binary NBT** | `BLOB` | `MEDIUMBLOB` | `MEDIUMBLOB` | `BYTEA` |
| **Monetary & Numeric Amounts** | `BIGINT` (exact minor units) — `REAL`/floating-point is STRICTLY BANNED | `BIGINT` (minor units) or declared `DECIMAL(18, scale)` | `BIGINT` (minor units) or declared `DECIMAL(18, scale)` | `BIGINT` (minor units) or declared `NUMERIC(18, scale)` |
| **Timestamps** | `TEXT` (ISO-8601) | `TIMESTAMP` | `TIMESTAMP` | `TIMESTAMPTZ` |
| **Migration Set** | `migrations/sqlite/` | `migrations/mysql/` | `migrations/mariadb/` | `migrations/postgresql/` |

* **SQLite Concurrency, Single-Writer Locking & Exact Economic Representation:**
  - **No Row-Level Locking:** SQLite does NOT support `SELECT ... FOR UPDATE` or `FOR UPDATE SKIP LOCKED`.
  - **Single-Writer Serialization via `BEGIN IMMEDIATE`:** All mutating transactions on SQLite MUST begin with `BEGIN IMMEDIATE`. This immediately acquires a `RESERVED` lock on the database file, preventing any concurrent writer from starting a transaction.
  - **Busy Timeout Queueing:** Concurrency is managed via `PRAGMA busy_timeout = 1000;`. Concurrent transactions wait up to 1000ms for the active writer to commit. If the timeout expires, `SQLiteBusyException` (`SQLITE_BUSY`) is raised and triggers a fail-closed retry.
  - **Strict Prohibition of `REAL` Floating-Point Decimals & Exact Minor Units Model:** Under SQLite, floating-point `REAL` columns for currency or financial state (`island_banks.primary_balance_minor_units`, `islands.net_worth_minor_units`) are strictly **BANNED** due to IEEE 754 rounding drift and truncation exploits. Across all dialects, monetary amounts are canonically modeled as `amountMinorUnits` (`BIGINT`) + `currencyId` + `currencyScale` provider metadata. Currencies with non-2 scales (e.g. 0-scale tokens or 4-scale micro-units) are natively supported without silent forced 2-decimal truncation.
  - **Authority Leases in Standalone Mode:** Single-node SQLite deployments operate without distributed clustering. The local node is the sole authority holder and verifies authority epoch and lease state within the `BEGIN IMMEDIATE` transaction boundary. Multi-node clustered deployments targeting SQLite are unsupported.

* **SQLite Outbox Architectural Policy & Local Duplicate Delivery Contract:**
  - In single-node standalone SQLite deployments, domain events are durably inserted into the local `outbox_events` table within the same atomic transaction (`BEGIN IMMEDIATE ... COMMIT`).
  - A local background thread polls `outbox_events` and dispatches records asynchronously to the in-process event bus.
  - **Duplicate Delivery Invariant:** If the server process crashes after a local event listener executes but before `outbox_events` is marked `PROCESSED`, the event will be re-dispatched upon server reboot. Local listeners and consumers MUST be idempotent or track processed event IDs via SQLite `consumer_inbox`. *In-process execution does NOT imply exactly-once delivery across crashes.*

### 4.1 Logical Database Timeout Contracts & Dialect Implementation Matrix

To ensure deterministic transaction bounds and prevent thread starvation, the persistence tier defines four abstract logical timeout deadlines:

1. **`LOCK_ACQUISITION_DEADLINE`**: Maximum duration a statement may block waiting to acquire a row lock before aborting.
2. **`STATEMENT_DEADLINE`**: Maximum server execution time allowed for an individual SQL statement.
3. **`SOCKET_IO_DEADLINE`**: Maximum duration the JDBC driver will block waiting for packet data over the physical network socket.
4. **`TRANSACTION_DEADLINE`**: Overall end-to-end deadline enforced by the application watchdog across the entire transactional sequence.

| Dimension / Deadline | PostgreSQL 15+ | MySQL 8.0+ | MariaDB 10.6+ | SQLite 3.40+ (WAL Mode) |
| :--- | :--- | :--- | :--- | :--- |
| **`LOCK_ACQUISITION_DEADLINE`** | `SET lock_timeout = 1000;` (1000ms) | `SET innodb_lock_wait_timeout = 1;` (1 sec) | `SET innodb_lock_wait_timeout = 1;` (1 sec) | `PRAGMA busy_timeout = 1000;` (1000ms) |
| **`STATEMENT_DEADLINE`** | `SET statement_timeout = 2000;` (2000ms, applies to all SQL) | `SET max_execution_time = 2000;` (*SELECT only*); DML cancellation via `Statement.setQueryTimeout(2)` | `SET max_statement_time = 2.0;` (2 sec, applies to all statements) | Application watchdog timer (`sqlite3_interrupt`) |
| **`SOCKET_IO_DEADLINE` (Driver Unit)** | `socketTimeout = 5` (*Unit: Seconds* in pgjdbc) | `socketTimeout = 5000` (*Unit: Milliseconds* in Connector/J) | `socketTimeout = 5000` (*Unit: Milliseconds* in MariaDB Client) | N/A (In-process memory/file) |
| **Idle-in-Transaction Severance** | `idle_in_transaction_session_timeout = 5000;` | `wait_timeout = 10;` / `interactive_timeout = 10;` | `wait_timeout = 10;` / `interactive_timeout = 10;` | N/A (In-process connection) |
| **TCP Keepalive Probing** | `keepalives_idle=2`, `keepalives_interval=1`, `keepalives_count=3` | OS TCP keepalive (`TCP_KEEPIDLE=2`, `TCP_KEEPINTVL=1`, `TCP_KEEPCNT=3`) | OS TCP keepalive (`TCP_KEEPIDLE=2`, `TCP_KEEPINTVL=1`, `TCP_KEEPCNT=3`) | N/A |
| **`TRANSACTION_DEADLINE` (Watchdog)** | 2000ms Application Watchdog | 2000ms Application Watchdog | 2000ms Application Watchdog | 2000ms Application Watchdog |
| **Lock Ownership Severance Mechanism** | Session termination rolls back Tx; releases `FOR UPDATE` lock | Connection drop rolls back Tx; releases InnoDB row lock | Connection drop rolls back Tx; releases InnoDB row lock | Process death releases OS POSIX/Win32 file lock |
| **Waiter Failure Outcome** | `LockTimeoutException` (55P03) $\to$ Fail-closed retry | `LockWaitTimeoutException` (1205) $\to$ Fail-closed retry | `LockWaitTimeoutException` (1205) $\to$ Fail-closed retry | `SQLiteBusyException` (SQLITE_BUSY) $\to$ Fail-closed retry |

* **MySQL DML Statement Deadline & Cancellation Semantics:**
  - MySQL server's `max_execution_time` session variable applies **strictly to read-only `SELECT` statements**.
  - For mutating DML statements (`UPDATE island_banks`, `UPDATE island_authorities`), statement deadlines are enforced via JDBC driver-side query timeout (`Statement.setQueryTimeout(2)`).
  - MySQL Connector/J does NOT close the socket by default; it dispatches an out-of-band `KILL QUERY <id>` command via an auxiliary thread.
  - **Cancellation & Unknown Outcome Adapter Contract:**
    1. If cancellation succeeds and the statement throws `SQLTimeoutException`: the adapter issues `conn.rollback()` and releases the connection safely to the pool.
    2. If cancellation fails, the socket blocks past `SOCKET_IO_DEADLINE`, or the transaction execution outcome becomes uncertain: the physical connection is immediately aborted/closed and evicted from HikariCP (`hikariDataSource.evict(conn)`).
    3. The operation transitions to `TRANSACTION_OUTCOME_UNKNOWN` and is reconciled through the scoped idempotency / recovery journal pipeline.
    4. *Note:* `queryTimeoutKillsConnection=true` is an optional explicit driver connection property, not default driver behavior.
* **Application Watchdog Deadline & Uncertain Transaction Eviction:**
  Exceeding the 2000ms `TRANSACTION_DEADLINE` on an asynchronous virtual thread does **NOT** mean the database server cancelled or rolled back the in-flight transaction. If the connection cannot be cleanly rolled back or its socket status is ambiguous, the physical connection is forcibly evicted from HikariCP to prevent leaking tainted or uncommitted state.

### 4.2 Production Durability Profiles (`DURABLE_PRODUCTION`)

The assertion of durably committed transactions requires that the underlying database engine is operating in an approved crash-durable configuration:

* **Durability Guarantee Standard:**
  > A successful database commit is considered durable only when the selected persistence adapter is running under its validated `DURABLE_PRODUCTION` profile and within the durability guarantees documented by that database/storage stack.
  > *Boundary Notice:* This software durability guarantee relies on operating system, storage controller, RAID, and underlying physical/cloud storage write-barrier adherence and does not exceed the hardware failure semantics documented by the database engine vendor.

* **Mandatory Dialect Settings for `DURABLE_PRODUCTION`:**
  - **PostgreSQL 15+:**
    - `fsync = on`
    - `synchronous_commit = on` (or `remote_apply` for synchronous multi-node replication)
    - All business tables created as regular `LOGGED` tables (unlogged tables strictly prohibited for financial/state tables).
  - **MySQL 8.0+ / MariaDB 10.6+:**
    - Storage engine strictly `InnoDB`.
    - `innodb_flush_log_at_trx_commit = 1` (forces write + fsync of redo log at commit).
    - If binary logging is enabled: `sync_binlog = 1` (forces fsync of binary log at commit).
  - **SQLite 3.40+ (Standalone Mode):**
    - `PRAGMA journal_mode = WAL;` (Write-Ahead Logging).
    - `PRAGMA synchronous = FULL;` (guarantees fsync to disk on transaction commit).
    - *Durability Rejection:* `PRAGMA synchronous = NORMAL;` is strictly **REJECTED** under `DURABLE_PRODUCTION` because an OS crash or power outage can forfeit recently committed transactions. `synchronous = NORMAL` is segregated strictly to the `PERFORMANCE_RELAXED` profile for ephemeral test environments.

* **Startup Configuration Detection & Fail-Fast Scope:**
  During plugin boot, `DatabaseMigrator` / `StorageModule` inspects the active database engine variables:
  - If a non-durable configuration is detected (e.g. `innodb_flush_log_at_trx_commit != 1`, `synchronous_commit = off`, or SQLite `synchronous != FULL`):
    - In `DEVELOPMENT` mode: Emits a high-visibility warning log (`NON_DURABLE_STORAGE_CONFIG_DETECTED`).
    - In `PRODUCTION_STRICT` mode: **`uxmSkyblock` plugin enable FAILS** immediately, throwing `FatalDurabilityConfigurationException` and disabling the plugin to prevent data corruption. (The plugin does NOT claim to forcibly kill the entire Minecraft server JVM; process management remains with the host watchdog/systemd).


---

## 5. End-to-End Atomic Transactional Pipeline & 0 Rows Invariant

```mermaid
sequenceDiagram
    participant Domain as IslandBankService
    participant SQL as SQL Connection (TxSql)
    participant Outbox as outbox_events
    participant Worker as OutboxDispatcher (Async)
    participant Redis as Redis Streams (uxmskyblock:stream:bank)
    participant Consumer as RemoteNode (Consumer)
    participant Inbox as consumer_inbox (Remote SQL)

    Note over Domain,SQL: Atomic Transaction Boundary (Commit-Time Serialized)
    Domain->>SQL: START TRANSACTION
    Domain->>SQL: INSERT INTO processed_operations (scope, actor_id, key, status='PENDING')
    Domain->>SQL: SELECT authoritative_node, authority_epoch, lease_expires_at FROM island_authorities WHERE island_id = :id FOR UPDATE
    alt Authority Valid & Active Lease (node==expected && epoch==expected && lease > NOW())
        Domain->>SQL: UPDATE island_banks SET primary_balance = primary_balance + :delta, version = version + 1 WHERE island_id = :id AND version = :expectedVersion
        alt Affected Rows == 1 (Success)
            Domain->>SQL: INSERT INTO bank_transactions (...)
            Domain->>SQL: INSERT INTO outbox_events (event_id, ..., status='PENDING')
            Domain->>SQL: UPDATE processed_operations SET status='APPLIED', result_code='SUCCESS', completed_at=NOW()
            Domain->>SQL: COMMIT TRANSACTION
        else Affected Rows == 0 (Stale Version / Insufficient Funds)
            Domain->>SQL: UPDATE processed_operations SET status='REJECTED', result_code=:failureCode, completed_at=NOW()
            Domain->>SQL: COMMIT TRANSACTION
            Note over Domain,SQL: Deterministic rejection persisted in SAME transaction! (Audit & Outbox NOT created)
        end
    else Authority Invalid / Expired / Wrong Node
        Domain->>SQL: UPDATE processed_operations SET status='REJECTED', result_code='STALE_AUTHORITY_EPOCH', completed_at=NOW()
        Domain->>SQL: COMMIT TRANSACTION
        Note over Domain,SQL: Rejection persisted atomically; takeover blocked by row lock until commit!
    end

    Note over Worker,Redis: Two-Phase Outbox Dispatch (Row Locks NOT Held Across Network I/O)
    loop Every 50ms (Background Worker)
        Worker->>Outbox: TX1: SELECT ... (FOR UPDATE SKIP LOCKED / BEGIN IMMEDIATE); UPDATE status='CLAIMED', claim_owner=:id, claim_expires_at=DB_NOW_PLUS(30); COMMIT;
        Worker->>Redis: Network I/O: XADD uxmskyblock:stream:bank * event_id payload
        Worker->>Outbox: TX2: UPDATE outbox_events SET status='PROCESSED', processed_at=NOW() WHERE event_id=:id AND claim_owner=:id; COMMIT;
    end

    Note over Redis,Consumer: Idempotent Consumer Processing (Dialect-Safe)
    Redis->>Consumer: XREADGROUP (...) event_id payload
    Consumer->>Inbox: START TRANSACTION; Dialect-Safe Conditional Insert (e.g. ON CONFLICT DO NOTHING / ON DUPLICATE KEY / INSERT OR IGNORE);
    alt Affected Rows == 1 (First Delivery)
        Consumer->>Inbox: Apply Local State Mutation / Projection; COMMIT;
        Consumer->>Redis: XACK uxmskyblock:stream:bank group event_id
    else Affected Rows == 0 (Duplicate Delivery)
        Consumer->>Inbox: COMMIT; (Skip side-effects)
        Consumer->>Redis: XACK uxmskyblock:stream:bank group event_id
    end
```

### 5.1 Transaction Execution Pseudo-Code (Model A Idempotency & Canonical Lock)
```java
public TransactionOutcome executeBankTransaction(
        IslandId islandId,
        String nodeId,
        long expectedVersion,
        long expectedAuthorityEpoch,
        BigDecimal delta,
        String idempotencyKey,
        UUID actorId,
        UUID operationId) {

    try (Connection conn = dataSource.getConnection()) {
        conn.setAutoCommit(false);
        // Enforce operational transaction ceiling at session level
        setSessionTimeouts(conn, 2000 /* statementTimeoutMs */, 1000 /* lockTimeoutMs */);

        // 1. Model A Idempotency: Attempt to insert scoped unique operation key
        try {
            insertProcessedOperation(conn, operationId, "ISLAND_BANK", actorId.toString(), idempotencyKey, "BANK_MUTATION", islandId.toString());
        } catch (DuplicateKeyException dupEx) {
            // Concurrent duplicate: unique index blocked until winner committed
            conn.rollback();
            OperationRecord existing = findProcessedOperation(conn, "ISLAND_BANK", actorId.toString(), idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Unique collision occurred but record missing on read"));
            return TransactionOutcome.cached(existing.resultCode(), existing.resultPayload());
        } catch (LockTimeoutException lockEx) {
            // Winner held unique index lock longer than lockTimeoutMs (1000ms)
            conn.rollback();
            return TransactionOutcome.inProgress("IDEMPOTENCY_OPERATION_IN_PROGRESS");
        }

        // 2. Canonical Authority Row Lock Serialization
        // Lock canonical row unconditionally by island_id:
        AuthorityRow authority = lockCanonicalAuthorityRow(conn, islandId);

        // Application-level validation inside transaction
        Instant dbNow = queryDbCurrentTimestamp(conn);
        boolean isValid = authority != null
                && nodeId.equals(authority.authoritativeNode())
                && authority.authorityEpoch() == expectedAuthorityEpoch
                && authority.leaseExpiresAt().isAfter(dbNow);

        if (!isValid) {
            // Invariant: If invalid/expired/stolen, commit deterministic rejection in the SAME transaction!
            completeProcessedOperation(conn, operationId, OperationStatus.REJECTED, "STALE_AUTHORITY_EPOCH", null);
            conn.commit();
            return TransactionOutcome.rejected("STALE_AUTHORITY_EPOCH");
        }

        // 3. Critical business mutation guarded by OCC version check
        int affectedRows = updateBankBalance(conn, islandId, expectedVersion, delta);

        if (affectedRows != 1) {
            // 0 rows affected indicates OCC conflict or insufficient funds.
            // Persist rejection in the EXACT SAME transaction without inserting audit or outbox events!
            String rejectionReason = diagnoseBalanceOrVersionFailure(conn, islandId, expectedVersion, delta);
            completeProcessedOperation(conn, operationId, OperationStatus.REJECTED, rejectionReason, null);
            conn.commit();
            return TransactionOutcome.rejected(rejectionReason);
        }

        // 4. Record audit trail & outbox event (executed ONLY because affectedRows == 1)
        insertBankTransactionAudit(conn, operationId, islandId, delta);
        insertOutboxEvent(conn, operationId, islandId, "ISLAND_BANK_MUTATED", delta, expectedAuthorityEpoch);

        // 5. Update idempotency state to APPLIED
        completeProcessedOperation(conn, operationId, OperationStatus.APPLIED, "SUCCESS", buildSuccessPayload(delta));

        conn.commit();
        return TransactionOutcome.applied(operationId);
    } catch (SQLException ex) {
        rollbackQuietly(conn);
        return TransactionOutcome.failedRetryable(ex.getMessage());
    }
}
```

---

## 6. Dependency Ownership Policy & uxm-lib Subsystem Governance

### 6.1 Fundamental Dependency Rule — uxm-lib First
In UXPLIMA architecture, the core foundational infrastructure is centrally owned and maintained in the [`uxm-lib`](https://github.com/UXPLIMA/uxm-lib) repository.
* **Strict Architecture Invariant:** `uxmSkyblock` MUST NEVER bypass `uxm-lib` by directly introducing or coupling to third-party dependencies when an existing `uxm-lib` abstraction or module provides that capability.
* **Dependency Chain Standard:**
  $$\text{uxmSkyblock} \longrightarrow \text{uxm-lib Public Modules} \longrightarrow \text{Third-Party Implementation (Internalized)}$$

### 6.2 Subsystem Ownership Matrix

| Infrastructure Capability | uxmSkyblock Direct Ownership | Canonical uxm-lib Module | Final Architectural Policy |
| :--- | :--- | :--- | :--- |
| **Storage & Connection Pooling** | Forbidden direct HikariCP coupling | `uxmlib-storage` | Use `uxmlib-storage` database manager (`Database`), connection pool abstraction, and dialect-neutral SQL helpers (`Sql`, `TxSql`). |
| **Database Migration** | Forbidden direct Flyway / Liquibase | `uxmlib-storage` (`MigrationRunner`) | **Flyway is NOT used.** All schema migrations are executed natively via `MigrationRunner`. Unused Flyway metadata is eliminated. |
| **Redis & Ephemeral Transport** | Forbidden direct Jedis / Lettuce coupling | `uxmlib-redis` | Low-level binary `byte[]` Pub/Sub messaging (`LettuceRedisBus`, `RedisBus`). Streams transport is tracked as an architectural gap. |
| **Configuration & Localization** | Forbidden direct SnakeYAML / Configurate | `uxmlib-common` | Managed via `uxmlib-common` configuration abstractions (`HoconConfig`, `RecordConfig`, `ConfigProperty`). YAML/ConfigManager god-classes do not exist. |
| **In-Memory Cache & Utilities** | Forbidden rogue cache implementations | `uxmlib-storage` | Caffeine caching utilities (`Cache`, `CachedStorage`, `PlayerProfileCache`) live canonically in `uxmlib-storage`. |
| **External Plugin Integrations** | Forbidden direct Vault / PAPI hooks in core | `uxmlib-integration` | Vault economy, PlaceholderAPI, and permission adapters route via `uxmlib-integration`. |
| **Bedrock & Floodgate UI** | Forbidden direct Geyser / Floodgate hooks | `uxmlib-bedrock` / `uxmlib-menu` | Floodgate form GUIs and Bedrock player detection consume `uxmlib-bedrock` / `uxmlib-menu`. |
| **Metrics & Telemetry** | External dependency rejected | *UXM-LIB GAP* | Documented as `UXM-LIB CAPABILITY GAP — METRICS`. Direct bStats exception is reverted and blocked pending formal PO/library resolution. |
| **General Object Storage / S3** | Forbidden direct AWS SDK / HTTP client in core | *UXM-LIB GAP* | Documented as `UXM-LIB CAPABILITY GAP — GENERAL OBJECT STORAGE / S3-COMPATIBLE STORAGE`. Pure core defines neutral `ObjectStoragePort`. Remote S3-compatible adapter requires upstream `uxm-lib` module or explicit PO library exception. Direct AWS SDK coupling in pure core is prohibited. |

### 6.3 Tracked uxm-lib Capability Gaps
1. **`UXM-LIB CAPABILITY GAP — DURABLE REDIS STREAMS TRANSPORT`:**
   - **Required Capability:** Redis Streams consumer groups, durable stream publishing, and XACK acknowledgments.
   - **Current State:** `uxmlib-redis` provides binary `byte[]` Pub/Sub only (`LettuceRedisBus`, `RedisBus`).
   - **Architectural Policy:** Direct Lettuce Redis Streams code is strictly prohibited in `uxmSkyblock`. Canonical durability is guaranteed via SQL authority leasing, OCC, and transactional outbox.
2. **`UXM-LIB CAPABILITY GAP — METRICS`:**
   - **Required Capability:** Telemetry and server metrics reporting.
   - **Current State:** No metrics module exists in `uxm-lib`.
   - **Architectural Policy:** Direct bStats inclusion is reverted and rejected. Awaiting formal PO decision or `uxmlib-metrics`.
3. **`UXM-LIB CAPABILITY GAP — GENERAL OBJECT STORAGE / S3-COMPATIBLE STORAGE`:**
   - **Required Capability:** S3-compatible object storage client abstraction supporting streaming upload/download, multipart upload, bucket operations, pagination, range reads, and metadata queries (compatible with AWS S3 and Cloudflare R2).
   - **Current State:** `uxm-lib` 0.88.0 contains no module or capability for object storage, S3, blob storage, or cloud storage.
   - **Architectural Policy:** Direct AWS SDK or HTTP client coupling in pure core is strictly prohibited. Core defines neutral `ObjectStoragePort`. Infrastructure adapter relies on `uxm-lib` object storage capability. Classified as `BLOCKING BEFORE OBJECT-STORAGE IMPLEMENTATION` and required for V1 completion.

### 6.4 uxm-lib Capability Gap Escalation Process
If a required infrastructure capability is not supported by `uxm-lib`, developers and agents are strictly forbidden from unilaterally adding third-party dependencies to `uxmSkyblock`. The following formal protocol must be followed:
1. **Report `UXM-LIB CAPABILITY GAP`:** File an architectural notice detailing the missing capability.
2. **State Required Capability & Target Module:** Identify what functional abstraction is required (e.g. `uxmlib-metrics`).
3. **Justify Insufficiency:** Explicitly demonstrate why existing modules in `uxm-lib` cannot fulfill the requirement.
4. **Suggest Extension Point:** Propose a clean interface or module addition to `uxm-lib`.
5. **Await Architecture / Product Owner Approval:** No direct third-party dependency may be introduced without formal PO/Architecture sign-off.
