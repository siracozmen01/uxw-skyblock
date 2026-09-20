# Extreme Scale Architecture: Engineering for 100,000 Players

To support mega-networks, 1,000,000+ registered player profiles, and 250,000+ persisted islands without memory bloat, lag spikes, or race conditions, the Skyblock architecture enforces five core performance and distributed correctness invariants.

---

## 1. "Unload on Idle" Memory Management

Holding hundreds of thousands of islands in JVM heap memory will trigger Out-Of-Memory (OOM) errors.

* **Lazy Active Island Lifecycle:**
  - An island is loaded into JVM memory **only** when an island member or authorized visitor is online and present within its boundary.
  - When the last player departs or disconnects, an idle countdown (default 10 minutes) initiates.
  - Upon timer expiration, all island metadata, cached block counts, and bank balances are flushed to SQL with optimistic CAS and authority epoch validation, and the island is evicted from the active Caffeine/FastUtil L1 cache.
* **Chunk Unloading:** Corresponding world chunks are marked for unloading via Folia's `RegionScheduler`, freeing Folia tick loops and entity trackers.

---

## 2. Distributed Authority Fencing & Intra-JVM Lock Striping

* **The Problem:** Global locking across servers creates catastrophic latency bottlenecks. Bare Redis locks fail under GC pauses (stale-owner split-brain). Conversely, absence of local synchronization causes multi-threaded race conditions within Folia regions.
* **The Solution — Dual-Tier Concurrency Architecture:**
   1. **Cluster-Wide Single-Writer Authority & Commit-Time Row Serialization (`island_authorities.authority_epoch`):**
      - In a distributed cluster, each active island is leased to exactly one authoritative node.
      - The canonical authority lease and monotonic `authority_epoch BIGINT` reside in SQL (`island_authorities`).
      - Authority ownership transfers or fresh acquisitions increment the epoch; routine heartbeat renewals by the active owner never advance the epoch.
      - **Commit-Time Serialization via Canonical Row Locking (`FOR UPDATE`):**
        - Rather than relying on statement-time existence checks or filtered predicates that vary by node, authoritative mutations lock the canonical row by `island_id`:
        ```sql
        -- Step 1: Serialize on canonical authority row (holds exclusive row lock until COMMIT/ROLLBACK)
        SELECT authoritative_node, authority_epoch, lease_expires_at
        FROM island_authorities
        WHERE island_id = :id
        FOR UPDATE;

        -- Step 2: Critical business mutation guarded by OCC version check
        UPDATE island_banks
        SET primary_balance = primary_balance + :delta,
            version = version + 1
        WHERE island_id = :id
          AND version = :expected_version;
        ```
      - *Invariant 1 (Authority Transaction Validity Semantics):* **Lease expiration prevents NEW authoritative transactions from acquiring or initiating mutations. It does NOT revoke an already-authorized transaction that was valid when the canonical authority row was locked and still exclusively holds that authority row lock within its operational duration budget.**
      - *Invariant 2 (Monotonic Takeover Serialization):* Any concurrent takeover query (`UPDATE island_authorities SET authoritative_node = :newNode, authority_epoch = authority_epoch + 1 ...`) targets the exact same canonical row and blocks on the row lock until this mutation transaction completes.
      - *Invariant 3 (Multi-Island Deadlock Ordering):* Operations locking multiple islands MUST sort `IslandId`s in **UUID Unsigned 128-Bit Binary Order**, acquiring `island_authorities` row locks in ascending order before locking business aggregate rows. This hierarchy is *specifically designed to eliminate lock inversion cycles among UXM transactions*. On unexpected deadlock, the transaction rolls back and retries with exponential backoff and jitter.
      - *Invariant 4 (Orphaned Lock Retention & Fail-Closed Waiters):* `lock_timeout` applies strictly to waiting queries. If a lock-holding node crashes, the DB retains the lock until severed via OS/TCP keepalives, driver socket timeouts, or idle transaction timeouts. Waiters waiting on the lock fail closed after `lock_timeout = 1000ms` and back off, strictly preventing split-brain or premature takeovers.
      - If mutation fails (`affectedRows != 1` or authority check fails), a deterministic rejection is persisted in `processed_operations` (`status = 'REJECTED'`) within the exact same transaction, committing without audit or outbox entries.
   2. **Intra-JVM Keyed Lock Striping (`Striped<Lock>`):**
     - Within a single JVM node, `Striped<Lock>` serializes concurrent tasks executing across local Folia region contexts per `IslandId`:
       ```java
       public final class IslandLockRegistry {
           private final Striped<Lock> locks = Striped.lazyWeakLock(4096);

           public <T> T computeWithLock(IslandId id, Supplier<T> action) {
               Lock lock = locks.get(id);
               lock.lock();
               try {
                   return action.get();
               } finally {
                   lock.unlock();
               }
           }
       }
       ```
     - **Scope Constraint:** `Striped<Lock>` is strictly an intra-JVM thread contention optimization. It does NOT protect across multiple cluster nodes.

---

## 3. Transactional Durability via `TxSql` & Outbox Pipeline

* Financial operations (island bank deposits, withdrawals, upgrade purchases) are executed inside atomic SQL transactions (`TxSql`).
* **Strict Transaction Boundary:** Idempotency reservation (`processed_operations`), CAS state mutation, audit logging (`bank_transactions`), and event streaming (`outbox_events`) execute within the EXACT same SQL transaction.
* **ACID Transaction Boundaries:** If a server node crashes mid-transaction or a query fails, the database rolls back atomically. Critical mutations enforce an affected-row count of exactly 1 before audit or outbox records can be committed.

---

## 4. Computational Complexity: Real-Time Leveling & Worth Engine

* **Antipattern Rejected:** Synchronous full-world block scanning during `/is level` which freezes server TPS.
* **Rigorous Computational Complexity Model:**
  1. **Material Count Mutation (`BlockPlaceEvent` / `BlockBreakEvent`):** **Amortized $O(1)$**. Real-time event listeners update the primitive in-memory histogram (`IslandMaterialIndex`) and adjust cached score accumulators.
  2. **Single Material Price Update Event:** **Amortized $O(1)$**. When dynamic economy shops update the unit price of a single material, `cachedEconomicWorth` adjusts via delta calculation:
     $$\Delta = \text{count}_m \times (\text{newPrice}_m - \text{oldPrice}_m)$$
  3. **Full Economic Worth Recomputation:** **$O(M)$**, where $M$ is the number of distinct tracked material types currently indexed. Executed strictly during cold cache initialization or manual `/is recalc`.
  4. **Authoritative Background World Scan:** **$O(C \times B)$**, where $C$ is the number of chunks and $B$ is the non-air block count per chunk section. Runs off-peak via Folia `RegionScheduler` semaphores with a 10ms inter-chunk delay to eliminate tick latency.

---

## 5. Storage Hierarchy: SQL Canonical Source of Truth & Ephemeral Redis L2

* **SQL Database (Sole Canonical Source of Truth):**
  - All identities, memberships, permissions, balances, and vault pages are canonically persisted in SQL (MySQL 8+, MariaDB 10+, PostgreSQL 15+, or SQLite).
* **L1 Cache (In-Memory JVM Heap - Caffeine & FastUtil):**
  - Microsecond read queries (permissions, flags, levels, basic metadata).
  - Bounded size limits with W-TinyLFU eviction policy.
* **Redis Tier (Ephemeral L2 Cache, Routing & Streaming Bus):**
  - Caches cluster routing leases (`uxmskyblock:node:island:<id>`).
  - Distributes ephemeral chat and online presence via Redis Pub/Sub.
  - Streams durable domain events via Redis Streams (`XADD`, consumer groups, and `consumer_inbox` deduplication).
  - **Constraint:** Redis is NEVER the authoritative permanent store of truth.

---

## 6. Player Session Authority & Single-Writer Invariant

* **Cluster-Wide Single-Writer Guarantee:** A player's active profile and inventory must have exactly one authoritative server node at any given point in time. The single source of truth is the SQL `player_sessions` table (`player_uuid PK, active_profile_id, authoritative_node, session_epoch, state, handoff_id, handoff_target_node, handoff_expires_at, last_durable_inventory_version, lease_expires_at, updated_at`).
* **Commit-Time Authority Row Lock Serialization (`SELECT ... FOR UPDATE`):**
  - Rather than relying on statement-time existence checks that fail to hold locks until commit, every authoritative player-state mutation transaction (quit flushes, profile switches, vault flushes, trade commits, cross-server handoff flushes, and ambient checkpoints under Hybrid durability) MUST lock the canonical `player_sessions` row exclusively:
    ```sql
    SELECT authoritative_node, session_epoch, state, lease_expires_at
    FROM player_sessions WHERE player_uuid = :playerUuid FOR UPDATE;
    ```
  - Application verifies `authoritative_node == currentNode`, `session_epoch == expectedEpoch`, and `lease_expires_at >= DB_CURRENT_TIME` within the exact same transaction before executing mutations on `profile_inventories`.
  - **Invariant:** *Every authoritative player-state mutation and every player-session ACQUIRE/TAKEOVER transition MUST serialize on the same canonical `player_sessions` row.*
  - **Lease Expiry Semantics:** Lease expiration rejects new authoritative transactions; it does not bypass an already-authorized bounded transaction holding the canonical session row lock.
  - **Player-State Lock Hierarchy:** To eliminate lock inversion cycles, locks are acquired in strict hierarchical order: (1) `player_sessions`; (2) `player_accounts`/`player_profiles`; (3) `profile_inventories`; (4) child/journal records. Multi-player operations sort `Player UUID`s in UUID unsigned 128-bit binary order.
* **Cross-Server Handoff Protocol (`ACTIVE` -> `DRAINING` -> `HANDOFF_READY` -> `ACTIVE`):**
  1. Proxy initiates server transfer for player.
  2. Source node transitions state to `DRAINING`, freezing interactive mutations.
  3. Source node captures final immutable player inventory snapshot, writes to journal, bumps `last_durable_inventory_version`, and transitions session to `HANDOFF_READY` bound to destination node and expiry window.
  4. Destination node verifies `HANDOFF_READY` (with matching target and unexpired window) or claims expired lease via takeover, increments `session_epoch = session_epoch + 1`, sets `authoritative_node = destinationNode`, loads latest inventory version, and transitions to `ACTIVE`.
* **Split-Brain & Stale Write Fencing:** Any delayed or asynchronous flush from a stale source node will block behind the active row lock or fail unconditionally upon acquire due to `session_epoch` and lease checks.


---

## 7. Production Database Durability Profiles (`DURABLE_PRODUCTION`)

* **Durability Guarantee Standard:**
  > A successful database commit is considered durable only when the selected persistence adapter is running under its validated `DURABLE_PRODUCTION` profile and within the durability guarantees documented by that database/storage stack.
  > *Boundary Notice:* This software durability guarantee relies on operating system, storage controller, RAID, and underlying physical/cloud storage write-barrier adherence and does not exceed the hardware failure semantics documented by the database engine vendor.
* **Dialect Invariants:**
  - **PostgreSQL:** `synchronous_commit = on` (or `remote_apply` for synchronous replicas), `fsync = on`.
  - **MySQL / MariaDB:** `innodb_flush_log_at_trx_commit = 1`, `sync_binlog = 1`.
  - **SQLite:** `PRAGMA synchronous = FULL;`, `PRAGMA journal_mode = WAL;`. (`synchronous = NORMAL;` is strictly **REJECTED** under `DURABLE_PRODUCTION` due to power-loss/crash rollback risk).
* **Fail-Fast Boot Validation:** During plugin bootstrap, UXPLIMA inspects database engine variables. If durability flags indicate relaxed or asynchronous flushing (e.g., `innodb_flush_log_at_trx_commit != 1`, `synchronous_commit = off`, or SQLite `synchronous != FULL`), **`uxmSkyblock` plugin enable FAILS** with a `FatalDurabilityConfigurationException` to prevent data corruption. (Minecraft server process supervision remains with the host watchdog).

---

## 8. Snapshot Restore & `ECONOMIC_ITEM_STATE` Boundary

* **Strict Accounting Boundary:** Multi-region asynchronous world snapshots are NOT point-in-time consistent with transactional SQL databases.
* **Non-Rollback of Economic Ledgers & `ECONOMIC_ITEM_STATE`:**
  - Restoring an island snapshot to a previous point in time restores strictly physical block geometry, biomes, and explicitly non-economic world contents.
  - It **NEVER** rolls back bank balances (`island_banks`), player inventories (`profile_inventories`), vault pages (`island_vault_pages`), transaction ledgers, or outbox records.
  - It **NEVER** restores historical `ECONOMIC_ITEM_STATE` (container blocks, shulkers, dropped items, item frames, mob equipment) without ledger-aware reconciliation.
* **Duplication Prevention:** Decoupling physical world geometry from financial aggregates and suppressing historical item containers prevents item and currency duplication under the documented recovery model.
