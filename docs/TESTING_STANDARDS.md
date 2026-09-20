# Testing Standards & Verification Infrastructure

Following the proven engineering practices of `uxm-essentials`, this project adopts a multi-layered, automated testing harness that prevents regressions, architectural drift, and platform deadlocks before code ever reaches a server.

---

## 1. Test Tooling Arsenal

| Tool | Version | Purpose & Value |
| :--- | :--- | :--- |
| **JUnit 5 (Jupiter)** | `5.12.1` | Modern test runner, parallel execution explicitly enabled via `junit.jupiter.execution.parallel.enabled = true`, isolated mock suites, parameterized tests. |
| **AssertJ** | `3.27.3` | Highly expressive, fluent assertion library (`assertThat(result).isInstanceOf(...)`). |
| **Mockito** | `5.23.0` | Clean mock generation for outbound ports and service boundaries. |
| **MockBukkit v26.2** | `4.116.1` | High-fidelity in-memory Mock Paper server. Runs listeners, commands, inventories, and player lifecycles in unit tests without launching a Minecraft server. Marked `@Isolated` to prevent static state leakage. |
| **ArchUnit** | `1.4.2` | Static bytecode architecture tests. Enforces architectural fences and detects illegal imports/concurrency APIs automatically. |
| **jqwik** | `1.9.3` | Property-based testing. Generates hundreds of randomized edge-case inputs for coordinate mathematics, boundary checks, and economy math. |
| **Testcontainers** | `1.20.4` | Containerized disposable database instances (PostgreSQL, MariaDB) for live SQL migration, CAS contention tests, and repository verification. |

---

## 2. Automated Architecture & Drift Guard Tests (ArchUnit)

ArchUnit tests run on every build to prevent human or AI coding mistakes:

1. **Layer Boundary Enforcement:**
   - The Core Domain (`domain.*`) must never import Bukkit (`org.bukkit.*`), Paper (`io.papermc.*`), or persistence adapters (`java.sql.*`).
   - Adapters may depend on Ports, but Domain and Ports must never import Adapters.
2. **Folia Threading Drift Guard:**
   - Scans bytecode to ensure no calls to `BukkitScheduler`, `Bukkit.getScheduler()`, `BukkitRunnable`, `runTask*`, or raw `synchronized (player)`.
3. **Legacy Chat & Formatting Drift Guard:**
   - Enforces that no legacy `ChatColor`, `§`, or `&` color code parsing exists in production classes. All text must use Adventure MiniMessage.
4. **PrintStackTrace Drift Guard:**
   - Ensures `e.printStackTrace()` is never called in production code. All exceptions must be routed through structured logging or typed results.
5. **SQL Concatenation Drift Guard:**
   - Checks that SQL queries are never constructed using string concatenation (`+`), forcing parameterized queries (`Sql.of("...", params)`).
6. **Suppressed Warning Integrity:**
   - Enforces that any `@SuppressWarnings` in the codebase is accompanied by a documented justification comment.

---

## 3. Property-Based Testing (jqwik)

For domain logic that relies on numerical precision, coordinates, or financial transactions, property-based tests must be written:
* **Island Grid Allocation:** Verify that no two assigned islands ever overlap or violate minimum buffer spacing across millions of pseudo-random allocations.
* **Coordinate Conversion:** Verify that chunk-to-island and location-to-region conversions are completely symmetric and reversible.
* **Level Calculations:** Verify that block counts and score weights never produce negative levels, NaN, or arithmetic overflow.
* **Transaction Math:** Verify that deposits, withdrawals, and bank operations maintain strict invariant preservation.

---

## 4. In-Memory MockBukkit Testing

Use MockBukkit to test inbound adapters (Commands & Event Listeners) without running a full server:
* Verify that `/island create` properly triggers island instantiation, sets player spawn, and closes or transitions menus.
* Verify that non-members cannot break blocks or open chests inside another player's island boundary.
* Verify that island settings toggles (e.g. PvP disabled) correctly cancel damage events between teammates or visitors.
* MockBukkit test classes must be annotated with `@Execution(ExecutionMode.SAME_THREAD)` or `@Isolated` to guarantee that MockBukkit's singleton registry is never corrupted by parallel test threads.

---

---

---

## 5. Multi-Tier Verification Harness & Distributed Correctness Suites

To verify distributed correctness, crash consistency, and data durability under the documented failure model, the test infrastructure is organized into seven progressive verification tiers:

### 5.1 Verification Tiers
* **Tier 1: Pure Unit & Property-Based Tests (`jqwik`, `JUnit 5`):**
  - Run without external servers or databases in sub-millisecond cycles.
  - Cover coordinate conversions, spiral grid recycling, OCC version math, and financial rounding precision.
* **Tier 2: Component MockBukkit Tests (`MockBukkit v26.2`):**
  - Test inbound event listeners, command trees, inventory click guards, and permission evaluations in an isolated Mock Paper server.
* **Tier 3: Relational Persistence Integration Tests (`Testcontainers`):**
  - Execute against real, containerized database engines: PostgreSQL 15+, MySQL 8.0+, MariaDB 10.6+, and SQLite.
  - Verify migration idempotency, canonical `FOR UPDATE` row locks, `SKIP LOCKED` outbox claims, single-transaction atomic rejections, and dialect-safe conditional inbox insertions.
* **Tier 4: Server Integration Tests (Real Paper Environment):**
  - Verify complete plugin boot, config validation, and plugin messaging adapters in a live Paper test harness.
* **Tier 5: Folia Thread Isolation & Multi-Region Tests (Real Folia Process):**
  - Verify region-boundary traversals, owning `RegionScheduler` execution contexts, and ensure live Bukkit references never escape to asynchronous worker threads.
* **Tier 6: Distributed Cluster Concurrency Harness:**
  - **Tier 6A (In-Process Multi-Node Simulation):** Simulated nodes connected to shared SQL and Redis instances for rapid lifecycle and heartbeat tests.
  - **Tier 6B (Real Multi-Process Multi-JVM Harness):** Spawns distinct, separate JVM processes running headless Paper/Folia instances connected to containerized PostgreSQL/MySQL and Redis instances. Validates real operating system socket latency, JVM GC pauses, and inter-process lock contention.
* **Tier 7: Chaos Engineering & Fault-Injection Tests:**
  - Inject unexpected process termination (`SIGKILL`), network partitions, simulated database latency spikes, and out-of-order Redis deliveries.

---

### 5.2 Mandatory Distributed Correctness & Crash-Consistency Test Suites

1. **`AuthorityTakeoverAfterBlockedExpiredTxTest` (Scenario A — Takeover Succeeds After Expired In-Flight Commit):**
   - **Setup:** Node A holds active authority lease on Island 1.
   - **Execution:** Node A initiates an authoritative bank mutation. Inside the transaction, it locks the canonical authority row (`SELECT authoritative_node, authority_epoch, lease_expires_at FROM island_authorities WHERE island_id = :id FOR UPDATE`).
   - Lease nominally expires while Node A's transaction is in-flight.
   - Concurrently, Node B detects lease expiration and executes takeover (`UPDATE island_authorities SET authoritative_node = 'node-b', authority_epoch = authority_epoch + 1 ...`). Node B's query blocks on the canonical row lock.
   - Node A commits its mutation (valid because authority was valid at lock time and Node A exclusively held the canonical row lock within the operational duration budget).
   - Node A commits; row lock releases.
   - Node B unblocks and executes its takeover query.
   - **Assertions:**
     - Node A's mutation committed successfully under epoch $E$.
     - Because Node A did not renew the lease and the lease is expired, Node B's takeover query **SUCCEEDS**:
       `affectedRows == 1`, `authoritative_node = 'node-b'`, `authority_epoch = E + 1`.

2. **`AuthorityRenewVsTakeoverRaceTest` (Scenario B — Heartbeat Renewal Defeats Stale Takeover):**
   - **Setup:** Node A holds active lease on Island 1.
   - **Execution:** Node A executes periodic heartbeat renewal (`UPDATE island_authorities SET lease_expires_at = NOW() + 15s WHERE island_id = :id AND authoritative_node = 'node-a' AND authority_epoch = :epoch AND lease_expires_at >= NOW()`).
   - Concurrently, Node B attempts a takeover with the prior expected epoch or while the lease is still valid.
   - **Assertions:**
     - Node A's renewal succeeds (`affectedRows == 1`, epoch unchanged).
     - Node B's takeover evaluates to `affectedRows == 0` (rejected safely without advancing epoch).

3. **`MultiIslandDeadlockOrderingRetryTest` (Canonical Lock Ordering & Jittered Retry):**
   - Simulates two concurrent cross-island transfers between Island 10 and Island 20.
   - Thread 1 transfers $10 \to 20$; Thread 2 transfers $20 \to 10$.
   - **Assertions:**
     - Both threads enforce canonical ID sorting, acquiring row locks in ascending order ($10 \to 20$), eliminating deadlocks.
     - Under forced random lock order injection, induced SQL deadlocks trigger immediate transaction rollback and succeed on exponential backoff retry.

4. **`IdempotencyUniqueIndexContentionTest` (Model A Concurrency Semantics):**
   - 10 concurrent threads simultaneously submit identical scoped requests `(operation_scope, actor_id, idempotency_key)`.
   - The winning thread inserts `status = 'PENDING'` and proceeds with business mutation.
   - The 9 losing threads block on the unique index at the database engine level until the winner commits.
   - Upon winner commit, losing threads catch `DuplicateKeyException`, roll back local drafts, query `processed_operations`, and receive the cached deterministic result without executing duplicate mutations.
   - Injects forced delay exceeding `lock_timeout = 1000ms` on winner: asserts loser unblocks with `IDEMPOTENCY_OPERATION_IN_PROGRESS`.

5. **`ProfileSwitchCrashAtTargetApplyIntentTest` (Write-Ahead Crash Recovery):**
   - Executes a profile switch to state `TARGET_APPLY_INTENT` (intent committed to SQL, but player in-game entity has not yet received target items).
   - Injects immediate JVM `SIGKILL`.
   - Upon recovery boot / player reconnect:
     - The recovery worker detects `state = 'TARGET_APPLY_INTENT'`.
     - Executes deterministic **Roll-forward**: applies target profile snapshot to player entity on the Folia `EntityScheduler`, updates `active_profile_id = targetProfileId`, and transitions state to `COMMITTED`.
     - Asserts player retains target profile items without item duplication or progress loss.
     - Confirms vanilla disk `playerdata/*.dat` cannot override SQL profile state.

6. **`DurableVaultEscrowSessionTest` (Slot Decoupling & Crash Reconciliation):**
   - Player opens island vault Page 1, staging transfer intents in `vault_edit_sessions.escrow_journal`.
   - Player moves 5 diamond blocks into the vault. Active session declares ownership over transferred slots.
   - An external game event concurrently rewards the player with an emerald in an unrelated inventory slot (Slot 12).
   - Simulates mid-session server crash.
   - Upon reboot/reconnect:
     - `vault_edit_sessions` entry is expired (`expires_at < NOW()`).
     - Reconciliation worker reads `escrow_journal` and restores the 5 diamonds to the player's inventory.
     - The unrelated emerald in Slot 12 is completely untouched and preserved.
     - Vault SQL state remains at original version without data corruption.

7. **`OutboxDispatcherCrashBetweenXaddAndProcessedMarkTest` (At-Least-Once Re-Delivery):**
   - Outbox worker executes TX1 (`SELECT ... FOR UPDATE SKIP LOCKED`, sets `status = 'CLAIMED'`).
   - Worker successfully publishes `XADD` to Redis Streams, but crashes before TX2 (`UPDATE ... SET status = 'PROCESSED'`).
   - Claim lease expires (30s). Worker 2 claims the row and re-issues `XADD`.
   - Consuming remote node receives duplicate stream delivery:
     - Delivery 1: executes `INSERT INTO consumer_inbox ... ON CONFLICT DO NOTHING`, affected rows == 1. Applies local state projection and commits.
     - Delivery 2: executes `INSERT INTO consumer_inbox ... ON CONFLICT DO NOTHING`, affected rows == 0. Skips local state projection, commits immediately, and sends `XACK`.
     - Asserts zero double-processing occurs downstream.

8. **`SnapshotRegionThreadIsolationTest` (Correctness Test):**
   - Triggers full island snapshot on a multi-chunk island spanning multiple dynamically split Folia owning region contexts.
   - Asserts that chunk extraction tasks execute strictly within their owning Folia region context.
   - Asserts that zero live Bukkit/NMS references (`Block`, `Chunk`, `TileState`, `World`) escape to asynchronous worker threads.
   - Injects coordinator timeout (500ms safety limit): verifies `snapshot_generation_token` is invalidated, late chunk results are discarded, and partial archive is purged.

9. **`SnapshotCaptureLatencyBenchmark` (Performance & SLO Benchmark):**
   - Executed strictly in isolated, reproducible benchmarking environments (separate from functional unit/integration suites).
   - Measures and profiles Phase 1 player modification quiesce duration under synthetic block-breaking load.
   - Evaluates operational pause characteristics without asserting unapproved numerical architecture constants (latency numbers remain operational benchmark measurements, not release-blocking architecture invariants).
   - *Note:* Benchmark degradation indicates performance tuning requirements and is reported separately from functional test failures.

---

### 5.3 Gate 5 Mandatory Distributed Correctness & Crash-Consistency Test Suites

To verify the distributed edge-case invariants established in Gate 5, the following nine tests are mandatory implementations in the test harness:

1. **`AuthorityOwnerCrashLockRetentionTest` (Lock Retention on Node Crash & Fail-Closed Waiters):**
   - **Target Invariant:** `lock_timeout` applies strictly to waiting queries. Orphaned locks are severed via DB TCP keepalive / idle session timeouts; waiters fail closed during the orphaned window.
   - **Scenario:** Node A begins an authoritative bank transaction and acquires the exclusive row lock on `island_authorities` (`SELECT ... FOR UPDATE`).
   - Node A crashes abruptly (`SIGKILL` injected without closing JDBC connection).
   - Node B immediately attempts an authority takeover on the same island.
   - **Assertions:**
     - Node B's takeover query blocks on the retained row lock and aborts after `lock_timeout = 1000ms` with `LockTimeoutException`.
     - Node B fails closed; it does **NOT** forcibly bypass the lock or advance the epoch.
     - Upon dialect engine severance (simulated `idle_in_transaction_session_timeout = 5000ms`), Node A's abandoned transaction rolls back automatically.
     - Node B's subsequent retry successfully locks the row and advances epoch to $E + 1$.

2. **`IdempotencyWinnerRollbackRaceTest` (Concurrent Loser Handling of Rolled-Back Winner):**
   - **Target Invariant:** In Model A scoped idempotency, if the winning transaction aborts/rolls back, waiting duplicate callers must cleanly recover without being stranded.
   - **Scenario:** Thread A begins transaction and inserts `(scope, actor, key)` with `status = 'PENDING'`. Thread B attempts the same insert and blocks on the unique index.
   - Thread A encounters an unexpected exception or DB crash and executes `ROLLBACK`.
   - **Assertions:**
     - Upon Thread A's rollback, Thread B unblocks.
     - Thread B queries `processed_operations`, finds no committed record, successfully inserts its own reservation, and completes the business mutation.
     - The business mutation executes exactly once.

3. **`VaultCrashBeforeSlotMutationTest` (Crash in INTENT Pre-Mutation — Zero Blind Refund):**
   - **Target Invariant:** Prohibition of blind refunds. If crash occurs during `INTENT` before in-memory slot modification, zero items may be refunded.
   - **Scenario:** Player opens island vault Page 1. An intent record is inserted into `vault_edit_sessions.escrow_journal` (`state = 'INTENT'`), recording `before_fingerprint` (source slot contains 64 Diamonds, destination is EMPTY).
   - Host process is killed immediately BEFORE the Folia entity thread mutates inventory slots.
   - Server reboots; recovery worker reconciles the expired session.
   - **Assertions:**
     - Recovery worker inspects player inventory and discovers the 64 Diamonds still in the source slot (matches `before_fingerprint`).
     - Recovery worker marks journal entry `ABORTED`.
     - Zero items are refunded or moved to the player's inventory, strictly preventing diamond duplication.

4. **`VaultCrashAfterSlotMutationBeforeAppliedMarkTest` (Crash Post-Mutation Before Applied Mark):**
   - **Target Invariant:** If in-memory slot mutation succeeded but crash occurred before journal state updated to `APPLIED`, conditional repair reconciles based on actual slot fingerprint.
   - **Scenario:** Intent is persisted (`INTENT`). Folia entity thread mutates player and vault slots (source cleared, diamonds moved to cursor/vault). Slot now matches `after_fingerprint`. Crash is injected before SQL journal can be marked `APPLIED`.
   - Server reboots; recovery worker runs.
   - **Assertions:**
     - Recovery worker compares slot content against `after_fingerprint`, detecting that the physical mutation occurred.
     - Because session never committed, recovery safely restores the diamonds to the original source slot, increments slot versions, and transitions journal entry to `ABORTED`.
     - Neither item duplication nor item deletion occurs.

5. **`VaultConflictingExternalSlotMutationTest` (Conflicting External Mutation Detection):**
   - **Target Invariant:** Third-party direct Bukkit API mutations (`player.getInventory().setItem(...)`) cannot be synchronously prevented; conditional repair must detect fingerprint drift and flag `RECOVERY_REQUIRED`.
   - **Scenario:** Active vault edit session holds ownership over Slot 2. An external rogue plugin calls direct Bukkit API `player.getInventory().setItem(2, dirtBlock)`. Session expires or crashes.
   - Recovery worker runs.
   - **Assertions:**
     - Recovery worker inspects Slot 2. The dirt block matches neither `before_fingerprint` nor `after_fingerprint`.
     - Recovery refuses blind repair, transitions session to `RECOVERY_REQUIRED`, quarantines the slot and vault page, and emits a critical staff alert.

6. **`OutboxExpiredClaimRecoveryTest` (Expired Outbox Claim Recovery):**
   - **Target Invariant:** Outbox worker claims must be reclaimed if the claiming worker crashes or hangs past `claim_expires_at`.
   - **Scenario:** Worker 1 claims pending outbox events in TX1, setting `claim_owner = 'worker-1'`, `claim_expires_at = NOW() + 30s`. Worker 1 crashes before issuing `XADD`.
   - Simulated clock advances 31 seconds. Worker 2 polls the outbox.
   - **Assertions:**
     - Worker 2's query (`WHERE status = 'PENDING' OR (status = 'CLAIMED' AND claim_expires_at < NOW())`) successfully claims Worker 1's expired events via `SKIP LOCKED`.
     - Worker 2 updates `claim_owner = 'worker-2'`, increments `retry_count`, and publishes the event to Redis Streams.

7. **`OutboxStaleWorkerCompletionFenceTest` (Claim Token Fencing Stale Outbox Worker):**
   - **Target Invariant:** A revived stale outbox worker must be fenced out from completing TX2 if its claim was reclaimed by another worker.
   - **Scenario:** Worker 1 claims Event E with `claim_token = 'token-1'`, then suffers a 40-second JVM GC pause.
   - Claim expires. Worker 2 claims Event E with `claim_token = 'token-2'`, publishes `XADD`, and commits TX2 (`WHERE event_id = :id AND claim_token = 'token-2'`).
   - Worker 1 resumes and executes TX2 (`WHERE event_id = :id AND claim_token = 'token-1'`).
   - **Assertions:**
     - Worker 1's query updates 0 rows (`affectedRows == 0`).
     - Worker 1 logs `STALE_OUTBOX_WORKER_FENCED` and safely discards completion without overwriting Event E's processed status or resetting timestamps.

8. **`ObjectStoreSnapshotPublishCrashTest` (S3/R2 Manifest Publication Crash Consistency):**
   - **Target Invariant:** S3 object store snapshots become visible and valid strictly upon atomic upload of `manifest.json`. Partial uploads without a manifest are treated as non-existent.
   - **Scenario:** Backup engine initiates snapshot on S3 provider. Chunks and envelope parts are uploaded to `islands/<id>/<snapshot_id>/chunks/...`. Crash is injected immediately BEFORE `manifest.json` is uploaded.
   - Recovery / administrative rollback CLI inspects the S3 bucket.
   - **Assertions:**
     - The snapshot is omitted from available backups because `manifest.json` is absent.
     - Administrative rollback on that snapshot ID is rejected with `SNAPSHOT_NOT_FOUND_OR_INCOMPLETE`.
     - Orphaned chunk objects are safely cleaned by bucket lifecycle expiration rules.

9. **`FullRestoreConsistencyPolicyTest` (Multi-Region Quarantine & Restore Mode Contract):**
   - **Target Invariant:** Full island rollback across mixed DB and world state requires maintenance quarantine; atomic single-instant rollback is unsupported across multi-region boundaries.
   - **Scenario:** Admin executes `/is admin rollback <island_id> latest --mode=FULL_ISLAND`.
   - **Assertions:**
     - Target island transitions to `quarantine_state = 'RESTORING'`.
     - Online visitors are safely teleported to spawn; incoming player commands and block edits are blocked.
     - World chunks are restored across Folia `RegionScheduler` threads.
     - SQL database configuration/upgrades/members restore atomically only after all chunk regions verify SHA-256 hashes (economic bank/vault balances are preserved untouched).
     - Database version increments to `version = max(live, snapshot) + 100`, invalidating stale caches.
     - Island quarantine is cleared upon final commit.

---

### 5.4 Final Blocking Architecture Correction Mandatory Test Suites

To verify the distributed edge-case invariants, session authority handoffs, durability contracts, and recovery state machines established in the final architecture pass, the following categorized test suites are mandatory implementations in the test harness:

#### 5.4.1 Player Session Authority & Fencing Tests
1. **`PlannedPlayerHandoffFencingTest` (Planned Handoff Exact Destination & Expiry Fencing):**
   - **Target Invariant:** Node B can only acquire session authority if `state = 'HANDOFF_READY'`, `handoff_id = expectedHandoffId`, `handoff_target_node = 'node-b'`, `authoritative_node = expectedSourceNode`, `session_epoch = expectedSourceEpoch`, and `handoff_expires_at >= CURRENT_TIMESTAMP`.
   - **Scenario:** Node A transitions player to `DRAINING` $\to$ flushes snapshot/journal $\to$ transitions to `HANDOFF_READY` with target Node B and 30s expiry.
   - **Assertions:**
     - Node B's acquire query updates exactly 1 row (`affectedRows == 1`), sets `state = 'ACTIVE'`, `authoritative_node = 'node-b'`, increments `session_epoch = E + 1`, and clears handoff fields.
     - An unauthorized Node C attempting to acquire updates 0 rows (`handoff_target_node` mismatch).
     - If `handoff_expires_at` has passed, Node B's planned acquire updates 0 rows and falls back to failure takeover.
2. **`PlayerAuthorityLocalSelfFenceTest` (Conservative Monotonic Self-Fencing on DB Loss):**
   - **Target Invariant:** Node A records `renewAttemptStartedNanos = System.nanoTime()` before dispatching RENEW. Local deadline is conservatively derived from `renewAttemptStartedNanos`. If renewal response arrives after local deadline or renewal fails, Node A halts player mutations before its DB lease can expire.
   - **Scenario:** Sever DB connection to Node A. Advance simulated monotonic clock past `renewAttemptStartedNanos + (leaseDuration - safetyMargin)`.
   - **Assertions:**
     - Node A locally transitions player session to `LOCAL_FENCED`.
     - Subsequent player inventory actions, item drops, trades, and vault interactions are immediately blocked.
     - Node A disconnects player or initiates transfer before the remote database lease expires.
3. **`StalePlayerAsyncFlushRejectedTest` (Stale Source Flush Rejection by Epoch and Lease Validity):**
   - **Target Invariant:** Asynchronous delayed inventory flush from an old source node cannot overwrite a new authoritative node's state, and must validate `lease_expires_at >= CURRENT_TIMESTAMP`.
   - **Scenario:** Node A captures snapshot at epoch $E$, then suffers long GC pause while its lease expires and Node B acquires at $E + 1$. Node A wakes up and attempts async SQL flush.
   - **Assertions:**
     - Node A's SQL update predicate (`WHERE session_epoch = :expectedEpoch AND authoritative_node = :nodeA AND lease_expires_at >= CURRENT_TIMESTAMP`) matches 0 rows (`affectedRows == 0`).
     - Node A's write is rejected (`STALE_SESSION_EPOCH_OR_EXPIRED_LEASE`); newer inventory version committed by Node B is preserved untouched.
4. **`PlayerStateCommitVsTakeoverSerializationTest` (Commit-Time Row Lock Serialization vs Concurrent Takeover):**
   - **Target Invariant:** Authoritative player-state mutations and failure takeovers serialize on the canonical `player_sessions` row lock (`FOR UPDATE`). Nominal lease expiration during statement execution does not revoke an already-authorized transaction, and concurrent takeover must wait for commit/rollback.
   - **Scenario:**
     - Node A begins transaction, locks `player_sessions` (`WHERE player_uuid = :uuid FOR UPDATE`) under epoch $E=10$, and updates `profile_inventories`.
     - Lease nominally expires while Node A's transaction remains within its operational duration budget.
     - Node B attempts failure takeover (`UPDATE player_sessions ... WHERE lease_expires_at < NOW()`).
   - **Assertions:**
     - Node B's takeover query blocks on the canonical row lock held by Node A.
     - Node A commits its transaction.
     - Node B unblocks, successfully advances epoch to $E=11$, and commits takeover.
     - Any subsequent delayed write from Node A under old epoch $E=10$ is rejected (`affectedRows == 0`).
5. **`PlannedAcquireVsFinalSourceFlushSerializationTest` (Planned Acquire Serialized After Final Source Flush):**
   - **Target Invariant:** Destination planned acquire cannot acquire session or advance epoch while source node's final handoff flush holds the canonical row lock.
   - **Scenario:** Node A sets state to `DRAINING`, locks `player_sessions FOR UPDATE`, and flushes final inventory and journal records. Node B attempts planned acquire concurrently.
   - **Assertions:**
     - Node B's planned acquire query blocks on the row lock until Node A commits `HANDOFF_READY`.
     - Once Node A commits, Node B acquires the lock, verifies `HANDOFF_READY`, advances epoch to $E+1$, and transitions to `ACTIVE`.
6. **`PlayerSourceCrashDuringHandoffTest` (Source Node Crash During Handoff):**
   - **Target Invariant:** If source node crashes before completing handoff, destination node safely waits for lease expiration before takeover.
   - **Scenario:** Server A crashes while session is in `DRAINING`. Player connects to Server B.
   - **Assertions:** Server B holds player in login queue until Server A's lease expires. Server B then executes failure takeover under $E + 1$, loading last durable checkpoint.
7. **`PlayerDestinationCrashAfterSessionAcquireTest` (Destination Crash Post-Acquisition):**
   - **Target Invariant:** If destination node crashes after acquiring lease, subsequent node takeover recovers cleanly.
   - **Scenario:** Server B acquires session under epoch $E + 1$, then crashes before flushing new state. Player reconnects to Server C.
   - **Assertions:** Server C waits for Server B's lease to expire, takes over under $E + 2$, and loads Server A's committed handoff state.

#### 5.4.2 Generic Inventory Mutation, Vault & Trade Tests
8. **`InventoryJournalCrashBeforeApplyTest` (Journal Crash in INTENT Pre-Mutation):**
   - **Target Invariant:** Journal crash before in-memory application results in clean abort without blind refund.
   - **Scenario:** Write `INTENT` to `inventory_mutation_journals`. Crash process before mutating Folia Bukkit inventory.
   - **Assertions:** On reboot, reconciler finds inventory matches `before_fingerprint`. Journal transitions to `ABORTED`. Zero items refunded.
9. **`InventoryJournalCrashAfterApplyBeforeCommitTest` (Journal Crash Post-Mutation Pre-Commit):**
   - **Target Invariant:** Crash after in-memory slot mutation but before SQL commit must be cleanly reconciled.
   - **Scenario:** Folia thread mutates inventory to `after_fingerprint`. Crash injected before SQL transaction commit.
   - **Assertions:** On reboot, reconciler inspects slot state, reconciles state to `before_fingerprint`, increments version, and marks journal `ABORTED`.
10. **`VaultSourceDestinationFingerprintMatrixTest` (Dual-Slot Vault Fingerprint Matrix):**
    - **Target Invariant:** Dual-slot vault transfers evaluate full 3x3 matrix (Source: BEFORE, AFTER, UNKNOWN $\times$ Destination: BEFORE, AFTER, UNKNOWN).
    - **Scenario:** Parameterized execution across all 9 combinations.
    - **Assertions:** (BEFORE, BEFORE) $\to$ `ABORTED`; (AFTER, AFTER) $\to$ `COMMITTED`; (BEFORE, AFTER) $\to$ Revert dest & `ABORTED`; (AFTER, BEFORE) $\to$ Revert source & `ABORTED`; Any UNKNOWN $\to$ `RECOVERY_REQUIRED` (quarantine page, staff alert, zero blind refund).
11. **`CrossRegionTradeCrashRecoveryTest` (Cross-Region Trade Two-Phase Journal Recovery):**
    - **Target Invariant:** Player-to-player trade across independent Folia regions or servers requires two-phase journal orchestration. Crash mid-trade must roll back or recover deterministically.
    - **Scenario:** Player A in Region 1 and Player B in Region 2 initiate trade. Durable `TRADE_INTENT` is persisted with expected inventory versions. Player A's inventory is mutated on `EntityScheduler(Player A)`. Node crashes before Player B's inventory is mutated.
    - **Assertions:**
      - Recovery worker detects uncommitted trade journal (`TRADE_INTENT`, Player A `APPLIED`, Player B `PENDING`).
      - Recovery worker safely restores Player A's inventory from `before_fingerprint`, marks trade `ABORTED`, and increments versions. For this tested recoverable trade failure scenario, recovery restores the expected source state and leaves no duplicate apply. (Any unhandled or externally modified slot drift triggers fail-closed `RECOVERY_REQUIRED`).

#### 5.4.3 GameMode Cross-Owner Economic Inventory & Schema Portability Tests
12. **`CompoundInventoryIntentPrecedesLiveMutationTest` (Durable Intent Precedes Live Mutation):**
    - **Target Invariant:** `InventoryMutationJournal` durable `INTENT` commit strictly *happens-before* any live in-memory inventory slot mutation can execute on Folia schedulers. No strong economic inventory mutation may become externally visible before its recovery intent is durably committed.
    - **Scenario:** Multi-owner transfer initiated. Instrument Folia scheduler hooks to verify that `EntityScheduler` or `RegionScheduler` live slot modification cannot trigger until the database confirms the `INTENT` commit.
    - **Assertions:** Live slot modification timestamp is strictly greater than the `INTENT` transaction commit timestamp.
13. **`CrossOwnerEconomicInventoryTransferContractTest` (Compound Cross-Owner Economic Transfer):**
    - **Target Invariant:** Strong economic transfers across distinct inventory owners (e.g. Player Inventory v12 ↔ Vessel Cargo v7) execute as a single logical compound mutation under two authority scopes (`PlayerSessionAuthority` and `GameModeInstanceAuthority`) using a single `operationId`. Two independent single-inventory mutation calls are strictly prohibited.
    - **Scenario:** Player initiates transfer from player inventory to vessel cargo. Engine collects authorities, sorts in canonical order (`PLAYER` before `GAME_MODE_INSTANCE`), acquires row locks via `SELECT ... FOR UPDATE`, validates leases, commits `INTENT`, applies live slots, and updates both durable rows atomically in finalization.
    - **Assertions:**
      - Both `profile_inventories` ($12 \to 13$) and `vessel_inventories` ($7 \to 8$) increment version counters.
      - Exactly one journal record with `operationId = T123` transitions from `INTENT` to `COMMITTED`.
14. **`CrossOwnerInventoryCrashRecoveryTest` (Crash Recovery Across Live Apply Boundary):**
    - **Target Invariant:** If a node crashes during multi-owner transfer—specifically after source live apply but before destination live apply—recovery evaluates the single `operationId` using dual before/after fingerprints. Zero blind second commits to destination occur; source is deterministically reverted and journal marked `ABORTED`.
    - **Scenario:**
      - Phase 1 `INTENT` commits `operationId = T123` with source/dest before/after fingerprints.
      - Phase 2 applies live mutation to source Player inventory (e.g. 64 Emeralds deducted).
      - Server process is forcibly terminated (`SIGKILL`) before destination Vessel cargo is mutated.
    - **Assertions:**
      - On reboot, recovery worker loads `T123` from `inventory_mutation_journals`.
      - Inspects live slots: Source = `AFTER`, Destination = `BEFORE`.
      - Reconciler reverts Player inventory to `before_fingerprint` (restores 64 Emeralds), increments source OCC version, marks `T123` `ABORTED`.
      - Destination Vessel cargo remains untouched. For the tested AFTER/BEFORE recoverable state, recovery restores the expected source/destination state and leaves no duplicate apply. If any participant exhibits an unrecognized slot fingerprint, recovery transitions immediately to `RECOVERY_REQUIRED` without blind refund or commit.
15. **`GameModeSchemaDialectPortabilityTest` (Dialect Portability Across Supported Engines):**
    - **Target Invariant:** GameMode logical schema and authority tables must compile and function identically across PostgreSQL, MySQL/MariaDB, and SQLite without dialect-specific syntax failures (e.g. `ON UPDATE CURRENT_TIMESTAMP`).
    - **Scenario:** Execute schema creation and mutation statements across testcontainers for PostgreSQL, MySQL, MariaDB, and SQLite.
    - **Assertions:** All schema migrations, authority row locks, and timestamp assignments (`updated_at = CURRENT_TIMESTAMP`) execute cleanly across all 4 database engines.

#### 5.4.4 Disaster Recovery, Restore & `ECONOMIC_ITEM_STATE` Boundary Tests
16. **`RestoreWorldContainerEconomicDupePreventionTest` (Snapshot Container Dupe Prevention):**
    - **Target Invariant:** A snapshot must not restore historical chest/container inventories over current durable economic state.
    - **Scenario:** At T1, snapshot captures island with 64 Diamond Blocks in a chest. At T2, player transfers 64 Diamond Blocks from chest to virtual vault. At T3, admin executes `/is admin rollback` in `FULL_ISLAND` or `WORLD_CONTENT_SAFE` mode.
    - **Assertions:**
      - Restore coordinator restores world geometry and blocks, but explicitly clears or skips historical container contents (`ECONOMIC_ITEM_STATE`).
      - Chest remains empty. Vault retains 64 Diamond Blocks. Item duplication is strictly prevented.
17. **`RestoreDroppedItemEconomicDupePreventionTest` (Dropped Items Excluded from Restore):**
    - **Target Invariant:** Historical dropped `Item` entities must not be respawned during restore if they represent economic value.
    - **Scenario:** At T1, snapshot captures 20 Diamonds dropped on ground. At T2, player picks up diamonds and commits to player inventory. At T3, island is restored.
    - **Assertions:** Dropped item entity from snapshot is excluded from respawn. Player retains inventory diamonds. Duplication prevented.
18. **`RestoreCrashAfterUnitApplyBeforeProgressCommitTest` (Idempotent Unit Replay on Crash):**
    - **Target Invariant:** World unit application is defined as idempotent/deterministic replacement. Crash after unit apply but before `restore_unit_progress` commit replays safely.
    - **Scenario:** Unit 5 chunk state is applied to world. Host crashes before persisting `state = 'VERIFIED'` in `restore_unit_progress`.
    - **Assertions:**
      - On reboot, recovery worker finds Unit 5 in `APPLY_INTENT`.
      - Recovery worker safely re-applies Unit 5 using deterministic replacement.
      - Checksum verifies, unit transitions to `VERIFIED`, and restore advances to Unit 6.
19. **`RestoreRejectsUnsafeEconomicRollbackTest` (Restore Rejects Economic Rollback):**
    - **Target Invariant:** World snapshots MUST NEVER roll back economic balances, player inventories, vault ledgers, or outbox/audit tables.
    - **Scenario:** Restore executed on island with bank balance changes.
    - **Assertions:** `island_banks`, `profile_inventories`, `island_vault_pages`, and `bank_transactions` remain untouched.
20. **`SnapshotEntityMovementDedupTest` (Entity Movement Deduplication Across Regions):**
    - **Target Invariant:** Non-point-in-time asynchronous entity capture across Folia chunk regions deduplicates entities by UUID.
    - **Scenario:** Entity moves across chunk boundary during snapshot capture.
    - **Assertions:** Manifest deduplicates entity by UUID; restore respawns exactly 1 entity.

#### 5.4.5 Database Durability, Timeout & Outbox Tests
21. **`SQLiteWalNormalRejectedByDurableProductionTest` (SQLite NORMAL Rejected under Production):**
    - **Target Invariant:** `PRAGMA synchronous = NORMAL;` is rejected under `DURABLE_PRODUCTION` profile.
    - **Scenario:** Configure SQLite with WAL and `synchronous = NORMAL`. Boot plugin in `PRODUCTION_STRICT`.
    - **Assertions:** Plugin enable fails, throwing `FatalDurabilityConfigurationException`. Plugin disabled fail-fast.
22. **`DatabaseNonDurableConfigurationDetectionTest` (Fail-Fast on Non-Durable DB Config):**
    - **Target Invariant:** Non-durable settings (e.g. MySQL `innodb_flush_log_at_trx_commit != 1` or PG `synchronous_commit = off`) fail plugin boot.
    - **Assertions:** `uxmSkyblock plugin enable FAILS` with `FatalDurabilityConfigurationException`.
23. **`MySqlDmlDeadlineBehaviorTest` (MySQL DML Deadline via JDBC Query Timeout):**
    - **Target Invariant:** MySQL `max_execution_time` applies only to `SELECT`. DML deadlines use JDBC query timeout.
    - **Assertions:** Verifies `Statement.setQueryTimeout()` sends `KILL QUERY` on timeout.
24. **`MySqlQueryTimeoutUnknownOutcomeRecoveryTest` (MySQL Unknown Outcome Recovery):**
    - **Target Invariant:** If query timeout cancellation produces an uncertain transaction outcome, the physical connection is evicted and operation is resolved via idempotency recovery.
    - **Scenario:** Simulate ambiguous timeout where socket blocks during cancellation.
    - **Assertions:** Connection is evicted from HikariCP (`hikariDataSource.evict(conn)`). Operation is tagged `TRANSACTION_OUTCOME_UNKNOWN` and reconciled via journal.
25. **`OutboxPoisonEventDeadLetterTest` (Poison Outbox Event Exponential Backoff & DLQ):**
    - **Target Invariant:** Poison outbox events back off exponentially and transition to `DEAD_LETTER` upon reaching `max_retries = 5`.
    - **Assertions:** Event moves to `DEAD_LETTER`, `last_error` recorded, valid queue processing continues unblocked.


---

## 6. The "No Skipped Tests" Mandate

A skipped test protects nothing. A Gradle verification task (`verifyNoSkippedTests`) inspects JUnit XML reports:
* If any test is skipped (`skipped > 0`), the build fails immediately.
* No tests may be marked `@Disabled` without an active, tracked fix branch.
