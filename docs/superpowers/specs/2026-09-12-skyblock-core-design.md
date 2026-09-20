# UXPLIMA Skyblock — Core Architecture & Gameplay Design Specification

**Document Version:** 1.0.0
**Date:** 2026-09-13
**Status:** FROZEN BY PRODUCT OWNER — 2026-09-13
**Authors:** UXPLIMA Architectural Team & Lead Developer
**Platform Target:** Paper 26.2+ & Folia on Java 25+

---

## 1. Executive Summary & Design Vision

UXPLIMA Skyblock is an enterprise-grade, distributed, next-generation Minecraft Skyblock system engineered to scale seamlessly across a federated server cluster. Built natively with **Hexagonal Architecture (Ports & Adapters)** and **Domain-Driven Design (DDD)**, it eliminates all common competitor bottlenecks (tick-freezing synchronous scans, console command dispatch antipatterns, hardcoded mechanics, and monolithic single-threaded assumptions).

### 1.1 Federated Capacity Targets
To ground scalability in concrete distributed metrics, UXPLIMA Skyblock targets:
- **1,000,000+ Registered Player Profiles** across the network database.
- **250,000+ Persisted Islands** stored in distributed SQL storage.
- **10,000+ Concurrent Online Players (CCU)** across the federated Folia cluster.
- **500 to 1,500 Concurrent Active Islands in Memory per Sub-Server Node**, with automatic unload-on-idle eviction keeping memory footprint bounded.

### 1.2 Performance Service Level Objectives (SLOs)
> [!IMPORTANT]
> **Status: UNVERIFIED PERFORMANCE TARGETS (Pending Formal Benchmark Verification)**
> The following metrics represent design target objectives under the reference testbed profile. They are not empirical guarantees until verified through automated reproducible stress testing.

* **Target Metrics under Reference Workload:**
  - **L1 In-Memory Cache Lookups:** $p99 < 1.5\text{ ms}$ target (Caffeine / FastUtil primitives).
  - **Redis Routing & Cluster Directory Lookups:** $p99 < 0.5\text{ ms}$ target.
  - **Bank & Economic Transactions:** $p99 < 5\text{ ms}$ target (including SQL CAS execution and local striped locking).
  - **Dynamic Valuation Recalculation:** $p99 < 2\text{ ms}$ target on single-item dynamic price updates (via `IslandMaterialIndex` delta).
  - **Folia Tick Cadence:** Sustained **20.0 TPS** per active region thread target under nominal load; graceful degradation via adaptive backpressure when TPS drops below $19.5$.

* **Required Benchmark Testbed Specification:**
  To transition these targets to verified SLAs, benchmarks must be executed against the following reference profile:
  - **Hardware:** AMD Ryzen 9 7950X / 9950X (16 cores, 32 threads, base clock $\ge 4.5\text{ GHz}$), 64 GB DDR5 ECC RAM (5600 MHz+), PCIe Gen4 NVMe M.2 SSD ($7,000\text{ MB/s}$ read, $5,000\text{ MB/s}$ write).
  - **Cluster Topology:** 3 Folia backend server instances + 1 Velocity proxy instance + 1 Dedicated MySQL 8.0 / PostgreSQL 15 database instance + 1 Redis 7.2 cluster instance.
  - **Network Interconnect:** Dedicated private virtual switch with Round-Trip Time ($\text{RTT}$) $< 0.2\text{ ms}$.
  - **JVM Environment:** Eclipse Temurin JDK 25 with ZGC Generational enabled (`-XX:+UseZGC -XX:+ZGenerational -Xms16G -Xmx16G`).
  - **Workload Parameters:** 10,000 simulated concurrent players (CCU), 1,500 active islands loaded in memory per backend instance, against a pre-seeded database of 250,000 persisted islands.
  - **Measurement Methodology:** 60 minutes sustained load test duration; separate recording for Cold-Start (0% cache hits) and Warm Steady-State (95%+ cache hits); latency measured using HdrHistogram ($p50$, $p90$, $p99$, $p99.9$).

### 1.3 Architectural Invariants: Technical Correctness vs Gameplay Policies
- **100% Externalized Gameplay Policy:** All gameplay rules, economy pricing, upgrade costs, mission rewards, permission names, display layouts, biomes, and timer durations are defined in human-readable HOCON configuration files. Zero gameplay values are hardcoded in Java.
- **Strictly Typed Technical Invariants:** Foundational correctness guarantees—such as UUID representations, data integrity checks, optimistic concurrency versions, authority epochs, byte layouts, bitmasks, database schema migrations, and communication protocols—are enforced in compile-time strongly typed Java code and cannot be weakened by configuration errors.

### 1.4 Product Scope Rule & Resolved Decisions (V1 Scope Target)
> [!IMPORTANT]
> ### PRODUCT OWNER RELEASE-SCOPE RULE — V1 ONLY
> The Product Owner has explicitly rejected the obsolete Day-0 / Day-1 / contracts-now / implementation-later / post-launch scope model.
> **There is exactly one release target: V1.**
> Every Product-Owner-approved feature in the architecture must have its production implementation completed in V1:
> $$\text{APPROVED PRODUCT REQUIREMENT} \implies \text{V1 COMPLETE IMPLEMENTATION REQUIRED}$$
> A capability being optional, configurable, modular, or provider-based does NOT mean it may be unfinished:
> - **OPTIONAL MODULE $\ne$ DEFERRED MODULE**
> - **OPTIONAL PROVIDER $\ne$ FUTURE IMPLEMENTATION**
> - **CONTRACT DEFINED $\ne$ V1 COMPLETE**
> The implementation exists completely in V1; server administrators may enable, disable, or tune providers at runtime/startup.

> [!NOTE]
> ### RESOLVED PRODUCT DECISION A: Advanced Subsystems & Competitive Rollout Scope
> **Status: RESOLVED.**
> In accordance with the V1 Product Scope Rule, all approved advanced subsystems and competitive capabilities require complete production implementations in V1:
> - Seasons (Section 2.33)
> - Social Ratings / Guestbook / Discovery (Section 2.35)
> - Discord Webhooks (Section 2.36)
> - Alliances (Section 2.28)
> - Dynamic Shop Pricing Engine (Section 2.42.8)
> - Competitive Capabilities: TemporaryAccessGrant, Compiled Namespaced Permission Registry, Durable Reward Inbox, StartTemplateBundle, LeaderboardMetricProvider, PlacementStrategy / RoutingState, Player-Facing Activity Feed, Durable Offline Notification Inbox, Reset / Lifecycle Policy, Multiple Homes, Visitor Policy Extensions, Offline Simulation Policy.
> *(Note: This defines V1 scope; Phase 1 implementation remains subject to separate Product Owner authorization).*

> [!NOTE]
> ### RESOLVED PRODUCT DECISION B: General Object Storage & Backup Architecture
> **Status: RESOLVED.**
> Replaces narrow S3/R2 backup-specific assumptions with a general, reusable object-storage architecture:
> - **Built-in Snapshot / Rollback Engine:** V1 REQUIRED
> - **Local Filesystem Storage Adapter:** V1 REQUIRED
> - **General S3-Compatible Object Storage Adapter:** V1 REQUIRED
> - **AWS S3 Verified Compatibility:** V1 REQUIRED
> - **Cloudflare R2 Verified Compatibility:** V1 REQUIRED
> Server operators configure topology (`LOCAL`, `REMOTE`, `MIRRORED`); adapter implementations are fully delivered in V1.

---

## 2. Decision Log & Confirmed Specifications

### 2.1 World Management & Island Allocation (`WorldGridPort`)
* **Decision:** Multi-Strategy World Provider via Hexagonal Port (`WorldGridPort`) with Folia Region Isolation Heuristics.
* **Strategies Implemented:**
  1. **Spiral Grid (`SpiralGridAdapter`):** Single void world (`skyblock_world`) with mathematical Archimedean/Ulam spiral coordinate allocation `(x, z)`.
     - **Folia Concurrency Heuristic:** Island distance defaults to `5,120` blocks (~10 Folia regions). This distance serves as an isolation heuristic to maximize independent chunk-region formation and minimize the probability of active player islands merging into common tick regions.
     - **Thread Invariant:** No fixed island-to-thread affinity is assumed or guaranteed; Folia dynamically splits and merges loaded chunk regions across a tick-thread pool. World and entity ownership is governed by Folia's `RegionScheduler`, while plugin domain state ownership is governed by our internal concurrency model.
  2. **Sharded Grid (`ShardedGridAdapter`):** Partitions islands into numbered void worlds (`skyblock_shard_1`, `skyblock_shard_2`) at a configurable threshold (e.g., 2,000 islands per world) to prevent oversized Anvil MCA region directories while preserving spiral threading.
  3. **Slime World Manager (`SlimeWorldAdapter`):** Pluggable adapter supporting AdvancedSlimePaper (ASP) / SlimeWorldManager (SWM) SRF lightweight worlds. Unloads idle islands to disk/database. Configured as an optional provider strategy.
* **Core Invariant:** The core domain (`:core`) remains completely decoupled from world storage semantics, operating strictly on `IslandId`, `IslandBounds`, and `IslandLocation`.

---

### 2.2 Island Profiles & Game Modes
* **Decision:** Full Multi-Profile Architecture with Profile-Scoped State & Inventory Isolation, Crash-Consistent State Machine, Strict Data Scoping, and Concurrency Guards.
* **Multi-Profile Domain Model:**
  1. **Canonical Domain Entity Hierarchy:**
     - `PlayerAccount` maintains `playerUuid`, offline default `activeProfileId`, in-flight `activeSwitchOperationId`, and associated `Set<ProfileId>`.
     - `Profile` aggregates `ProfileId`, `playerUuid`, `Ruleset` (policy layer: `CLASSIC`, `IRONMAN`, `HARDCORE`, `STRANDED`), and `Participations` (linkage to active gameplay instances).
     - **Decoupled Root Hierarchy:**
       $$\text{PlayerAccount} \longrightarrow \text{Profile} \longrightarrow \text{Ruleset} + \text{Participation(s)} \longrightarrow \text{GameModeInstance} \longrightarrow \text{PrimaryGameplayRootRef}$$
       - SkyBlock $\longrightarrow$ `Island`
       - TradeWinds $\longrightarrow$ `Vessel`
       - Parkour $\longrightarrow$ `Course`
       - Brix $\longrightarrow$ `CreativeArea`
     - Generic `Profile` contains **zero hardcoded `IslandId` or `GameModeId` references**.
     - Core APIs query by profile: `getProfiles(UUID)`, `getActiveProfile(UUID)`. Mode-specific convenience queries (e.g. `findIslandByProfile(ProfileId)`) exist exclusively as mode-specific projection queries within Island context where `PrimaryGameplayRootRef` is an `Island`.
  2. **Concurrent Switch Prevention (Single Active Switch Invariant):**
     - A player can execute at most one profile switch operation at any given moment.
     - Before initiating a switch, the engine attempts an atomic CAS reservation on `player_accounts`:
       ```sql
       UPDATE player_accounts
       SET active_switch_operation_id = :operationId
       WHERE player_uuid = :playerUuid
         AND active_switch_operation_id IS NULL;
       ```
     - If zero rows are affected, another switch operation is already active or in progress. The request is immediately rejected with `ErrorCode.PROFILE_SWITCH_ALREADY_IN_PROGRESS`.
   3. **Profile-Switch Crash Consistency State Machine (`ProfileSwitchOperation`):**
       - Minecraft inventory/player state and SQL databases do not share a single 2-phase commit (2PC) resource. To eliminate item duplication or item loss across server crashes or network interruptions, profile switching executes through a durable write-ahead state machine:
         ```
         [PREPARING]
             ↓ (Acquire active_switch_operation_id lock, lock player interactions, capture live NBT)
         [SOURCE_SNAPSHOTTED]
             ↓ (Flush source profile inventory & state to SQL)
         [TARGET_LOADED]
             ↓ (Read target profile data from SQL into memory buffer)
         [TARGET_APPLY_INTENT]
             ↓ (Persist TARGET_APPLY_INTENT and COMMIT to SQL before player entity mutation)
         [PLAYER_APPLIED]
             ↓ (Apply target snapshot to Player entity on Folia EntityScheduler)
         [COMMITTED]
             ↓ (Atomically commit active_profile_id in SQL and clear active_switch_operation_id)
         [FAILED / RECOVERY_REQUIRED]
         ```

       - **Comprehensive State Transition & Persistence Table:**
         | State | Persisted in SQL? | Pre-requisites | Allowed Player Interactions | Crash Recovery Direction | Replay Action on Reconnect / Reboot | Next Legal States |
         |---|---|---|---|---|---|---|
         | `PREPARING` | Yes (`profile_switch_operations`) | Atomic CAS on `active_switch_operation_id` succeeds | NONE (All inventory, move, command, and drop events locked) | Rollback | Clear `active_switch_operation_id`; mark switch `FAILED`. Player remains on source profile. | `SOURCE_SNAPSHOTTED`, `FAILED` |
         | `SOURCE_SNAPSHOTTED` | Yes (`profile_switch_operations`, `profile_inventories`) | Live player state captured and flushed to SQL | NONE | Rollback | Clear `active_switch_operation_id`; reload source profile $P_1$ snapshot into player. | `TARGET_LOADED`, `FAILED` |
         | `TARGET_LOADED` | Yes (`profile_switch_operations`) | Target profile $P_2$ read into memory buffer | NONE | Rollback | Clear `active_switch_operation_id`; reload source profile $P_1$ snapshot into player. Target state was never applied. | `TARGET_APPLY_INTENT`, `FAILED` |
          | `TARGET_APPLY_INTENT` | Yes (`profile_switch_operations`) | Target data validated; intent committed to SQL | NONE | **Roll-forward** | Execute apply on Folia `EntityScheduler`, serialize on `player_sessions`, update `player_sessions.active_profile_id = P_2` and `player_accounts.active_profile_id = P_2`, set `COMMITTED`. Prevents lost progress if crash occurs mid-apply. | `PLAYER_APPLIED`, `RECOVERY_REQUIRED` |
          | `PLAYER_APPLIED` | Yes (`profile_switch_operations`) | Snapshot applied to Player entity on Folia thread | NONE | **Roll-forward** | Finalize DB commit: serialize on `player_sessions` row lock, validate node/epoch/lease, atomically synchronize `player_sessions.active_profile_id = P_2` and `player_accounts.active_profile_id = P_2`, clear `active_switch_operation_id`, transition to `COMMITTED`. | `COMMITTED`, `RECOVERY_REQUIRED` |
          | `COMMITTED` | Yes (`player_sessions`, `player_accounts`, `profile_switch_operations`) | SQL transaction committed atomically | FULL (Player interactions unlocked) | None | Normal active gameplay. Clear dangling `active_switch_operation_id` if present. | Terminal |
         | `FAILED` | Yes (`profile_switch_operations`) | Rollback completed safely | FULL | None | Audit record retained; player remains on source profile. | Terminal |
         | `RECOVERY_REQUIRED` | Yes (`profile_switch_operations`) | Irreconcilable conflict or repeated DB crash during roll-forward | QUARANTINED (Login blocked, operator alerted) | Quarantine | Block player login; emit high-priority operator alert for administrative reconciliation. | Manual Admin Fix |

        - **Canonical Player State Authority, Session Leasing & Durability Contract:**
          - **Durability Semantic Separation (Financial vs Inventory):**
            - **Non-Inventory Financial State (Island Bank):** Durably committed within the database engine's configured `DURABLE_PRODUCTION` profile.
            - **Player Inventory State:** Live Minecraft memory and SQL databases do not share a 2-phase commit (2PC) resource. Routine gameplay (mining cobblestone, picking up mob drops, shooting arrows) mutates Bukkit inventory in JVM memory; synchronously writing to SQL on every item pickup would exhaust database IOPS and degrade tick rates.
          - **Write-Ahead Inventory Mutation Journal Architecture (Item Inventories):**
            - High-value Minecraft and container inventory item mutations (e.g. island vault item transfers, container extraction, high-value item exchanges) execute through **`InventoryMutationJournal`** (`expected_inventory_version` + write-ahead journal entry in SQL (`INTENT`) + in-memory application + final durable commit bumping `profile_inventory_version`).
            - `InventoryMutationJournal` is scoped strictly to economic item inventories; non-inventory subsystems use their own approved durability protocols:
              - SQL-owned currency balances and island bank mutations $\to$ canonical SQL OCC + operation-id / processed-operations protocol.
              - External Vault or third-party economy integrations $\to$ approved saga / idempotency / unknown-outcome recovery protocol.
              - Player profile switches $\to$ canonical `player_sessions` authority + `profile_switch_operations` protocol.
              - Peer-to-peer trades $\to$ approved compound operation / authority / journal protocol matching the exchanged asset types.
          - **RESOLVED PRODUCT OWNER DECISION — Ambient Player-State Durability (Hybrid Model):**
            > [!IMPORTANT]
            > **Ambient Player-State Durability Policy:** The Product Owner has explicitly resolved the former open decision (`Maximum Accepted Ambient Player-State Loss Window` is now **RESOLVED**). The canonical V1 architecture adopts the **`HYBRID`** durability model:
            > - **Durability Mode:** `HYBRID` (canonical V1 product mode).
            > - **Routine Ambient Checkpoint Default:** 60 seconds (operator-configurable via typed configuration).
            > - **Critical / Economic / Valuable Mutations:** Immediately durable through the already-approved durability protocol owned by the relevant subsystem.
            >
            > **1. Exact Semantic Split:**
            > - **Critical Path (Immediate Durability):** Value-sensitive mutations bypass the periodic checkpoint interval and commit immediately via their subsystem's existing approved durability protocol:
            >   - Minecraft / economic inventory item mutation requiring immediate durability $\to$ `InventoryMutationJournal` (`expected_inventory_version` + write-ahead SQL journal entry (`INTENT`) + in-memory application + final durable commit bumping `profile_inventory_version`).
            >   - SQL-owned bank / currency balance mutation $\to$ canonical SQL transaction + OCC + operation-id / `processed_operations` contract.
            >   - External Vault / third-party economy side effect $\to$ approved saga / idempotency / unknown-outcome recovery protocol.
            >   - Profile switch $\to$ canonical `player_sessions` authority + `profile_switch_operations` protocol.
            >   - Peer-to-peer trade $\to$ approved compound authority / operation / journal protocol matching exchanged asset types.
            >   - Reward component $\to$ `RewardClaimCoordinator` routes each component to its owning durability protocol with stable `RewardComponentOperationId`.
            >   - Vault / player-inventory commit $\to$ existing approved inventory/economic operation protocol.
            >   - Handoff finalization $\to$ canonical session/root authority + required final durable commit.
            >   - *Invariant:* No new universal durability journal may be invented; `InventoryMutationJournal` is scoped strictly to economic item inventories.
            > - **Routine Ambient State Path (Periodic Checkpointed Durability):** Routine low-risk mutable state (e.g. routine block drops, mob experience, ambient item pickups) targets a **60-second default checkpoint cadence**. The exact uncheckpointed interval may include scheduler/flush execution delay and is governed by checkpoint completion semantics (not an unconditional mathematical guarantee of $\le 60\text{s}$ under arbitrary crash timing, but bounded by checkpoint completion).
            >
            > **2. Critical vs Routine Classification:**
            > - The architecture supports policy-driven classification (`DurabilityClassification`: `IMMEDIATE`, `CHECKPOINTED`).
            > - Does NOT hardcode arbitrary item rarity tables into core durability logic.
            > - The owning subsystem's approved durability policy remains authoritative. Routine state remains `CHECKPOINTED` where the owning subsystem explicitly permits it; critical operations require `IMMEDIATE` durability.
            >
            > **3. Canonical Typed Configuration Contract:**
            > ```hocon
            > player-state {
            >     durability-mode = HYBRID
            >     ambient-checkpoint-interval = 60s
            > }
            > ```
            > - `durability-mode = HYBRID`: V1 canonical product mode.
            > - `ambient-checkpoint-interval = 60s`: Configurable duration with a 60-second default. Business logic never hardcodes "60" directly; it queries this typed policy. Validation rejects non-positive or malformed durations according to configuration standards.
            >
            > **4. Checkpoint Execution & Folia Threading Invariant:**
            > - Ambient checkpointing preserves Folia and distributed authority architecture. There is **NO global main-thread autosave loop**.
            > - Respects Folia `EntityScheduler` / region scheduler ownership: an immutable memory snapshot is captured on the player's valid Folia thread, then dispatched asynchronously for relational persistence.
            > - Checkpoint persistence MUST serialize on the canonical `player_sessions` row lock:
            >   `SELECT authoritative_node, session_epoch, state, lease_expires_at FROM player_sessions WHERE player_uuid = :playerUuid FOR UPDATE;`
            >   validating `authoritative_node == currentNode`, `session_epoch == expectedEpoch`, `state == 'ACTIVE'`, and `lease_expires_at >= DB_CURRENT_TIME` before committing aggregate state under OCC.
            > - **Active State Invariant:** If `state != 'ACTIVE'` (specifically `DRAINING`, `HANDOFF_READY`, `RECOVERING`, `OFFLINE`, `LOCAL_FENCED`, or any other non-`ACTIVE` state), the routine ambient checkpoint write is strictly rejected/aborted without performing a canonical player/profile write.
            > - Aborting a rejected ambient checkpoint MUST NOT clear the in-memory ambient dirty state.
            > - Checkpoint scheduling may be staggered/jittered operationally to prevent synchronized write spikes, without hardcoding numerical jitter constants into core architecture.
            >
            > **5. Checkpoint Coalescing:**
            > - Multiple routine dirty mutations between checkpoints are coalesced into the next ambient aggregate checkpoint where correctness permits.
            > - An `IMMEDIATE` mutation must **NOT** be downgraded to checkpointed durability merely because an ambient checkpoint is scheduled.
            > - A successful immediate durable operation updates/advances in-memory dirty/version state so the next ambient checkpoint does not redundantly replay the operation.
            >
            > **6. Distinction from Dedicated Finalization Flushes & Handoff Race Resolution:**
            > - Periodic ambient checkpoint $\ne$ quit final flush $\ne$ profile switch finalization $\ne$ cross-node handoff final flush $\ne$ server controlled shutdown drain.
            > - `DRAINING` remains strictly valid and reserved for its dedicated finalization workflows (cross-node handoff final flush, controlled shutdown drain). Those workflows execute their own stronger finalization protocols.
            > - High-priority exit/switch paths execute immediately under their previously approved stronger authority/finalization protocols and never wait for the ambient checkpoint interval.
            > - **Handoff / Checkpoint Race Resolution:**
            >   1. Ambient checkpoint snapshot/work is prepared while session is `ACTIVE`.
            >   2. Before SQL commit authority validation, session transitions to `DRAINING` for cross-node handoff.
            >   3. Ambient checkpoint acquires `player_sessions` row lock (`FOR UPDATE`).
            >   4. Authority validation observes `state != 'ACTIVE'` (`state == 'DRAINING'`).
            >   5. Ambient checkpoint aborts without performing a canonical write.
            >   6. Ambient dirty state is not incorrectly cleared.
            >   7. Dedicated handoff/finalization path performs the required final durable flush.
            >   This prevents a stale routine checkpoint snapshot from committing after ownership lifecycle has entered finalization.
            >
            > **7. Failure Behavior & Stale Node Fencing:**
            > - If an ambient checkpoint fails (transient DB timeout/IO error), dirty state is **NOT** silently cleared; state is retained/re-marked for retry or transition to fail-closed handling.
            > - If authority validation fails (session epoch advanced or lease expired due to failover takeover), the stale node **MUST NOT write**. Session fencing fails closed immediately. Local unsafe fallback writes are strictly prohibited.
          - **Folia Player Lifecycle & Threading Invariant:**
            - **Official Folia Execution Context Rule:** *Folia events may execute in parallel on their valid owning region context. Any immediate player access must be valid for the current event execution context. Any deferred or later access/mutation to the Player MUST use that Player's `EntityScheduler`.*
            - Synchronous database I/O inside player quit or disconnect event handlers is **STRICTLY PROHIBITED** on Folia to prevent freezing entity tick loops.
            - **Asynchronous Safe Flush Protocol:**
              1. In the player's valid Folia execution context during disconnect/quit, the player's profile and inventory state are extracted into an **immutable memory snapshot**.
              2. The snapshot is immediately dispatched to an asynchronous persistence executor to persist to `profile_inventories`.
              3. The player's write authority lease remains locked in `player_sessions` until the asynchronous flush is durably committed.

        - **Distributed Player Session Authority & Single-Writer Invariant (`player_sessions`):**
          - *Invariant:* **At most one backend node may hold write authority over a player's canonical profile and inventory state at any given moment.**
          - Canonical table: `player_sessions (player_uuid PK, active_profile_id, authoritative_node, session_epoch, state, handoff_id, handoff_target_node, handoff_expires_at, last_durable_inventory_version, lease_expires_at, updated_at)`.
          - Legal Session States: `ACTIVE`, `DRAINING`, `HANDOFF_READY`, `RECOVERING`, `OFFLINE`, `LOCAL_FENCED`.

        - **Source Node Conservative Local Monotonic Self-Fencing Protocol:**
          - If a source node loses database connectivity, it cannot query the database to discover whether its lease has expired.
          - To prevent network and JDBC response delays from extending the assumed local authority window beyond the database lease, Node A records a monotonic timestamp **BEFORE** dispatching the renewal request:
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
            - The node initiates player transfer or disconnects with `DISCONNECT_STALE_AUTHORITY` before its database lease expires.

        - **Exact Cross-Server Player Handoff Protocol:**
          When a player switches from Server Node A to Server Node B via proxy:
          1. **Node A (`ACTIVE` $\to$ `DRAINING`):** Node A executes:
             ```sql
             UPDATE player_sessions SET state = 'DRAINING', updated_at = CURRENT_TIMESTAMP
             WHERE player_uuid = :uuid AND authoritative_node = :nodeA AND session_epoch = :epoch AND state = 'ACTIVE' AND lease_expires_at >= CURRENT_TIMESTAMP;
             ```
             All incoming player inventory interactions, item drops, and command inputs are immediately blocked.
          2. **Immutable Snapshot & Journal Flush:** Within the player's entity execution context on Node A, the final player state is captured into an immutable snapshot. The pending `InventoryMutationJournal` entries and final inventory NBT flush to SQL.
          3. **Version Increment & Hand-off Ready:** `last_durable_inventory_version` is incremented, and Node A executes:
             ```sql
             -- DB_NOW_PLUS(:seconds) is the canonical dialect-neutral clock primitive
             UPDATE player_sessions SET state = 'HANDOFF_READY', handoff_id = :handoffId, handoff_target_node = :targetNodeB, handoff_expires_at = DB_NOW_PLUS(30), lease_expires_at = DB_NOW_PLUS(30), updated_at = CURRENT_TIMESTAMP
             WHERE player_uuid = :uuid AND authoritative_node = :nodeA AND session_epoch = :epoch AND state = 'DRAINING' AND lease_expires_at >= CURRENT_TIMESTAMP;
             ```
          4. **Node B Authority Acquisition (Two Distinct Acquisition Paths):**
             - **Path A: Planned Handoff (Destination-Scoped & Bounded Window):**
               ```sql
               UPDATE player_sessions
               SET authoritative_node = :nodeB, session_epoch = session_epoch + 1, state = 'ACTIVE', handoff_id = NULL, handoff_target_node = NULL, handoff_expires_at = NULL, lease_expires_at = DB_NOW_PLUS(15), updated_at = CURRENT_TIMESTAMP
               WHERE player_uuid = :uuid AND state = 'HANDOFF_READY' AND handoff_id = :expectedHandoffId AND handoff_target_node = :nodeB AND authoritative_node = :nodeA AND session_epoch = :expectedSourceEpoch AND handoff_expires_at >= CURRENT_TIMESTAMP;
               -- affectedRows MUST == 1. Capability-scoped to one intended destination and bounded handoff window.
               ```
             - **Path B: Failure Takeover (Unplanned / Crash of Node A):**
               ```sql
               UPDATE player_sessions
               SET authoritative_node = :nodeB, session_epoch = session_epoch + 1, state = 'RECOVERING', handoff_id = NULL, handoff_target_node = NULL, handoff_expires_at = NULL, lease_expires_at = DB_NOW_PLUS(15), updated_at = CURRENT_TIMESTAMP
               WHERE player_uuid = :uuid AND session_epoch = :expectedEpoch AND lease_expires_at < CURRENT_TIMESTAMP;
               -- affectedRows MUST == 1. Strictly distinct from Planned Handoff!
               ```
          5. **Node B Profile Load:** Node B loads the durable profile state from SQL (matching `last_durable_inventory_version`), sets `state = 'ACTIVE'` if recovering, and spawns the player into the world with mutable gameplay enabled.
          - *Commit-Time Authority Row Lock Serialization:*
            - Every authoritative player-state mutation transaction and every session `ACQUIRE` / `TAKEOVER` transition MUST serialize on the canonical `player_sessions` row:
              ```sql
              -- Step 1: Exclusive row lock on canonical session lease
              SELECT authoritative_node, session_epoch, state, lease_expires_at
              FROM player_sessions WHERE player_uuid = :playerUuid FOR UPDATE;
              -- Step 2: Validate authority, epoch, and lease_expires_at >= DB_CURRENT_TIME
              -- Step 3: Mutate profile_inventories with OCC version increment (affectedRows == 1)
              -- Step 4: COMMIT
              ```
            - **Invariant:** *Every authoritative player-state mutation and every player-session ACQUIRE/TAKEOVER transition MUST serialize on the same canonical `player_sessions` row.*
            - **Lease Expiry during an Already-Authorized Transaction:** If owner, epoch, and lease were valid when the canonical row lock was acquired, nominal lease expiration during statement execution does NOT revoke the transaction mid-flight, provided it completes within its operational deadline. `TAKEOVER` queries target the exact same canonical row and block on the lock until the transaction commits or rolls back.
              > *Invariant:* Lease expiration rejects new authoritative transactions; it does not bypass an already-authorized bounded transaction holding the canonical session row lock.
            - **Player-State Lock Hierarchy:**
              1. `player_sessions` (Row lock via `FOR UPDATE`)
              2. `player_accounts` / `player_profiles`
              3. `profile_inventories`
              4. `inventory_mutation_journals` / `outbox_events` / `processed_operations`
              - In two-player operations (trades, shared inventory moves), `player_sessions` row locks MUST be acquired in **UUID Unsigned 128-Bit Binary Order** (`Long.compareUnsigned`) to eliminate lock inversion cycles.
            - **Universal Application Scope:** Applied strictly across quit flushes, profile switches, inventory journal commits, vault player-inventory flushes, trade commits, cross-server handoffs, and ambient checkpoints under Hybrid durability.



        - **Player Handoff Crash & Partition Recovery Matrix:**
          | Failure Scenario | Canonical Player State in SQL | Authoritative Node | Session Epoch | Recovery Direction | Player Login Availability |
          | :--- | :--- | :--- | :--- | :--- | :--- |
          | **Node A crashes before `DRAINING`** | `ACTIVE`, lease held by Node A | Node A | $E$ | Node B or recovery worker detects expired lease (`lease_expires_at < NOW()`). Reconciles pending journals, flushes last checkpoint, sets `RECOVERING` $\to$ `OFFLINE`. | Fail-closed: login queued until Node A lease expires (max 15s) and recovery cleans session. |
          | **Node A crashes during `DRAINING`** | `DRAINING` | Node A | $E$ | Recovery worker inspects SQL. Because snapshot flush did not commit, worker restores player state to last durable checkpoint, sets `OFFLINE`. | Login queued until recovery sets `OFFLINE`; player connects with last durable checkpoint. |
          | **Node A crashes after snapshot capture but before SQL commit** | `DRAINING` | Node A | $E$ | In-memory snapshot was lost with Node A. Worker on reboot/heartbeat expiry rolls back uncommitted mutations to last durable version in SQL, marks `OFFLINE`. | Login allowed after lease expiration and cleanup. |
          | **Node A crashes after committing `HANDOFF_READY`** | `HANDOFF_READY` | Node A (relinquished) | $E$ | Normal planned handoff. Node B acquires session with $E + 1$, loads fully committed durable snapshot, sets `ACTIVE`. | Seamless transition: player connects to Node B without progress loss. |
          | **Node B crashes after authority acquire before player spawn** | `ACTIVE` | Node B | $E + 1$ | Node B crashed with authority. Lease expires on Node B. Player reconnects to Node C; Node C claims expired lease under epoch $E + 2$, loads durable SQL state, sets `ACTIVE`. | Login queued until Node B lease expires (max 15s); item duplication prevented by recovery model. |
          | **Proxy disconnects player midway during handoff** | `HANDOFF_READY` or `DRAINING` | Node A | $E$ | If `DRAINING`, Node A finishes flush and marks `OFFLINE`. If `HANDOFF_READY`, timeout task sets `OFFLINE` after 30s. | Player can reconnect to any node immediately once `OFFLINE`. |
          | **Network partition isolates Node A** | `ACTIVE`, lease ticking | Node A | $E$ | Node A cannot reach DB/Redis; Node A's local monotonic safety deadline expires and it triggers `LOCAL_FENCED`. Cluster detects expired lease; Node B executes takeover under $E + 1$. Node A delayed writes rejected by fencing predicate. | Node A fails closed; player reconnects to Node B safely. |

       - **Two-Phase Intent & Commit SQL Boundaries:**
         ```sql
         -- Phase A: Write-Ahead Intent Persisted BEFORE Modifying Player Entity
         START TRANSACTION;
         UPDATE profile_switch_operations
         SET state = 'TARGET_APPLY_INTENT',
             updated_at = CURRENT_TIMESTAMP
         WHERE operation_id = :operationId
           AND state = 'TARGET_LOADED';
         COMMIT;

         -- (Folia EntityScheduler applies target snapshot to Player entity here)

          -- Phase B: Final Atomic Commit Serialized on player_sessions Authority
          START TRANSACTION; -- SQLite: BEGIN IMMEDIATE

          -- 1. Acquire exclusive lock on canonical player_sessions row (obeying Global Authority Lock Order)
          SELECT player_uuid, authoritative_node, session_epoch, state, lease_expires_at
          FROM player_sessions
          WHERE player_uuid = :playerUuid
          FOR UPDATE; -- SQLite: row-level write serialization held via BEGIN IMMEDIATE

          -- 2. Application layer validates session authority invariants:
          --    authoritative_node == :currentNode
          --    session_epoch == :currentEpoch
          --    state == 'ACTIVE'
          --    lease_expires_at >= CURRENT_TIMESTAMP
          --    target profile belongs to player_uuid

          -- 3. Atomically synchronize BOTH canonical session active profile AND account default active profile
          UPDATE player_sessions
          SET active_profile_id = :targetProfileId,
              updated_at = CURRENT_TIMESTAMP
          WHERE player_uuid = :playerUuid
            AND authoritative_node = :currentNode
            AND session_epoch = :currentEpoch
            AND state = 'ACTIVE'
            AND lease_expires_at >= CURRENT_TIMESTAMP;

          UPDATE player_accounts
          SET active_profile_id = :targetProfileId,
              active_switch_operation_id = NULL,
              updated_at = CURRENT_TIMESTAMP
          WHERE player_uuid = :playerUuid
            AND active_switch_operation_id = :operationId;

          -- 4. Mark profile switch operation COMMITTED
          UPDATE profile_switch_operations
          SET state = 'COMMITTED',
              updated_at = CURRENT_TIMESTAMP
          WHERE operation_id = :operationId
            AND state IN ('TARGET_APPLY_INTENT', 'PLAYER_APPLIED');

          COMMIT;
         ```

       - **Exact Java Sealed Record Contract:**
         ```java
         public sealed interface ProfileSwitchOperation permits
             ProfileSwitchOperation.Preparing,
             ProfileSwitchOperation.SourceSnapshotted,
             ProfileSwitchOperation.TargetLoaded,
             ProfileSwitchOperation.TargetApplyIntent,
             ProfileSwitchOperation.PlayerApplied,
             ProfileSwitchOperation.Committed,
             ProfileSwitchOperation.Failed,
             ProfileSwitchOperation.RecoveryRequired {

             UUID operationId();
             UUID playerId();
             UUID sourceProfileId();
             UUID targetProfileId();
             Instant initiatedAt();

             record Preparing(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt) implements ProfileSwitchOperation {}
             record SourceSnapshotted(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, PlayerDataSnapshot sourceSnapshot) implements ProfileSwitchOperation {}
             record TargetLoaded(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, PlayerDataSnapshot sourceSnapshot, PlayerDataSnapshot targetSnapshot) implements ProfileSwitchOperation {}
             record TargetApplyIntent(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, PlayerDataSnapshot targetSnapshot) implements ProfileSwitchOperation {}
             record PlayerApplied(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt) implements ProfileSwitchOperation {}
             record Committed(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, Instant completedAt) implements ProfileSwitchOperation {}
             record Failed(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, String failureReason, boolean rollbackCompleted) implements ProfileSwitchOperation {}
             record RecoveryRequired(UUID operationId, UUID playerId, UUID sourceProfileId, UUID targetProfileId, Instant initiatedAt, String failureReason) implements ProfileSwitchOperation {}
         }
         ```

     - **Player Interaction Lockdown:** During the switch operation (from `PREPARING` through `COMMITTED`):
       - All inventory clicks, drags, and container openings are cancelled (`InventoryClickEvent`, `InventoryDragEvent`, `InventoryOpenEvent`).
       - Item dropping and pickup are cancelled (`PlayerDropItemEvent`, `EntityPickupItemEvent`).
       - Container and workstation interactions are blocked (`PlayerInteractEvent` on chests, hoppers, barrels, crafting tables).
       - Economy-sensitive commands are blocked (`/pay`, `/shop`, `/ah`, `/trade`, `/is bank`).
       - Teleportation and re-entrant profile switch commands are strictly blocked.
  4. **Strict Data Scope Taxonomy:**
     - **Profile-Scoped Data (Stored in `profile_inventories` per `ProfileId`):**
       - Main inventory, hotbar, offhand, armor slots, and crafting grid slots
       - Ender chest contents
       - Experience points and level
       - Health, max health attributes, food level, saturation, and exhaustion
       - Active potion effects and durations
        - Last logout location coordinates within the active gameplay space
        - GameMode and flight state
        - Profile-specific cooldowns and progression attributes
        - Active gameplay participations (`game_mode_participations` linking to `GameModeInstance`, e.g., Island membership for Skyblock)
        - Personal profile currency wallets (bank balances, crystals)
      - **Account-Scoped Data (Stored in `player_accounts` per `PlayerUUID`):**
       - Canonical Mojang UUID and username
       - Complete set of registered profile IDs (`Set<ProfileId>`)
       - Current active profile ID pointer (`activeProfileId`)
       - In-flight switch operation pointer (`activeSwitchOperationId`)
       - Client locale and global menu preferences
       - Global account abuse cooldowns (daily island deletion/reset cooldowns)
  5. **Ironman ↔ Classic Economy Isolation Invariant:**
     - To ensure complete economic isolation between game modes:
     - All economy operations route through a profile-isolated virtual wallet (`uxmskyblock:profile:<id>:wallet`). Even if server economy (Vault) is traditionally account-wide, Skyblock's economy hook intercepts transactions and binds balances strictly to the active profile.
     - On `ProfileType.IRONMAN`, external economic operations (Auction House, direct player trades, chest shops, dropping items to visitors, picking up items dropped by non-members) are unconditionally blocked by event guards. Ironman profiles cannot receive currency or items from Classic profiles under any circumstance.
* **Supported Profile Rulesets (Policy Layer):**
  > [!NOTE]
  > **Architectural Invariant (Ruleset ≠ GameMode):** `CLASSIC`, `IRONMAN`, `HARDCORE`, and `STRANDED` represent **Profile Rulesets** (economic and life-loss policy layers). They are completely orthogonal to **GameModes** (`uxm:skyblock`, `uxm:oneblock`, `uxm:chunkblock`, `uxm:acid_island`, etc.). Any valid Ruleset can be paired with any compatible GameMode (e.g. *Ironman OneBlock*). See full specification in [`docs/GAMEMODE_ARCHITECTURE.md`](../GAMEMODE_ARCHITECTURE.md).

  1. **Classic Ruleset (`ProfileType.CLASSIC`):** Standard cooperative or solo skyblock gameplay. Full access to island bank, auctions, player trading, and visit systems.
  2. **Ironman Ruleset (`ProfileType.IRONMAN`):** Pure self-sufficiency challenge mode. Blocked from auction houses, direct player-to-player trading, dropping items to non-profile members, and accessing public visitor shops. All items, upgrades, and progression must be crafted, farmed, or unlocked directly on the island.
  3. **Hardcore Ruleset (`ProfileType.HARDCORE`):** Shared or individual life pool tracking. Hardcore lifecycle behavior is supplied by the existing Hardcore Ruleset policy.
  4. **Stranded Ruleset (`ProfileType.STRANDED`):** Extreme survival mode where players can never leave their island void boundary. No access to `/spawn` or server hub; custom visiting wandering traders, NPC spawns, and specialized fishing/mob loot provide all progression materials.

---

### 2.3 Dynamic Upgrades & Generator Progression Subsystem
* **Decision:** 100% Dynamic, Node-Based HOCON System with Multi-Currency & Deep `uxm-lib` Ecosystem Integration.
* **Core Capabilities:**
  1. **Arbitrary Upgrade Categories:** Configured dynamically via `upgrades.conf` (e.g., `island_size`, `member_limit`, `hopper_limit`, `spawner_rate`, `crop_growth_multiplier`, `ore_generator`, `warps_limit`, `minion_slots`).
  2. **Multi-Currency & Cost Structures:** Upgrade tiers can demand complex multi-cost criteria:
     - Vault economy (`VaultEconomyProvider`)
     - Island Bank balance (`IslandBank`)
     - Island Experience / Crystal currency (`IslandExp`)
     - Physical items or custom items (`uxmlib-integration` -> ItemsAdder, Oraxen, MMOItems)
     - PlaceholderAPI numeric/boolean checks
  3. **Event & Mission Unlocks:** Upgrades can be unlocked directly through quest/mission completion events, achievements, or island level thresholds without requiring console commands.
  4. **Dynamic Cobblestone & Basalt Ore Generators:**
     - Configured in `generators.conf` with weighted drop tables per generator tier and per biome.
     - Integration with `CompositeCustomItems` (custom ores/minerals) and `CompositeCustomMobs` (for custom spawners).
  5. **Zero Hardcoding & Zero Console Command Mutators:** Internal state is mutated cleanly through domain events (`IslandUpgradeLevelChangedEvent`), eliminating console command execution antipatterns.

---

### 2.4 Island Level Calculation Engine (`IslandLevelService`)
* **Decision:** Real-Time Material Histogram Tracking (`IslandMaterialIndex`) with Authoritative Background Verification and Rigorous Computational Complexity Boundaries.
* **Core Architecture & Computational Complexities:**
  1. **Material Histogram Indexing (`IslandMaterialIndex`):**
     - Each loaded island maintains an in-memory primitive map (`Long2IntOpenHashMap`) tracking individual material counts (e.g., `DIAMOND_BLOCK -> 13,421`, `EMERALD_BLOCK -> 2,188`, `BEACON -> 52`).
     - Point calculation decouples into two distinct metrics:
       - **Island Level Score:** Evaluated from fixed, immutable weights configured in `levels.conf`.
       - **Economic Net Worth:** Evaluated as $\text{Worth} = \sum_{m=1}^{M} (\text{count}_m \times \text{currentPrice}_m)$, backed by a cached accumulator `cachedEconomicWorth`.
  2. **Computational Time Complexity Model (Eradicating Blanket O(1) Claims):**
     - **Block Event Mutation:** **Amortized $O(1)$**. Intercepting `BlockPlaceEvent` / `BlockBreakEvent` updates the FastUtil primitive map and increments/decrements `cachedEconomicWorth` and `cachedLevelScore` in $O(1)$.
     - **Single Material Price Update:** **Amortized $O(1)$**. When a dynamic shop updates the price of a single material $m$, the accumulator updates via delta calculation without iterating over other materials:
       $$\Delta_{\text{worth}} = \text{materialCount}_m \times (\text{newPrice}_m - \text{oldPrice}_m)$$
       $$\text{cachedEconomicWorth} \leftarrow \text{cachedEconomicWorth} + \Delta_{\text{worth}}$$
     - **Full Economic Worth Recomputation:** **$O(M)$**, where $M$ is the number of distinct tracked material types currently indexed in `IslandMaterialIndex`. Triggered strictly during cold cache initialization or manual administrative recalculation.
     - **Authoritative Background World Chunk Scan:** **$O(C \times B)$**, where $C$ is the number of chunks within the island boundary and $B$ is the non-air block count per chunk section. Governed by time-sliced Folia `RegionScheduler` tasks to eliminate tick disruption.
  3. **Physics & Exploitation Protection:**
     - Intercepts `BlockPlaceEvent`, `BlockBreakEvent`, `BlockExplodeEvent`, `EntityExplodeEvent`, and piston extension/retraction to maintain live histogram deltas.
     - Derived Invariant: Real-time event tracking provides near-real-time derived state.
  4. **Authoritative Background Verification Scanner:**
     - An asynchronous, Folia-native chunk scanner utilizes Folia's `RegionScheduler` and chunk-batching semaphores.
     - Serves as the authoritative source of truth, periodically healing any un-tracked world-derived drift (fluids, falling blocks, external plugin edits, `/is recalc`).
  5. **Multi-Factor Score Composition:**
     - Configurable score weight components:
       $$\text{TotalScore} = \text{Score}_{\text{blocks}} + \text{Score}_{\text{spawners}} + \text{Score}_{\text{quests}} + \text{Score}_{\text{bank}}$$

---

### 2.5 Multi-Metric Leaderboard Subsystem (`IslandLeaderboardService`)
* **Decision:** Dedicated, Independent Bounded Context with Multi-Metric Competition & Distributed Redis Indexing.
* **Architecture & Separation of Concerns:**
  - Decoupled completely from Level Calculation; listens to score mutation domain events (`IslandLevelUpdatedEvent`, `IslandBankUpdatedEvent`, etc.).
* **Supported Leaderboard Categories (Configurable):**
  1. `LEVEL`: Overall island point value and structural development.
  2. `BANK`: Total liquid wealth and assets deposited into the Island Bank.
  3. `KILLS`: Island-wide mob, boss (MythicMobs), and player kill tallies.
  4. `MISSIONS`: Total completed challenges, quests, and milestone achievements.
  5. `CUSTOM`: Extensible metric hooks powered by custom expressions and PlaceholderAPI integration.
* **Storage & Indexing Strategy:**
  - **Single-Server Mode:** In-memory balanced skip-lists / trees with Caffeine caching for instant O(1) lookups.
  - **Multi-Server / Distributed Network:** Redis Sorted Sets (`ZADD`, `ZREVRANGE`) partitioned under `uxmskyblock:leaderboard:<metric>`. Enables microsecond O(log N) cross-server rank lookups across 100,000+ islands.
* **Display & Presentation Layer:**
  - **Interactive GUIs:** `uxmlib-menu` virtual chest pagination featuring player skulls, island statistics, and live member rosters.
  - **Bedrock Forms:** `uxmlib-bedrock` native dialogs and action sheets for touch-screen mobile devices.
  - **Holograms & Podiums:** Soft-dependency adapters for DecentHolograms / FancyHolograms, alongside dynamic NPC / Armor Stand podiums (Citizens / ZNPCs) rendering top 3 player skins and island banners in real time.

---

### 2.6 Dynamic Island Roles & Granular Permission Matrix (`IslandRoleService` / `IslandPermissionService`)
* **Decision:** Fully Dynamic, Schema-Driven Role Hierarchy with Bitset/Set Granular Action Permissions and In-Game GUI Management.
* **Core Architecture & Invariants:**
  1. **Configurable Dynamic Roles (`roles.conf`):**
     - Roles are not hardcoded enums; server administrators can define arbitrary roles (e.g., `OWNER`, `CO_OWNER`, `MODERATOR`, `BUILDER`, `FARMER`, `MEMBER`, `RECRUIT`, `VISITOR`).
     - Each role specifies:
       - `id` (String identifier)
       - `weight` (Integer determining seniority/promotion hierarchy)
       - `display-name` (MiniMessage formatted title)
       - `default-permissions` (List of initial active permission nodes)
       - `is-system` (Boolean flag protecting mandatory anchor roles like `OWNER` and `VISITOR` from accidental deletion)
  2. **Granular Action Permissions (`IslandPermission`):**
     - Fine-grained permission flags categorized logically:
       - **World Interaction:** `BLOCK_BREAK`, `BLOCK_PLACE`, `BUCKET_USE`, `NATURAL_INTERACT` (doors/trapdoors), `REDSTONE_INTERACT` (buttons/levers/plates).
       - **Container Access:** `CHEST_OPEN`, `FURNACE_USE`, `SHULKER_OPEN`, `BARREL_OPEN`, `ANVIL_USE`, `BEACON_MODIFY`.
       - **Spawner & Farming:** `SPAWNER_BREAK`, `SPAWNER_CHANGE_TYPE`, `SPAWNER_UPGRADE`, `CROP_TRAMPLE_BYPASS`, `ANIMAL_BREED`, `ANIMAL_KILL`.
       - **Financial & Economy:** `BANK_DEPOSIT`, `BANK_WITHDRAW`, `SHOP_ACCESS`.
       - **Administrative:** `MEMBER_INVITE`, `MEMBER_KICK`, `MEMBER_PROMOTE`, `MEMBER_DEMOTE`, `SETTINGS_MODIFY`, `BIOME_CHANGE`, `WARP_CREATE`, `WARP_DELETE`.
  3. **Per-Island Customization & In-Game GUI / Bedrock Forms:**
     - Island owners and permitted officers can override any permission per-role directly within the island settings menu (`/is permissions` via `uxmlib-menu` and `uxmlib-bedrock`).
     - Stored as compact bitsets or keyed sets per island in database storage (`IslandRolePermissionRepository`).
  4. **Trusted Player Overrides (`IslandTrustService`):**
     - Allows assigning temporary or permanent granular permissions to external players without enrolling them as permanent island members (e.g., trusted guest builders).

---

### 2.7 Dynamic Island Flags & Environmental Protection (`IslandFlagService`)
* **Decision:** Dynamic, Schema-Driven Island Environment & Physics Flag Engine with Permission Gates and Profile Sensitivity.
* **Core Architecture & Invariants:**
  1. **Schema-Driven Flags Configuration (`flags.conf`):**
     - Environment rules are loaded dynamically from configuration:
       - **Physics & Natural Spread:** `FIRE_SPREAD`, `LEAF_DECAY`, `WATER_FLOW`, `LAVA_FLOW`, `CROP_TRAMPLE`.
       - **Spawning & Combat:** `PVP`, `MONSTER_SPAWN`, `ANIMAL_SPAWN`, `PHANTOM_SPAWN`, `EXPLOSION_DAMAGE`.
       - **Visitor Safety:** `VISITOR_ITEM_PICKUP`, `VISITOR_ITEM_DROP`, `VISITOR_PVP`.
       - **Atmosphere & Visual Perks:** `TIME_LOCK` (Day/Night/Custom ticks lock), `WEATHER_LOCK` (Clear/Rain/Thunder lock).
  2. **Profile & Permission Constraints:**
     - Profile types enforce non-negotiable flag states (e.g., in `ProfileType.HARDCORE`, `EXPLOSION_DAMAGE` cannot be toggled off).
     - Individual flags can declare required server permissions (e.g., `uxmskyblock.flag.timelock`).
  3. **High-Performance Flag Resolution:**
     - Flags are cached in-memory per loaded island using primitive bitmasks for sub-microsecond event interception.
  4. **Management Interface:**
     - Modifiable via `/is settings` through `uxmlib-menu` and native Bedrock touch forms (`uxmlib-bedrock`), gated by `IslandPermission.SETTINGS_MODIFY`.

---

### 2.8 Island Bank & Multi-Currency Economy Subsystem (`IslandBankService`, `IslandBankTransaction`)
* **Decision:** Enterprise Virtual Bank with ACID Audit Trail, Multi-Currency Wallets, Optimistic Concurrency Control, Monotonic Fencing Tokens (`authority_epoch`), and Transactional Outbox.
* **Core Architecture & Invariants:**
  1. **Multi-Currency Vaults:**
     - Each island maintains segregated numeric balances:
       - `primary_currency`: Standard server currency managed via `EconomyProvider` (Vault / PlayerPoints / CoinsEngine).
       - `island_crystals`: Secondary prestige/grind currency gained via quests, mob kills, and challenges.
       - `island_exp`: Island-level progression pool used for perk purchasing.
  2. **Distributed & Atomic Transaction Model (`IslandBankTransaction`):**
      - **Single-Writer Authority & Canonical Fencing Token (`island_authorities.authority_epoch`):**
        - In a distributed cluster, each island is leased to an authoritative node. The **single canonical mutable source of truth** for authority and fencing tokens is `island_authorities.authority_epoch`.
        - Business tables (`islands`, `island_banks`) do NOT maintain duplicate mutable epoch counters. They validate the current authority against `island_authorities` directly via subquery/EXISTS predicate.
        - **Core Invariant:** *Authority epoch advances only when authority ownership is newly acquired or transferred. Lease renewal by the current valid owner never advances the fencing epoch. A stale authority holder MUST NEVER be able to commit a mutation after a newer authority epoch has been issued.*
      - **SQL Optimistic Concurrency Control with Canonical Authority Validation (OCC / CAS):**
        - Authoritative mutations serialize on the canonical `island_authorities` row lock before modifying business balances:
          ```sql
          -- Step 1: Serialize on canonical authority row
          SELECT authoritative_node, authority_epoch, lease_expires_at
          FROM island_authorities
          WHERE island_id = :island_id
          FOR UPDATE;

          -- Step 2: Critical business mutation guarded by OCC version check
          UPDATE island_banks AS b
          SET b.primary_balance = b.primary_balance + :delta,
              b.version = b.version + 1,
              b.updated_at = CURRENT_TIMESTAMP
          WHERE b.island_id = :island_id
            AND b.version = :expected_version
            AND b.primary_balance + :delta >= 0;
          ```
      - **Critical Mutation Invariant (Affected Rows Must Equal Exactly 1):**
        - **Rule:** *Critical mutation affected rows MUST equal exactly 1.*
        - If `affectedRows == 0`, either the version is stale, the authority lease has expired or transferred, or funds are insufficient.
        - Under NO circumstance may the transaction proceed to insert audit logs or outbox events! The transaction rolls back immediately and produces a deterministic failure outcome: `REJECTED_STALE_VERSION`, `REJECTED_STALE_AUTHORITY`, or `REJECTED_INSUFFICIENT_FUNDS`.
      - **JVM-Local Striped Locking (`Striped<Lock>`):** Used strictly as a single-JVM thread contention optimization to serialize concurrent tasks across local Folia region contexts per `IslandId` prior to database execution. It is never relied upon for cluster-wide consistency.
      - **Transactional Operation Boundary & Deterministic Idempotency (`processed_operations`):**
         - `processed_operations` persists the execution outcome to return identical results for re-delivered requests:
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
               UNIQUE KEY uq_processed_ops (operation_scope, actor_id, idempotency_key)
           );
           ```
         - **Scoped Idempotency & Key Generation Policy:**
           - Uniqueness is bound to `(operation_scope, actor_id, idempotency_key)`.
           - Callers MUST provide a stable logical UUID/ULID token that is preserved across network retries. (Timestamp-derived retry keys are prohibited).
         - **Concurrency Model (Model A — Fully Atomic Single Transaction):**
           - When duplicate requests race: Request B's insert blocks on the unique index until Request A commits.
           - Upon Request A's commit, Request B catches `DuplicateKeyException`, reads Request A's committed deterministic outcome (`APPLIED` or `REJECTED`), and returns the cached result without executing duplicate mutations.
           - If the unique index lock wait exceeds `lock_timeout = 1000ms`, Request B returns `IDEMPOTENCY_OPERATION_IN_PROGRESS`.
           - Uncommitted `PENDING` states are NEVER assumed to be visible across transactions under standard READ COMMITTED / REPEATABLE READ isolation.
         - **Single-Transaction Atomic Rejection Protocol:**
           - When an authoritative business mutation fails (e.g. stale version, stale authority epoch, or insufficient funds), the transaction does NOT roll back to an unrecorded void.
           - In the **exact same transaction**, `processed_operations` is marked `status = 'REJECTED'` with the deterministic failure reason, and `conn.commit()` is executed (without inserting audit or outbox events).
         - **Multi-Island Deadlock Ordering Protocol:**
           - Operations spanning multiple islands MUST sort `IslandId`s by **UUID Unsigned 128-Bit Binary Order** (`Long.compareUnsigned(mostSigBits)` then `leastSigBits`), acquiring `island_authorities` row locks in ascending order before locking business rows.
           - **Guarantee Scope:** This protocol is *specifically designed to eliminate lock inversion cycles among UXM transactions following the hierarchy*. On anomalous deadlocks from external tools, transactions roll back and retry with exponential backoff and jitter.
      - **Transaction Execution Flow Pseudo-Code:**
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
                setSessionTimeouts(conn, 2000 /* statementTimeoutMs */, 1000 /* lockTimeoutMs */);

                // 1. Model A Idempotency: Insert scoped unique key
                try {
                    insertProcessedOperation(conn, operationId, "ISLAND_BANK", actorId.toString(), idempotencyKey, "BANK_TRANSACTION", islandId.toString());
                } catch (DuplicateKeyException dupEx) {
                    conn.rollback();
                    OperationRecord existing = findProcessedOperation(conn, "ISLAND_BANK", actorId.toString(), idempotencyKey)
                            .orElseThrow(() -> new IllegalStateException("Duplicate key found but record missing"));
                    return TransactionOutcome.cached(existing.resultCode(), existing.resultPayload());
                } catch (LockTimeoutException lockEx) {
                    conn.rollback();
                    return TransactionOutcome.inProgress("IDEMPOTENCY_OPERATION_IN_PROGRESS");
                }

                // 2. Canonical Authority Row Lock: SELECT ... WHERE island_id = :id FOR UPDATE
                AuthorityRow authority = lockCanonicalAuthorityRow(conn, islandId);
                Instant dbNow = queryDbCurrentTimestamp(conn);
                boolean isValid = authority != null
                        && nodeId.equals(authority.authoritativeNode())
                        && authority.authorityEpoch() == expectedAuthorityEpoch
                        && authority.leaseExpiresAt().isAfter(dbNow);

                if (!isValid) {
                    // Stale, expired, or wrong owner: Persist deterministic rejection in SAME transaction!
                    completeProcessedOperation(conn, operationId, OperationStatus.REJECTED, "STALE_AUTHORITY_EPOCH", null);
                    conn.commit();
                    return TransactionOutcome.rejected("STALE_AUTHORITY_EPOCH");
                }

                // 3. Execute Critical State Mutation with OCC version check
                int affectedRows = updateBankBalance(conn, islandId, expectedVersion, delta);

                if (affectedRows != 1) {
                    // Persist rejection in the EXACT SAME transaction without inserting audit or outbox events!
                    String rejectionReason = diagnoseBalanceOrVersionFailure(conn, islandId, expectedVersion, delta);
                    completeProcessedOperation(conn, operationId, OperationStatus.REJECTED, rejectionReason, null);
                    conn.commit();
                    return TransactionOutcome.rejected(rejectionReason);
                }

                // 4. Record Audit Trail & Durable Outbox Event (Only executed when affectedRows == 1)
                insertBankTransactionAudit(conn, operationId, islandId, delta);
                insertOutboxEvent(conn, operationId, islandId, "BANK_BALANCE_CHANGED", delta, expectedAuthorityEpoch);

                // 5. Finalize Idempotency Record (APPLIED)
                completeProcessedOperation(conn, operationId, OperationStatus.APPLIED, "SUCCESS", buildSuccessPayload(delta));

                conn.commit();
                return TransactionOutcome.applied(operationId);
            } catch (SQLException ex) {
                rollbackQuietly(conn);
                return TransactionOutcome.failedRetryable(ex.getMessage());
            }
        }
        ```
      - **Transactional Outbox & Redis Streams (Durable Events):** Financial events are committed to `outbox_events` in the SAME transaction. A background worker polls the outbox and streams into Redis Streams (`uxmskyblock:stream:bank_events`) with consumer group acknowledgments (`XACK`). Bare Redis Pub/Sub is NEVER used for financial transactions.
  3. **Persistent Audit Log (Transaction History):**
     - Every financial operation records: `transaction_id`, `operation_id`, `actor_uuid`, `currency_type`, `amount`, `resulting_balance`, `reason` (e.g. `UPGRADE_PURCHASE`, `PLAYER_WITHDRAWAL`, `QUEST_REWARD`), and `timestamp`.
     - Queryable via `/is bank log` in paginated `uxmlib-menu` and Bedrock forms (latest 50 transactions cached in memory, archival in SQL).
  4. **Configurable Upkeep & Passive Yield Modules:**
      - `bank.conf` supports optional automated features:
        - **Interest Rate (Passive Yield):** Percentage compounding on bank balance during server low-load schedules.
        - **Island Upkeep / Maintenance Tax:** Periodic deduction proportional to island size or upgrade level. Default is disabled (`upkeep.enabled = false`). Arrears are handled via the two-stage lifecycle (`BANKRUPTCY_GRACE` -> `BANKRUPTCY_LOCKED`) detailed in Section 2.39.

---

### 2.9 Island Template & Schematic Pasting Engine (`IslandSchematicService`, `SchematicPaster`)
* **Decision:** Self-Contained Native Sponge NBT (`.schem`) Engine with Folia Time-Sliced Batching and Optional FAWE Acceleration.
* **Core Architecture & Invariants:**
  1. **Zero Mandatory Dependencies (Standalone Engine):**
     - Integrates an internal pure-Java Sponge NBT parser capable of reading `.schem` and legacy `.schematic` files directly without requiring WorldEdit or FastAsyncWorldEdit.
     - Preserves tile entities (chests with starter starter kits, signs, furnaces) and entity spawns.
  2. **Folia-Native Time-Sliced Batching:**
     - Pasting operations execute asynchronously via Folia's `RegionScheduler` bounded to the owning region context(s) (accounting for dynamic split/merge).
     - Block placements are committed in configurable batches (e.g. 2,500 blocks per tick) to minimize region tick degradation even when multiple islands are spawned concurrently.
  3. **Soft-Dependency FastAsyncWorldEdit (FAWE) Acceleration:**
     - If FAWE is present on the runtime server, the engine dynamically binds `FaweSchematicPasterAdapter` to delegate block writes through FAWE's native low-level byte buffers for high-throughput asynchronous completion.
  4. **Dynamic Template Catalog (`templates.conf`):**
     - Schematics are organized as selectable presets (e.g. `classic_island`, `desert_pyramid`, `nether_fortress`, `hollow_cavern`).
     - Presets can specify custom permission gates (`uxmskyblock.template.vip`), custom starting biomes, and unique starter chest loot tables.

---

### 2.10 Island Visits, Public Warps & Visitor Security (`IslandWarpService`, `SafeTeleportEngine`)
* **Decision:** Multi-Warp Subsystem with Folia Safe-Spot Anti-Trap Verification, Categorized Directory, and Granular Privacy.
* **Core Architecture & Invariants:**
  1. **Named Multi-Warp System (`IslandWarp`):**
     - Islands can create multiple distinct named warp points (e.g., `market`, `pumpkin_farm`, `cactus_farm`, `parkour`).
     - Maximum warp allowances scale dynamically via island upgrades (`upgrades.conf`).
     - Each warp records: `warp_name`, `exact_location` (pitch/yaw included), `creation_time`, `icon_item`, `category`, and `is_locked`.
  2. **Folia-Native Safe-Spot Anti-Trap Engine (`SafeTeleportEngine`):**
     - Before teleporting any visitor, an asynchronous check validates the destination coordinate within the destination owning region context:
       - Checks for hazardous blocks: lava, active fire, open void drops, cactus, suffocating full solid head blocks, or active wither rose fields.
       - If unsafe conditions are detected, automatically searches for the nearest valid safe air column with a solid base within a 5-block bounding box; if none exists, teleportation cancels cleanly with a descriptive localized error (`teleport.unsafe_destination`).
  3. **Visitor Protection & Locking Controls:**
     - Global island lock (`/is lock`) prevents all non-member visits.
     - Granular warp locking (`/is warplock <name>`) allows restricting individual warps.
     - Direct player blacklist (`/is ban <player>`) immediately teleports banned visitors off the island boundary to `/spawn` and prohibits future warp entry.
  4. **Categorized Community Explorer (`/is explore` / `/is warps`):**
     - Interactive `uxmlib-menu` and `uxmlib-bedrock` directory indexing open public warps by category (Shops, Automated Farms, Parkour/Puzzles, Island Showcases).
     - Players can leave persistent ratings/vouches (`IslandVouch`) influencing directory sorting and prestige metrics.

---

### 2.11 Island Biome Architecture & Environmental Modification (`IslandBiomeService`, `BiomeChunkEngine`)
* **Decision:** Granular Regional/Global Biome Modification Engine with Folia Asynchronous Chunk Palette Writing and Progression Locks.
* **Core Architecture & Invariants:**
  1. **Dual Scope Application (Global vs Regional):**
     - **Global Scope:** Sets the entire island bounding volume (`minX..maxX, minZ..maxZ`) to the designated biome key (`/is biome <id>`).
     - **Regional Scope:** Allows players to paint specific sub-zones (e.g. 16 to 48-block radius) with distinct micro-biomes (e.g., creating a dedicated Swamp corner for slime spawns alongside a Snowy mountain corner for powder snow).
  2. **Folia-Native Asynchronous Chunk Palette Pipeline:**
     - Modifying biomes does not trigger world saves or synchronous chunk freezes.
     - The modification is scheduled through Folia's `RegionScheduler`, mutating the 3D Biome Palette within the chunk section directly.
     - Immediately dispatches a lightweight client chunk packet to nearby tracked players via Paper's client-bound network pipeline to update grass, water, sky tint, and foliage instantly.
  3. **Progression-Locked Biome Catalog (`biomes.conf`):**
     - Each biome entry defines:
       - `id` (Namespaced biome identifier, e.g. `minecraft:plains`, `minecraft:warped_forest`, `minecraft:deep_dark`).
       - `display-name` & `icon_material`.
       - `cost` (Vault currency or Island Crystals).
       - `requirements` (Island Level, specific mission completion, or VIP permission node).
  4. **Generator & Spawner Synergy:**
     - The cobblestone generator engine (`IslandGeneratorService`) dynamically queries the local block biome at the lava/water contact point to weight ore tables accordingly.

---

### 2.12 Island Missions, Quests & Progression Engine (`IslandMissionService`, `MissionProgress`)
* **Decision:** Event-Driven Dynamic Mission Tree with Automatic Background Progress Tracking, Hybrid Item Submissions, and Rich Progression Unlocks.
* **Core Architecture & Invariants:**
  1. **Configurable Mission Trees (`missions.conf`):**
     - Structured into progressive thematic branches: `FARMING`, `MINING`, `SLAYER`, `BUILDER`, `ECONOMY`, `ADVENTURE`.
     - Each mission defines:
       - `id` (String identifier)
       - `display-name` & MiniMessage description
       - `trigger_type` (e.g. `BLOCK_BREAK`, `BLOCK_PLACE`, `CROP_HARVEST`, `MOB_KILL`, `CUSTOM_MOB_KILL`, `FISHING`, `LEVEL_REACH`, `BANK_BALANCE`, `ITEM_SUBMIT`)
       - `target_filter` (Material names, MythicMobs ids, custom item keys from `uxmlib-integration`)
       - `required_amount` (Target count)
       - `rewards` (Island crystals, island exp, level bonus, Vault currency, unlockable upgrade perks, or custom items)
  2. **Zero-Friction Automatic Event Interception:**
     - Players do not need to manually collect or deposit items into a GUI for action-based missions; events are captured asynchronously during normal gameplay.
     - Progress is aggregated in-memory per island profile and flushed to the database via batched writes.
  3. **Toast Notifications & Sound Feedback:**
     - Completing a mission triggers a native MiniMessage Toast advancement popup (`uxmlib-integration` advancement packets) accompanied by configurable audio feedback.
  4. **Hybrid Manual Item Submissions:**
     - For specialized end-game or economy-sink challenges, supports an interactive `/is challenges` interface where physical goods are submitted/consumed from inventory directly.

---

### 2.13 Island Boundaries, Dynamic Expansion & Boundary Protection Shield (`IslandBoundaryService`, `WorldBorderPacketAdapter`)
* **Decision:** Per-Player Virtual WorldBorder Packets with Particle Perimeter and Boundary Physics Shielding.
* **Core Architecture & Invariants:**
  1. **Clientbound Virtual WorldBorder:**
     - Utilizes Paper/Minecraft's per-player virtual world border pipeline (`ClientboundInitializeBorderPacket` / `WorldBorder`).
     - When a player steps into an island territory, their client border smoothly snaps to that island's center and exact bounding radius.
     - On purchasing an `island_size` upgrade, the client border animates outward dynamically with zero world-ticking penalty.
  2. **Boundary Physics & Spillover Shield:**
     - Intercepts fluid flow (`BlockFromToEvent`), piston movements (`BlockPistonExtendEvent`), dispensers, and projectile trajectories.
     - Any block, entity, or liquid attempting to traverse across the island's bounding perimeter into the void gap is cancelled immediately, preventing griefing, void entity spam, or cross-island bridging.
  3. **Particle Perimeter Projection (`/is border`):**
     - Players can trigger temporary colored dust particle walls outlining their active borders for precise building alignment.
  4. **Soft-Dependency Claim Bridge (`uxmlib-integration`):**
     - Emits boundary registration into `ClaimProvider` interfaces, ensuring seamless compatibility if server owners operate alongside WorldGuard or Lands.

---

### 2.14 Island Deletion, Safe Resetting & Grid Slot Recycling (`IslandRecycleService`, `SpiralSlotPool`)
* **Decision:** Asynchronous Chunk Voiding on Folia, Coordinate Recycling Queue, and Multi-Step Cryptographic Verification.
* **Core Architecture & Invariants:**
  1. **Folia-Native Asynchronous Voiding:**
     - Deleting an island does not perform synchronous block-by-block destruction.
     - Scheduled chunk tasks execute across the owning region context(s) to purge block entities, kill lingering non-player entities, and regenerate chunks directly to a pristine void state (`Chunk#regenerateChunk()` or native void section zeroing).
  2. **Spiral Grid Coordinate Recycling Pool (`SpiralSlotPool`):**
     - Prevents unbounded Anvil file inflation over time.
     - When an island is deleted, its vacated `(x, z)` grid coordinates are returned to a persistent `reusable_slots` pool.
     - New island creation queries `SpiralSlotPool` before expanding outward into new spiral rings, conserving world storage footprint.
  3. **Multi-Factor Accidental Deletion Protection:**
     - Standard reset requests issue a temporary cryptographic 4-digit code challenge (`/is reset confirm <code>`) expiring within 60 seconds, accompanied by a double-confirmation `uxmlib-menu` and Bedrock modal.
  4. **Pre-Deletion Backup Snapshots:**
     - An automatic NBT snapshot is saved locally to `backups/islands/<island_id>_<timestamp>.schem` before chunk zeroing, providing administrative disaster-recovery capabilities.

---

### 2.15 Distributed Multi-Server Architecture, Redis Bus & Velocity Proxy Bridge (`IslandNetworkRouter`, `VelocityBridge`)
* **Decision:** Island Affinity Sharding with Authoritative Leases & Epoch Fencing, Dual-Tier Redis Bus (Pub/Sub & Streams), Idempotent Effectively-Once Consumer Processing, Fail-Closed Degradation Policy, and Velocity Native Seamless Routing.
* **Core Architecture & Invariants:**
  1. **Island Server Affinity, Canonical Epoch & Authoritative Takeover Sequence (`IslandNetworkRouter`):**
     - In a distributed cluster, each active island is owned by exactly ONE authoritative sub-server node at any given moment.
     - **Canonical Authority Storage:** The canonical authority state and monotonic `authority_epoch BIGINT` reside in SQL table `island_authorities` (`island_id`, `authoritative_node`, `authority_epoch`, `lease_expires_at`, `last_heartbeat_at`, `updated_at`).
     - **Canonical Clock:** Database clock (`CURRENT_TIMESTAMP`) is the sole reference clock for all lease expiration comparisons.
     - **Redis Lease Cache:** Redis caches the active routing directory (`uxmskyblock:route:island:<id> -> {"node": "skyblock-02", "epoch": 482}`) with a 15-second TTL, renewed alongside database heartbeats. Redis is strictly a routing cache and transport bus; SQL is the sole authoritative state.
     - **Authority Lifecycle Operations (ACQUIRE, TAKEOVER, RENEW):**
       - **ACQUIRE (Unallocated / Fresh Island):**
         ```sql
         -- DB_NOW_PLUS(:seconds) is the canonical dialect-neutral clock primitive
         INSERT INTO island_authorities (island_id, authoritative_node, authority_epoch, lease_expires_at, last_heartbeat_at, updated_at)
         VALUES (:islandId, :nodeId, 1, DB_NOW_PLUS(15), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
         ```
       - **TAKEOVER (Lease Expired on Prior Authoritative Node):**
         ```sql
         UPDATE island_authorities
         SET authoritative_node = :newNodeId,
             authority_epoch = authority_epoch + 1,
             lease_expires_at = DB_NOW_PLUS(15),
             last_heartbeat_at = CURRENT_TIMESTAMP,
             updated_at = CURRENT_TIMESTAMP
         WHERE island_id = :islandId
           AND authority_epoch = :expectedAuthorityEpoch
           AND lease_expires_at < CURRENT_TIMESTAMP;
         ```
       - **RENEW (Periodic Heartbeat by Active Owner Every 5 Seconds):**
         ```sql
         UPDATE island_authorities
         SET lease_expires_at = DB_NOW_PLUS(15),
             last_heartbeat_at = CURRENT_TIMESTAMP,
             updated_at = CURRENT_TIMESTAMP
         WHERE island_id = :islandId
           AND authoritative_node = :currentNodeId
           AND authority_epoch = :currentAuthorityEpoch
           AND lease_expires_at >= CURRENT_TIMESTAMP;
         ```
       - **Epoch Advance Invariant:** *Authority epoch advances only when authority ownership is newly acquired or transferred. Lease renewal by the current valid owner never advances the fencing epoch.*
       - When Node B takes over an expired lease, it increments epoch $481 \to 482$. When Node A resumes from a GC pause, any write attempted with epoch $481$ affects 0 rows and is rejected.
     - Global routing lookup latency target is $p99 < 0.5\text{ ms}$.
  2. **Dual-Tier Cross-Server Event Synchronization & Idempotent Consumer Processing (`uxmlib-redis`):**
     - **Ephemeral Data via Redis Pub/Sub:**
       - Fire-and-forget channel communications: Island Chat (`/is chat`), online presence heartbeats, staff spy mode broadcasts, and transient L1 cache invalidation hints.
       - Dropped packets during network blips are non-critical and acceptable for ephemeral topics.
     - **Durable State via Transactional Outbox + Redis Streams (At-Least-Once Delivery):**
       - All state-altering events (bank transactions, role promotions/kicks, upgrades, island deletions) are committed to the SQL `outbox_events` table and polled into Redis Streams (`uxmskyblock:stream:domain_events`).
       - Because Redis Streams operates with at-least-once delivery semantics, re-deliveries and network retries produce duplicate stream entries.
     - **Idempotent / Effectively-Once Consumer Processing Contract:**
       - **Local SQL Projections:**
         Executed within a single local SQL transaction:
         ```sql
         START TRANSACTION;
         INSERT INTO consumer_inbox (consumer_name, event_id, processed_at) VALUES (:consumerName, :eventId, CURRENT_TIMESTAMP);
         -- Apply local projection / read-model mutation
         COMMIT;
         -- XACK sent to Redis Streams strictly AFTER successful SQL COMMIT!
         ```
         If `INSERT consumer_inbox` triggers a primary key violation, the consumer recognizes it as duplicate, skips handler execution, and sends `XACK` immediately.
       - **External Side Effects (Discord Webhooks, HTTP Endpoints, External Economy):**
         External side effects cannot participate in a local SQL transaction. No "exactly-once" delivery is promised for external systems. Instead, handlers enforce:
         - Downstream deduplication via idempotency keys (passing `event_id` in webhook payloads).
         - Exponential retry backoff with jitter.
         - Dead Letter Streams (`uxmskyblock:stream:dead_letter`) for permanently failing deliveries after 5 attempts.
         - Operator alerts and compensation logging for partial external failures.
     - **Consumer Lifecycle & Recovery:**
       - Messages are read via consumer groups (`XREADGROUP GROUP uxmskyblock-group <consumer_id> BLOCK 2000 COUNT 50 ...`).
       - Stale/crashed consumers are recovered via periodic `XAUTOCLAIM` scanning for messages pending $> 30\text{ seconds}$. Messages exceeding 5 retry attempts route to the Dead Letter Stream.
  3. **Velocity Seamless Proxy Routing (`uxmSkyblock-velocity`):**
     - When a player executes `/is visit <target>` or selects an island from the global `/is explore` directory on a different sub-server:
       1. The backend server notifies Velocity via modern plugin messaging channel or Redis pub/sub.
       2. Velocity routes the player connection to the designated target server seamlessly.
       3. Upon player connection handshake on the destination server, the player is automatically teleported to the target island warp coordinates.
  4. **Degradation & Failure Policy on Redis Disconnection:**
     - If the connection to Redis is severed or becomes unresponsive:
       - **Local Authoritative Mutations (Safe Operation):** Sub-servers holding valid active leases in SQL `island_authorities` CONTINUE processing local player transactions and island modifications normally, because canonical authority and CAS validation reside entirely within SQL using database clock comparisons.
       - **Chat & Social:** Degrades gracefully to local sub-server scope only; cross-server chat is suspended.
       - **Leaderboards:** Serves stale cached values from the local L1 Caffeine cache.
       - **Cross-Server Teleportation (`/is visit`):** Fails closed with a localized MiniMessage alert (`error.network.cluster_unavailable`). Players cannot jump between nodes while cluster routing directory is unavailable.
       - **Outbox Dispatcher:** Outbox events buffer safely in the SQL `outbox_events` table and resume streaming once Redis reconnects.

---

### 2.16 User Interface Architecture, uxmlib-menu Specs & Geyser Bedrock Engine (`IslandMenuService`)
* **Decision:** `uxm-essentials` Standard Spec-Driven Menus (`uxmlib-menu`) with Seamless Native Bedrock Form Bridge (`uxmlib-bedrock`).
* **Core Architecture & Invariants:**
  1. **Spec-Driven GUI Architecture (`uxmlib-menu`):**
     - Every menu layout is decoupled from Java code and declared in disk-first HOCON spec files (e.g. `modules/skyblock/gui/island-main.conf`, `island-upgrades.conf`, `island-permissions.conf`, `island-bank.conf`).
     - Uses `MenuSpecs`, `MenuBindings`, and `Menus` following the exact architectural conventions of `uxm-essentials`.
     - Zero hardcoded colors, items, slots, or titles in Java.
  2. **Native Bedrock Form Adaptation (`uxmlib-bedrock`):**
     - Intercepts menu requests for Bedrock players authenticated via Floodgate.
     - Automatically renders dedicated native forms:
       - **Action Forms (SimpleForm):** Button matrices for `/is warp`, `/is explore`, `/is missions`.
       - **Modal Forms (ModalForm):** Confirmation dialogs for `/is reset`, `/is kick`, `/is disband`.
       - **Custom Configuration Forms (CustomForm):** Sliders, switches, and dropdowns for `/is settings` and `/is permissions`.
  3. **Shared Presentation Logic:**
     - A unified domain presenter handles actions, permission checks, and transactional mutations identically across both Java inventory click events and Bedrock form submissions, guaranteeing zero divergent behavior.

---

### 2.17 Persistence Architecture, ACID Transactions & Memory Lifecycle (`IslandRepository`, `TxSql`, `Striped<Lock>`)
* **Decision:** SQL Sole Source of Truth (`TxSql`), Optimistic CAS Versioning with Epoch Fencing, Keyed Local Lock Striping (`Striped<Lock>`), Transactional Operation Boundaries, and Unload-on-Idle Memory Eviction Policy.
* **Core Architecture & Invariants:**
  1. **SQL as Sole Canonical Source of Truth (`TxSql`):**
     - SQL is the single definitive source of truth across the entire system. Redis functions strictly as an ephemeral L2 cache, cluster directory, and streaming bus—never the authoritative data store.
     - Supports native zero-dependency SQLite for single-server standalone deployments.
     - Clustered multi-server deployments utilize enterprise connection pooling (HikariCP) with MySQL 8+, MariaDB 10+, or PostgreSQL 15+.
     - Automated versioned migrations are managed via `DatabaseMigrator` (`V1__initial_schema.sql`, `V2__add_audit_log.sql`, etc.) with strict transaction wrapping where dialect allows.
  2. **Unload-on-Idle Lifecycle Policy:**
     - Prevents unbounded heap memory bloat across 250,000+ persisted islands.
     - When all members and visitors depart from an island, an idle countdown timer (default `10 minutes`, configurable in `performance.conf`) begins.
     - Upon timer expiration without player activity, the island's dirty aggregate state is flushed via `TxSql` with optimistic version validation, and its domain instance is evicted from the L1 Caffeine cache.
     - Any inbound warp, member join, or cross-server event reactivates the island asynchronously into the L1 cache in $p99 < 1.5\text{ ms}$ target.
  3. **Concurrency Control & Multi-Server Fencing:**
     - **JVM-Local Striped Locks (`Striped<Lock>`):** Used strictly as a local thread contention optimization inside a single JVM to serialize concurrent tasks across local Folia region contexts before touching the database.
     - **Distributed Optimistic Concurrency Control with Canonical Epoch Fencing:** State flushes and critical mutations enforce both version validation and active authority lease verification against `island_authorities`:
       ```sql
       UPDATE islands AS i
       SET i.lifecycle = :lifecycle,
           i.economic_state = :economic_state,
           i.administrative_state = :administrative_state,
           i.level_score = :level,
           i.version = i.version + 1,
           i.updated_at = CURRENT_TIMESTAMP
       WHERE i.id = :id
         AND i.version = :expected_version
         AND EXISTS (
             SELECT 1
             FROM island_authorities AS a
             WHERE a.island_id = i.id
               AND a.authoritative_node = :node_id
               AND a.authority_epoch = :expected_authority_epoch
               AND a.lease_expires_at > CURRENT_TIMESTAMP
         );
       ```
       If another node acquired authority or the local version is stale, the update affects 0 rows. In accordance with the critical mutation invariant, the transaction is immediately rolled back without committing audit logs or outbox events.
  4. **Strict Transactional Operation Boundary & Idempotency (`processed_operations`):**
     - Reserving the idempotency key, executing the critical state mutation, recording audit history, and committing durable outbox events MUST occur within the exact same local SQL transaction:
       ```sql
       START TRANSACTION;
       -- 1. Reserve idempotency key (status = 'PENDING')
       INSERT INTO processed_operations (operation_id, idempotency_key, operation_type, resource_id, status)
       VALUES (:operation_id, :idempotency_key, :type, :resource_id, 'PENDING');
       -- 2. CAS Mutation with canonical authority verification (affected rows MUST == 1)
       UPDATE islands AS i ... WHERE i.id = :id AND i.version = :expected_version AND EXISTS (SELECT 1 FROM island_authorities ...);
       -- 3. Audit trail (inserted ONLY if affected rows == 1)
       INSERT INTO bank_transactions (...) VALUES (...);
       -- 4. Durable outbox event (inserted ONLY if affected rows == 1)
       INSERT INTO outbox_events (...) VALUES (...);
       -- 5. Mark operation APPLIED
       UPDATE processed_operations SET status = 'APPLIED', result_code = 'SUCCESS', completed_at = CURRENT_TIMESTAMP WHERE operation_id = :operation_id;
       COMMIT;
       ```
     - Re-delivered requests look up existing idempotency records; if already `APPLIED` or `REJECTED`, the cached outcome is returned without re-running mutations or emitting duplicate side-effects.

---

### 2.18 Developer API, Embedded REST API & Command Subsystem (`:api`, `:rest-adapter`, `uxmlib-command`)
* **Decision:** Enterprise-Grade Split Developer API, Service Registry Capability Model, Sealed Result Contracts, Embedded Javalin REST Server, Brigadier Command Tree, and MiniMessage i18n (`messages_en` / `messages_tr`).
* **Core Architecture & Invariants:**
  1. **Developer API Subsystem (`:api` module):**
     - **`IslandQueryApi`:** Thread-safe, non-blocking read operations returning read-only domain records (`IslandSnapshot`, `IslandMemberView`).
     - **`IslandActionApi`:** Mutation operations returning `CompletableFuture<IslandResult<T>>` with sealed status codes:
       ```java
       public sealed interface IslandResult<T> permits
           IslandResult.Success,
           IslandResult.Failure,
           IslandResult.FeatureUnavailable {

            record Success<T>(T value) implements IslandResult<T> {}
            record Failure<T>(ErrorCode code, DiagnosticContext context) implements IslandResult<T> {
                public Failure(ErrorCode code) {
                    this(code, DiagnosticContext.empty());
                }
            }
            record FeatureUnavailable<T>(String featureKey) implements IslandResult<T> {}

            default boolean isSuccessful() {
                return this instanceof Success;
            }
        }
        ```
     - **Presentation vs Domain Separation:** `Failure` carries structured, strongly typed diagnostic context (`ErrorCode`, `DiagnosticContext context`), strictly preventing raw maps, localized strings, or Minecraft formatting from leaking into the core domain layer. Presentation adapters resolve messages via `messages_<lang>.conf`.
     - **Platform-Neutral Domain Events (`:api`):** Comprehensive platform-neutral domain event records (`IslandCreatedEvent`, `IslandDeletedEvent`, `IslandLevelChangedEvent`, `IslandBankTransactionEvent`, `IslandMemberRoleChangedEvent`). **Pure `:api` contains ZERO Bukkit event classes**; Bukkit `Event` wrappers are dispatched strictly within the platform adapter layer (`:bukkit-adapter` or `:bukkit-api`).
     - **Service Registry & Optional Module Capabilities Contract (Zero Runtime Exceptions):**
       - Subsystems register capabilities with `SkyblockServices` (`services.find(IslandBankService.class) -> Optional<IslandBankService>`).
       - If a module is disabled in `modules.conf`, `find()` returns `Optional.empty()` and direct facade mutation calls return `new IslandResult.FeatureUnavailable<>("bank")`.
       - **Invariant:** *Expected feature absence MUST NEVER throw runtime exceptions (`FeatureUnavailableException` is banned).* All callers handle absence cleanly via `Optional` checks or compiler-checked sealed `switch` pattern matching.
  2. **Embedded Javalin REST API (`:rest-adapter` module):**
     - Runs a lightweight, isolated embedded Javalin HTTP daemon.
     - Protected via Bearer token authentication configured in `rest.conf`.
     - Standard JSON endpoints:
       - `GET /api/v1/health`
       - `GET /api/v1/islands/:id`
       - `GET /api/v1/islands/:id/members`
       - `GET /api/v1/leaderboards/:metric`
       - `POST /api/v1/islands/:id/bank/deposit` (Web store Tebex / CraftingStore delivery integration)
  3. **Brigadier Command Tree (`uxmlib-command`):**
     - Full modern command tree `/island` (aliases: `/is`, `/sb`) with rich context-aware tab completion, permission nodes mapped from `CatalogPermissions`, and zero legacy chat formatting.
  4. **Internationalization & Localization (i18n):**
     - MiniMessage format strictly enforced.
     - Ships with 100% complete `messages_en.conf` (English default) and `messages_tr.conf` (Turkish).

---

### 2.19 Minion Ecosystem Boundary & Integration Policy
* **Decision:** No Internal Minion Reinvention (Clean SRP Boundary); Native Claim & Permission Bridges for UXPLIMA Minion Ecosystem.
* **Core Architecture & Invariants:**
  1. **Strict Single Responsibility:**
     - In adherence to project rule "Do not reinvent the wheel" and avoiding monolithic scope creep, the Skyblock core plugin does **not** embed internal robot/minion ticker logic.
  2. **Interoperability & Claim Provider Hook:**
     - Skyblock implements `ClaimProvider` / `IslandClaimBridge` allowing the external UXPLIMA Minion plugin (or third-party minion engines) to query island boundaries, check `IslandPermission.MINION_PLACE` and `IslandPermission.MINION_INTERACT`, and associate minions with `IslandId`.
  3. **Extensible Upgrade Capacity Node:**
     - An `island_minion_slots` node remains available in `upgrades.conf` so server administrators can tie external minion placement caps to the island's upgrade level via standard PlaceholderAPI placeholders (`%uxmskyblock_island_minion_slots%`).

---

### 2.20 Shared Island Vault & Paged Inventory Subsystem (`IslandVaultService`, `PessimisticVaultLock`)
* **Decision:** Paged Virtual Island Vault with Hardware-Serialized Pessimistic Page Locking, Distributed Fencing Tokens, Escrow Anti-Duplication Protocol, and Granular Role Permissions.
* **Core Architecture & Invariants:**
  1. **Dynamic Paged Vault Capacity:**
     - Virtual storage accessible via `/is chest [page]` or `/is vault [page]`.
     - Base page starts at 1, expanding up to a configurable maximum (e.g. 10 pages, 54 slots each) unlocked via `vault_pages` in `upgrades.conf`.
  2. **Pessimistic Page Locking & Distinct Versioning Roles:**
     - When an island member opens a specific vault page (e.g., Page 2), an exclusive lease is assigned to `(IslandId, PageNumber)`.
     - **Role Separation:**
       - `lease_epoch BIGINT`: Incremented monotonically whenever an edit session is acquired. Used strictly for fencing out stale edit sessions.
       - `page_version BIGINT`: Incremented monotonically whenever page contents are successfully committed.
       - `active_session_id VARCHAR(36)`: Tracks the active editor's session UUID.
     - **Simultaneous Open Protection:** Other members attempting to open an occupied page receive an instant notification (`vault.page_busy`, naming the active editing member).
  3. **Atomic CAS Commit & Stale Session Rejection:**
     - When the editing player closes the GUI, contents flush to SQL with full epoch, version, and session validation:
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
  4. **Durable Escrow Journaling & Exact Write-Ahead Protocol (`vault_edit_sessions`):**
       - **Strict Prohibition of Blind Refunds & Whole-Inventory RAM Rollbacks:**
         - Blind refunds without verification are strictly prohibited.
         - Whole-inventory RAM snapshots are rejected because: (1) RAM state vanishes on process termination; (2) rolling back entire inventories wipes legitimate external items (rewards, external trades, pickups).
       - **Exact Per-Transfer Write-Ahead Protocol & Dual-Slot Version Contract:**
         Every item transfer between the island vault GUI and player inventory follows a five-step lifecycle:
         1. `TRANSFER_INTENT`: Prior to modifying visible slot contents, an intent record is staged into `vault_edit_sessions.escrow_journal`:
            - Schema: `transfer_id UUID`, `session_id UUID`, `source ('VAULT'|'PLAYER')`, `destination ('PLAYER'|'VAULT')`, `source_slot INT`, `destination_slot INT`, `source_before_fingerprint VARCHAR(64)`, `source_after_fingerprint VARCHAR(64)`, `destination_before_fingerprint VARCHAR(64)`, `destination_after_fingerprint VARCHAR(64)`, `source_expected_version BIGINT`, `destination_expected_version BIGINT`, `source_container_version BIGINT`, `destination_container_version BIGINT`, `serialized_item_nbt BLOB`, `quantity INT`, `state ('INTENT'|'APPLYING'|'APPLIED'|'COMMITTED'|'ABORTED'|'RECOVERY_REQUIRED')`, `created_at TIMESTAMP`.
         2. **Slot Content Mutation:** The player and vault GUI slot views update on the Folia entity thread, incrementing local slot versions and container generations.
         3. `TRANSFER_APPLIED`: The journal entry state updates to `APPLIED`.
         4. `SESSION_COMMIT`: When the GUI closes, the final page NBT commits atomically to `island_vault_pages`, the player inventory ledger updates, the session state transitions to `COMMITTED`, and escrow journal entries are finalized.
         5. `ABORTED` / `RECOVERY_REQUIRED`: Non-standard terminal states for failed or contested transfers.
       - **Canonical Item Ownership Invariant:**
         - *Invariant:* **No player-visible vault transfer may occur before its durable transfer intent exists.**
       - **Comprehensive Dual-Slot Recovery Decision Matrix:**
         Upon server reboot or player reconnect with an uncommitted session (`state != 'COMMITTED'` and `expires_at < CURRENT_TIMESTAMP`), recovery inspects the actual physical slot states of **both source and destination**:

         | Source Slot Observed | Destination Slot Observed | Recovery Action | Resulting State | Inventory & Accounting Outcome |
         | :--- | :--- | :--- | :--- | :--- |
         | `BEFORE` (Item in source) | `BEFORE` (Destination empty/pre-item) | `ABORT` | `ABORTED` | Crash in `INTENT` pre-mutation. Zero items moved or refunded; duplicate generation prevented under documented recovery semantics. |
         | `AFTER` (Source cleared) | `AFTER` (Item in destination) | `CONDITIONAL_ROLLBACK` | `ABORTED` | In-memory transfer occurred but GUI never committed. Reverts item to source slot, increments slot/container versions. |
         | `BEFORE` (Item in source) | `AFTER` (Item in destination) | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | **Duplication Hazard!** Both slots contain item. Refuse repair; quarantine slot and vault page; emit critical staff alert. |
         | `AFTER` (Source cleared) | `BEFORE` (Destination empty) | `CONDITIONAL_ROLLBACK` | `ABORTED` | **Item Loss Hazard!** Item cleared from source but missing at destination. Restores item from journal NBT to source; increments version. |
         | `UNKNOWN` (Drift/Tampering) | *ANY* | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | Source slot has unrecognized fingerprint (external mutation). Quarantine page; manual operator inspection required. |
         | *ANY* | `UNKNOWN` (Drift/Tampering) | `RECOVERY_REQUIRED` | `RECOVERY_REQUIRED` | Destination slot has unrecognized fingerprint. Quarantine page; manual operator inspection required. |

       - **Vault Commit Atomic SQL Transaction Boundary:**
         Closing the vault GUI persists all state in a single atomic SQL transaction:
         ```sql
         START TRANSACTION;
         SELECT authoritative_node, authority_epoch, lease_expires_at FROM island_authorities WHERE island_id = :islandId FOR UPDATE;
         UPDATE island_vault_pages SET contents_nbt = :contents, page_version = page_version + 1, active_session_id = NULL, last_modified_by = :playerId, updated_at = CURRENT_TIMESTAMP WHERE island_id = :islandId AND page = :pageNumber AND page_version = :expectedPageVersion AND lease_epoch = :expectedLeaseEpoch AND active_session_id = :sessionId;
         UPDATE profile_inventories SET inventory_nbt = :playerInventoryNbt, profile_inventory_version = profile_inventory_version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id = :playerProfileId;
         UPDATE vault_edit_sessions SET state = 'COMMITTED', closed_at = CURRENT_TIMESTAMP WHERE session_id = :sessionId AND state = 'ACTIVE';
         COMMIT;
         ```
       - **Session-Owned Slots & External Mutation Boundary Guarantee:**
         - Active vault sessions register ownership over: (1) vault page slots ($0..53$), and (2) player inventory slots actively involved in in-flight transfers.
         - **UXM-Owned vs Third-Party Boundary:**
           - UXM-owned plugins MUST route player inventory mutations through `CentralInventoryMutationService`. Conflicting writes to session-owned slots are queued until GUI close or redirected to free slots.
           - Direct third-party Bukkit API mutations (`player.getInventory().setItem(...)`) cannot be trapped synchronously and are **explicitly outside strong vault consistency guarantees**. If third-party plugins overwrite session slots, Dual-Slot Recovery detects fingerprint mismatch and flags `RECOVERY_REQUIRED`.
  5. **Granular Action Permissions (`IslandPermission`):**
     - `VAULT_VIEW`: Read-only inspection of stored contents.
     - `VAULT_DEPOSIT`: Permission to place items into the vault.
     - `VAULT_WITHDRAW`: Permission to retrieve items from the vault.
  6. **Persistent Audit History:**
     - Maintains a rolling audit log of the last 50 vault interactions (who inserted/removed which item stack, at what timestamp), viewable by island officers.
  7. **Player-to-Player Trade Durability Contract (Live Folia Contexts vs SQL Atomicity):**
     - *Architecture Notice:* A single SQL transaction only makes durable SQL state atomic. Two live `Player` entities can execute in different Folia region contexts or on different backend cluster nodes. Live Bukkit inventory mutations CANNOT be made atomic simply by wrapping SQL queries in a transaction.
     - *Conceptual Two-Phase Journal Orchestration:*
       1. Acquire and fence both player session authorities in canonical Player UUID order.
       2. Persist durable `TRADE_INTENT` in `inventory_mutation_journals` recording both expected inventory versions.
       3. Dispatch to `EntityScheduler(Player A)` for conditional slot mutation.
       4. Dispatch to `EntityScheduler(Player B)` for conditional slot mutation.
       5. Record each participant `APPLIED` in the mutation journal.
       6. Persist durable final SQL inventory versions and snapshots.
       7. Mark `TRADE_COMMITTED`.
     - *Status:* **Trade capability product scope = V1 (previously PO-approved for Classic profile); Trade correctness architecture = TECHNICALLY APPROVED (Two-Phase / Four-Phase Compound Journal Protocol); Implementation = NOT AUTHORIZED until Phase 1.**

---

### 2.21 Stacker Ecosystem Boundary & Integration Policy (`IslandPermission.SPAWNER_MODIFY`, Stacker Bridge)
* **Decision:** Dedicated Standalone Enterprise Plugin (`uxm-stacker`) to Enable Cross-Gamemode Reusability (Skyblock, Survival, Factions); Native Claim & Limit Hooks in Skyblock Core.
* **Core Architecture & Invariants:**
  1. **Clean Micro-Plugin Decoupling:**
     - Rather than coupling monolithic mob/block/spawner stacking code exclusively to Skyblock, the stacking engine is designed as an independent enterprise plugin (`uxm-stacker`).
     - Enables unified deployment across all network server types (Skyblock, Survival, Towny, Factions).
  2. **Skyblock Core Integration Surface:**
     - Skyblock exposes `IslandPermission.SPAWNER_PLACE`, `IslandPermission.SPAWNER_BREAK`, `IslandPermission.SPAWNER_MODIFY`, and `IslandPermission.HOPPER_MODIFY` to guard stacker interactions within island bounds.
     - Spawner limits (`spawner_limit`) and vacuum hopper limits (`hopper_limit`) configured in `upgrades.conf` are queryable by `uxm-stacker` via standard API / PlaceholderAPI (`%uxmskyblock_island_spawner_limit%`, `%uxmskyblock_island_spawners_count%`).

---

### 2.22 Configurable Leader Inactivity & Succession Lifecycle (`IslandInactivityService`)
* **Decision:** Fully Configurable Dynamic Succession Engine with Customizable Demotion Ranks, Inactivity Thresholds, and Orphan Cleanup Rules.
* **Core Architecture & Invariants:**
  1. **Configurable Inactivity Engine (`inactivity.conf`):**
     - Completely zero-hardcoded; administrators customize all thresholds and actions:
       - `enabled`: Boolean toggle.
       - `owner-inactivity-duration`: Configurable duration string (e.g. `30d`, `60d`).
       - `succession-hierarchy`: Priority order of roles eligible to inherit ownership (e.g. `[CO_OWNER, ADMIN, MODERATOR, OLDEST_MEMBER]`).
       - `former-owner-action`: Dynamic disposition for the deposed owner (Options: `DEMOTE_TO_CO_OWNER`, `DEMOTE_TO_MEMBER`, `DEMOTE_TO_RECRUIT`, or `KICK_FROM_ISLAND`).
  2. **Total Team Abandonment & Pruning Policy:**
     - `all-members-inactivity-duration`: Configurable threshold (e.g. `60d` or `90d`).
     - Action when zero active members remain on the island:
       - `ARCHIVE`: Marks island state as dormant, unloads from cache, and locks warp points.
       - `DELETE_AND_RECYCLE`: Executes asynchronous Folia chunk voiding and returns the coordinate to `SpiralSlotPool` for new players.
  3. **No External Notification Dependencies:**
     - Self-contained in-game lifecycle; no external email or third-party messaging requirements. In-game broadcast and offline mail (`uxm-essentials` mail bridge) notify members upon next login.

---

### 2.23 Commerce & Chest Shop Ecosystem Boundary & Integration Policy (`IslandPermission.CHEST_SHOP_*`)
* **Decision:** No Internal Reinvention of Auction House or Chest Shops; Decoupled Architecture with Native Claim Bridges for UXPLIMA's Dedicated AH and Chest Shop Plugins.
* **Core Architecture & Invariants:**
  1. **Strict Single Responsibility:**
     - UXPLIMA maintains dedicated enterprise plugins for Auction House and Chest Shops. Skyblock core strictly refrains from embedding duplicate commercial transaction code.
  2. **Granular Commercial Permissions:**
     - Skyblock implements `IslandPermission.CHEST_SHOP_CREATE` (allowing members to place chest shop sign/block markers) and `IslandPermission.CHEST_SHOP_USE` (allowing visitors to purchase or sell items via island shops).
  3. **Claim Bridge & Chest Protection Hook:**
     - Exposes a dedicated hook ensuring that when UXPLIMA Chest Shop registers a shop on an island chest/barrel, the container is protected from visitor destruction or unauthorized theft while remaining accessible for trade transactions.

---

### 2.24 Island Communication & Private Chat Subsystem (`IslandChatService`, `IslandChatChannel`)
* **Decision:** Dual-Mode Island Chat (Persistent Toggle Mode & Single Dispatch Prefix) with Distributed Redis Synchronization and Administrative Spy Capabilities.
* **Core Architecture & Invariants:**
  1. **Dual Dispatch Modes:**
     - **Toggle Mode (`/is chat`):** Flips player's active chat channel session state so subsequent normal messages route exclusively to island members.
     - **Direct Dispatch (`/is c <message>` / `/is chat <message>`):** One-off communication routed to island members without altering the active chat channel.
  2. **MiniMessage Format Styling (`messages.conf`):**
     - Fully customizable format strings with contextual placeholder tokens:
       - `<dark_gray>[<aqua>Island Chat<dark_gray>] <gray>[<green><role><gray>] <yellow><player><white>: <message>`
  3. **Cross-Server Cluster Broadcast (`uxmlib-redis`):**
     - Island chat packets are serialized and published over Redis Pub/Sub (`uxmskyblock:chat:island:<id>`).
     - Members located on different backend servers receive the message simultaneously without tick desynchronization.
  4. **Staff Oversight & Spy Mode (`IslandChatSpy`):**
     - Permitted server moderators (`uxmskyblock.chat.spy`) can toggle `/is spy` to receive cross-island transmissions for moderation and rule enforcement.

---

### 2.25 FeatureModule Architecture, Explicit Descriptors & Lifecycle (`FeatureModule`, `modules.conf`)
* **Decision:** Explicit Module Descriptors, DAG Startup Resolution, Fail-Fast Dependency Enforcement, Explicit Provider Collision Policy, and Restart-Only Lifecycle.
* **Core Architecture & Invariants:**
  1. **Explicit Module Descriptor & Capability Contract:**
     - `FeatureModule` represents internal built-in modules compiled into the plugin. External ecosystem addons are developed as standard Paper plugins that interact with the core engine exclusively via `:api`.
     - Every internal module defines a declarative `ModuleDescriptor`:
       ```hocon
       module {
         id = "missions"
         version = "1.0.0"
         requires = ["core >= 1.0.0"]
         optional = ["island-bank >= 1.0.0"]
         provides = ["island-missions"]
         api-compatibility = ">=1.0.0 <2.0.0"
       }
       ```
  2. **Dependency Resolution & Deterministic Startup Ordering:**
     - **SemVer 2.0.0 Version Expression Grammar:**
       - All module and dependency versions strictly adhere to Semantic Versioning 2.0.0 (`MAJOR.MINOR.PATCH[-PRERELEASE]`).
       - Supported dependency range expressions in `requires` and `optional`:
         - Exact version: `"1.2.3"`
         - Caret range: `"^1.2.0"` (compatible updates $\ge 1.2.0 < 2.0.0$)
         - Tilde range: `"~1.2.0"` (patch-level updates $\ge 1.2.0 < 1.3.0$)
         - Comparison range: `">=1.2.0 <2.0.0"`
         - Wildcard range: `"1.2.*"` or `"*"`
     - **DAG Topological Sort:** Subsystem startup order is deterministically derived by constructing a Directed Acyclic Graph (DAG) from `requires` and `optional` dependencies.
     - **Circular Dependency Detection:** During dependency graph compilation, cycle detection algorithms (Tarjan's/Kahn's) validate the graph. Any circular dependency aborts startup immediately with a fatal diagnostic: `CircularDependencyException: Cycle detected: [missions -> bank -> missions]`.
     - **Missing Required Dependency:** Fails fast immediately on startup. The plugin halts with a descriptive error (`MissingDependencyException: Module 'missions' requires 'core >= 1.0.0' which is missing`).
     - **Missing Optional Dependency:** The module starts successfully in degraded mode. Any downstream call to `services.find(OptionalService.class)` yields `Optional.empty()`.
  3. **Module Initialization Failure Policy (Three-Tier Fail-Fast Scope):**
      - If a module throws an unhandled exception during `enable()`:
        - **Tier 1 — Core Required Modules (e.g., `core`, `persistence`, `island-lifecycle`):** Plugin startup aborts immediately (`FailsafePluginShutdownException`), safely halting boot to prevent corrupt state or headless operations.
        - **Tier 2 — Required Capability Providers:** If a module is registered as the active `selected-provider` for a required capability (e.g., `island-bank`, grid provider) and fails during initialization, plugin startup aborts immediately. Running without an explicit required provider is prohibited.
        - **Tier 3 — Optional Internal Built-in Modules:** Initialization failure aborts ONLY that individual internal module. The module is marked `FAILED`, any downstream internal modules depending on it are degraded or cleanly disabled, a high-priority operator alert is emitted, and the core server continues running.
        - **External Paper Plugins Lifecycle:** External addons are standard Paper plugins managed entirely by Paper's plugin lifecycle. Skyblock does NOT possess a private addon loader or manage external plugin lifecycles. External plugins register public capabilities via `SkyblockServices`. Skyblock may accept or reject provider registration based on compatibility, or degrade capabilities if an external provider unregisters, but never marks an external Paper plugin `FeatureModule` as FAILED.
  4. **Capability Provider Collision Policy (Zero Ambiguity):**
     - When multiple modules register the same capability (e.g., built-in bank vs an external Paper plugin economy bank), silent or randomized provider selection is **strictly prohibited**.
     - The operator MUST declare the active provider explicitly in `modules.conf`:
       ```hocon
       capabilities {
         "island-bank" {
           selected-provider = "builtin-bank" # or "external-provider-id"
         }
       }
       ```
     - **Collision Fail-Fast Invariant:** If more than one provider registers for a capability and `selected-provider` is not explicitly declared, plugin startup aborts with a fatal configuration error detailing the collision.
     - Built-in providers can only be replaced by external providers if the administrator explicitly sets `selected-provider` to the external identifier.
  5. **External Provider API Compatibility Verification:**
     - External Paper plugins declare `api-compatibility` metadata during service registration (e.g. `">=1.0.0 <2.0.0"`). During registration, the engine validates semantic version boundaries against the runtime core API baseline.
     - **Unsupported Range Rejection:** If an external plugin reports an unsatisfied version range or targets an incompatible major API, provider registration is immediately rejected (`ModuleDependencyResolutionException` specifying the failing provider, expected range, and runtime version).
  6. **Zero-Resource Inactive Footprint (`modules.conf`):**
     - When an administrator disables a module (e.g., `bank = false`):
       - Associated commands (`/is bank`) are un-registered from the Brigadier dispatcher.
       - Event listeners are never registered with Bukkit.
       - Database tables and in-memory caches are omitted, consuming zero runtime scheduling ticks and registering zero Bukkit event listeners (class metadata remains in JVM memory).
  7. **Restart-Only Lifecycle Invariant & Reload Boundaries:**
     - Enabling or disabling a subsystem in `modules.conf` strictly requires a server restart. Runtime dynamic hot-unloading / hot-reloading of complex architectural modules is explicitly rejected to eliminate classloader leaks, lingering Bukkit listeners, dangling SQL connection pools, and desynchronized thread scheduler states.
     - `disable()` is executed strictly during graceful server shutdown or clean plugin unloads.
     - `reload()` is strictly restricted to reload-safe configuration files (e.g. MiniMessage translation strings, GUI menu specs, pricing tables, and limits). Core service registrations and database tables are never torn down or re-bound during runtime `/is reload`.

---

### 2.26 Island Block Worth Valuation Engine (`IslandWorthService`, `levels.conf`)
* **Decision:** Static Declarative HOCON Valuation Dictionary with Exact O(1) Memory Indexing and Custom Spawner Mappings.
* **Core Architecture & Invariants:**
  1. **Explicit Declarative Dictionary (`levels.conf`):**
     - Block point values are declared explicitly without implicit heuristic guessing:
       ```hocon
       blocks {
         "minecraft:diamond_block" = 900
         "minecraft:emerald_block" = 1200
         "minecraft:iron_block" = 90
         "minecraft:gold_block" = 450
         "minecraft:netherite_block" = 15000
         "minecraft:beacon" = 5000
       }
       spawners {
         "minecraft:iron_golem" = 5000
         "minecraft:blaze" = 1000
         "minecraft:zombie" = 200
       }
       ```
     - Any block not explicitly defined in the catalog evaluates to `0` points, preventing unexpected score inflation from mass dirt/cobblestone placement.
  2. **Computational Complexity & Worth Invariants:**
     - **Live Block Event Histogram:** Amortized $O(1)$ updates via `fastutil` `Object2IntOpenHashMap`.
     - **Price Shift Delta Recalculation:** Amortized $O(1)$ economic delta accumulator on single material unit price updates.
     - **Full Worth Recomputation:** $O(M)$ bounded by tracked material vocabulary size.
     - **Full World Scan:** $O(C \times B)$ throttled across Folia regions with inter-chunk delays.

---

### 2.27 Cross-Dimension Travel & Nether/End World Subsystem (`IslandDimensionService`)
* **Decision:** Unified Custom Dimensions via Vanilla Dimension Types and Seamless Portal Interception.
* **Core Architecture & Invariants:**
  1. **Dimension Structure:**
     - Each island owns a coordinated triad of worlds or coordinate-segregated spaces: `island_overworld`, `island_nether`, `island_the_end`.
  2. **Vanilla Portal Linkage:**
     - Intercepts `PlayerPortalEvent`. Stepping into Nether or End portals redirects players to their island's corresponding dimension coordinates instead of the global server Nether/End.
  3. **Progression Locks:**
     - Nether unlocked via `island_nether` upgrade in `upgrades.conf`.
     - The End unlocked via `island_end` upgrade, providing end-game progression gating.

---

### 2.28 Island Alliances & Diplomatic Relations Subsystem (`IslandAllianceService`, `alliances.conf`)
* **Decision:** Bilateral Alliance Handshake with Shared Boundaries, Friendly-Fire Protection, and Alliance Chat.
* **Core Architecture & Invariants:**
  1. **Bilateral Mutual Agreement:**
     - Alliances require mutual consent (`/is ally invite <island>` -> `/is ally accept <island>`).
  2. **Alliance Privileges & Synergy:**
     - **Private Alliance Chat:** Members communicate across allied islands using `/is ac <message>` or `/is allychat`.
     - **Privileged Visit Access:** Allied players can visit an island even if the island is globally locked (`/is lock`) to general visitors.
     - **Friendly-Fire Shielding:** Prevents accidental PvP damage between allied members when island PvP is active.
  3. **Scalable Alliance Capacity:**
     - Configurable default alliance limit (e.g. 2 allies), upgradeable via `max_allies` in `upgrades.conf`.
  4. **Strict Module Toggling:**
     - Controlled via `modules.conf` (`alliances = true/false`); when disabled, commands and tables are suppressed.

---

### 2.29 Enterprise Snapshots, BackupSet Architecture, General Object Storage & Disaster Recovery (`IslandBackupService`, `ObjectStoragePort`, `DatabaseBackupPort`, `RootRelationalSnapshotPort`, `WorldDimensionSnapshotPort`)
* **Decision:** Asynchronous Zstd-Compressed Snapshots with Two-Phase Bounded Full Capture, Logical `BackupSet` Model, Neutral `ObjectStoragePort` Integration (AWS S3 and Cloudflare R2 V1 Required Verified-Compatibility Targets; Current Status: Not Yet Implemented / Not Yet Compatibility-Verified), Dialect-Correct `DatabaseBackupPort`, Separated `RootRelationalSnapshotPort` and `WorldDimensionSnapshotPort`, and Fail-Closed Disaster Recovery.
* **Core Architecture & Invariants:**
  1. **Reusable General Object Storage Architecture (`ObjectStoragePort`):**
     - Rather than building a narrow backup-specific S3/R2 client, infrastructure capabilities are modeled behind an internal, platform-neutral port: `ObjectStoragePort` (located in `:core`).
     - **Dependency Direction (Hexagonal House Pattern):**
       $$\text{Application Use Case (e.g. BackupApplicationService)} \longrightarrow \text{ObjectStoragePort} \longleftarrow \text{Storage Adapter} \longrightarrow \text{Storage Backend}$$
     - Pure domain aggregates/entities NEVER directly call outbound infrastructure ports. Core remains strictly free of AWS SDK types, HTTP client classes, provider credentials, and R2-specific classes.
     - **Conceptual Identities:** `ObjectStorageProviderId`, `BucketRef`, `ObjectKey`, `ObjectMetadata`, `ObjectVersionRef`, `ObjectChecksum`, `ObjectRange`.
     - **Operation Families:** `putObject`, `getObject`, `uploadStream`, `downloadStream`, `headObject`, `exists`, `deleteObject`, `listObjects` (with pagination tokens), `readRange`, `initiateMultipartUpload`, `uploadPart`, `completeMultipartUpload`, `abortMultipartUpload`, `copyObject`, and conditional writes.
     - **Typed Capability Discovery (`ObjectStorageCapabilities`):** Providers declare supported capabilities (`MULTIPART_UPLOAD`, `RANGE_READ`, `SERVER_SIDE_COPY`, `CONDITIONAL_WRITE`, `PRESIGNED_URL`, `NATIVE_OBJECT_VERSIONING`, `OBJECT_LOCK`, `PROVIDER_LIFECYCLE_RULES`, `CHECKSUM_ALGORITHMS`). Core use cases declare required capabilities; startup validation fails closed if a required capability is missing.
     - **Target Adapters & Real Provider Verification Boundary:**
       - *V1 Required Verified-Compatibility Targets:* Amazon AWS S3 and Cloudflare R2.
       - *Current Implementation Status:* NOT YET IMPLEMENTED / NOT YET COMPATIBILITY-VERIFIED (zero production Java or test implementation yet written).
       - *Canonical Verification Distinction:*
         - Generic S3 Adapter Tests: Local emulators / compatible test servers MAY be used to test generic S3 adapter behavior.
         - AWS S3 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderAwsCompatibilityContractTest`) MUST pass against a real AWS S3 endpoint/account.
         - Cloudflare R2 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderR2CompatibilityContractTest`) MUST pass against a real Cloudflare R2 endpoint/account.
         - *Emulator Rule:* Passing against an emulator can NEVER upgrade AWS S3 or Cloudflare R2 to VERIFIED status.
       - *General Integration Scope:* Provides reusable object-storage semantics and capability discovery; does not claim full AWS SDK feature coverage, complete AWS S3 API coverage, or complete R2 parity with AWS. Commercial pricing properties (e.g. "zero-egress") are non-architectural and excluded.
       - `LocalFilesystemStorageAdapter`: Structured local directory hierarchy with atomic rename or manifest commit-marker.
       - `S3CompatibleObjectStorageAdapter`: S3 API client supporting endpoint, region, bucket, credential references, path prefixes, addressing modes (`PATH_STYLE` vs `VIRTUAL_HOSTED`), retry/backoff policy, bounded streaming, multipart upload thresholds, and checksum policy.
       - *Generic S3 Endpoints:* Configurable via standard S3 adapter; capability negotiation validates feature compliance.
     - **Storage Topology Policy (`storage-policy`):**
       - `LOCAL`: Persisted exclusively via local filesystem storage adapter.
       - `REMOTE`: Persisted exclusively via S3-compatible remote object storage adapter.
       - `MIRRORED`: Publication requires successful durable completion to BOTH local and remote storage destinations before transitioning to `AVAILABLE`.
       - *Staging Boundary:* Local filesystem may serve as temporary staging for streaming remote uploads without making staging itself the canonical remote backup destination.
  2. **Logical `BackupSet` Architecture, Publication Markers & Discovery Without SQL:**
     - Snapshots and backups operate on complete, durable logical `BackupSet`s rather than treating raw standalone `.schem` files as complete backups.
     - **Operational Metadata (`backup_operations` SQL Table):**
       - Canonical operational state machine: `PLANNED` $\to$ `CAPTURING` $\to$ `STAGED` $\to$ `UPLOADING` $\to$ `VERIFYING` $\to$ `AVAILABLE` (with failure/cleanup states: `FAILED`, `PARTIAL`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`).
       - Maintained in SQL persistence while the system is healthy.
     - **Immutable Manifest (`manifest.json`):**
       - Self-contained disaster-recovery document published/finalized only upon a completed capture generation.
       - Stored alongside artifacts in object storage (and local disk).
       - Contains stable facts: `backup_set_id`, `backup_type`, `root_type_id`, `root_key`, `created_at`, `authority_epoch`, `db_version`, `schema_version`, `plugin_version`, `game_mode_id`, `game_mode_version`, `consistency_result`, and `artifacts` map with strong SHA-256 checksums.
       - It **NEVER** contains continuously mutable lifecycle state fields (`status = UPLOADING`).
     - **`BackupPublicationMarker` (`AVAILABLE.marker` / typed discovery marker):**
       - Small discoverability marker uploaded to storage proving that the backup generation was completely published, verified, and remains an eligible restore candidate.
       - *Disaster Discovery Without Live Database:* Remote/local `manifest.json` files and `AVAILABLE.marker` allow disaster-recovery discovery even if the live SQL database is lost. A backup is considered an `AVAILABLE` restore candidate if and only if: (1) immutable manifest exists; (2) availability marker exists; (3) manifest and artifact checksum verification succeeds.
     - **Backup Classification & Restore Scope Safety:**
       - **`ROOT_BACKUP`:** Targets a single stable gameplay root (`PrimaryGameplayRootRef`: Island, Vessel, Course, etc.). Restores only the target root and its declared dependencies; never triggers a full database restore.
       - **`DATABASE_DISASTER_BACKUP`:** Whole-database disaster recovery via `DatabaseBackupPort`. Captures complete consistent relational export stored through `ObjectStoragePort`. Restoring a database disaster backup is a catastrophic, full-database disaster recovery workflow. A whole database is NEVER restored as an implicit side effect of an individual root rollback.
     - **Orthogonal Consistency Taxonomy:**
       - *Backup Lifecycle / Operational State:* `PLANNED`, `CAPTURING`, `STAGED`, `UPLOADING`, `VERIFYING`, `AVAILABLE`, `PARTIAL`, `FAILED`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`.
       - *Capture Coordination Mechanism / Evidence:* `QUIESCED` (capture performed during a bounded mutation-quiesce window, without unverified numeric latency constants), `VERSION_FENCED`, `TRANSACTION_SNAPSHOT`, `ROOT_MUTATION_FENCE`.
       - *Consistency Guarantee:* `FULL_RESTORE_CONSISTENT`, `ROOT_CONSISTENT`, `BEST_EFFORT / PARTIAL_NOT_RESTORABLE` (partial captures cannot be advertised as restorable unless an explicit restore mode supports that partial artifact set).
  3. **Database Backup Architecture & Root Snapshot Hexagonal Adapter Ownership:**
     - **`DatabaseBackupPort`:** Outbound infrastructure port defined in `:core` application boundary, implemented by `:persistence-adapter` or dedicated database-backup adapter. Decoupled from CLI command strings (`VACUUM INTO`, `pg_dump`, `mysqldump`). Establishes dialect-correct consistent capture and restore validation semantic contracts.
     - **Root Snapshot Hexagonal Adapter Ownership:**
       - `RootRelationalSnapshotPort`: Outbound port in `:core`, implemented by `:persistence-adapter` for root-scoped relational records (island level, custom name, flags, permissions, upgrades). Pure relational SQL without Bukkit/world dependencies.
       - `WorldDimensionSnapshotPort`: Outbound port in `:core`, implemented by `:bukkit-adapter` (or dedicated world snapshot adapter) for world/dimension chunk extraction and Folia thread coordination.
       - `RootStateSnapshotPort`: Coarse application-level coordinator façade in `:core` composing `RootRelationalSnapshotPort` and `WorldDimensionSnapshotPort`.
       - *Strict Invariant:* Database persistence adapter (`:persistence-adapter`) $\ne$ Minecraft world snapshot adapter (`:bukkit-adapter`). The persistence adapter MUST NEVER depend on Bukkit, Paper, or world classes.
  4. **Large Object Streaming & Memory Bounding:**
     - Direct in-memory `byte[]` accumulation of multi-gigabyte world or database archives is **STRICTLY PROHIBITED**.
     - All object transfers use bounded streaming buffers, disk-spooled staging where necessary, chunked multipart uploads with retryable part workers, and explicit cancellation/timeout tokens.
  5. **Two-Phase Bounded Island Capture Strategy (Strict Folia Thread Isolation):**
     - **Precise Snapshot Consistency Taxonomy:**
       - `ARCHIVE_INTEGRITY: GUARANTEED WITHIN DEFINED MODEL` — The compressed archive is validated via cryptographic SHA-256 checksums and an immutable manifest.
       - `OWNERSHIP_SAFE_CAPTURE: GUARANTEED WITHIN DEFINED MODEL` — Block data, tile entities, and entities are extracted strictly while executing within their respective Folia execution contexts. Live Bukkit references never escape their thread boundaries.
       - `ISLAND_POINT_IN_TIME_CONSISTENT: NOT GUARANTEED` — Chunks residing in different owning region contexts are captured at slightly different ticks (e.g. tick 100 vs tick 103) because server-wide world freezing is prohibited and regions split/merge dynamically.
       - `DB_WORLD_SINGLE_REVISION: NOT GUARANTEED` — World block state and SQL metadata are captured within a short quiesce window, but distributed skew across non-atomic region captures cannot guarantee an identical single-instant point-in-time across all chunks.
       - `WORLD_ATOMIC: NOT GUARANTEED` — Multi-region worlds in Folia cannot be snapshotted in a single global atomic instant without stopping the entire server.
     - **Absolute Thread Boundary Rule (Block vs Entity Scheduler Separation):**
       - **Block Geometry & Tile Entities:** Extracted strictly on that chunk's owning Folia `RegionScheduler`.
       - **Living Entities, Vehicles & Armor Stands:** Extracted strictly on the respective entity's `EntityScheduler` (or region-bound entity tick context) to prevent Folia cross-thread access exceptions.
       - **Entity Movement & Deduplication Contract:**
         - Chunks spanning different owning region contexts tick independently. If an entity crosses chunk/region boundaries during Phase 1 capture, the coordinator uses the **Entity UUID as a unique deduplication key** per snapshot generation token.
         - Entity extraction outcomes are explicitly recorded: `CAPTURED`, `DESPAWNED_DURING_CAPTURE`, or `UNAVAILABLE`.
         - *Point-in-Time Notice:* Global entity point-in-time consistency across asynchronous regions is **NOT guaranteed**.
       - Live Bukkit/Paper world, chunk, block, and entity references (`Block`, `Chunk`, `TileState`, `Entity`, `World`) MUST NEVER escape to asynchronous workers. Only pure Java immutable byte arrays, primitive coordinate buffers, and serialized NBT compound blobs are passed to `snapshotExecutor`.
     - **Phase 1: Region/Entity-Owned CAPTURE (Bounded Quiesce Window):**
       - A unique `snapshot_generation_token` (UUIDv4) is generated for the capture run.
       - A short quiesce window halts incoming block modifications and bank transactions on the target island.
       - Chunk extraction tasks run on owning `RegionScheduler`s; entity serialization tasks run on `EntityScheduler`s.
       - Database state (island level, custom name, flags, permissions, upgrades) is captured into the envelope tagged with revision $R$ (matching current DB `version` and `authority_epoch`).
     - **Phase 2: Off-Thread SERIALIZE, COMPRESS & PUBLISH:**
       - The mutation quiesce on the island is **immediately released**! Players resume block breaking and gameplay within the target window.
       - The immutable byte buffers are transferred to `snapshotExecutor` (async thread pool) for Zstandard compression, checksum generation, and publication via `ObjectStoragePort`.
     - **Bounded Mutation-Quiesce Duration & Timeout Invalidation:**
       - `Quiesce Target`: Targeted player pause window during Phase 1 capture is bounded to a minimal quiesce duration. Any numerical latency target is classified strictly as a measured operational SLO / benchmark and requires validated benchmark evidence before becoming a release requirement.
       - `Configurable Safety Timeout (max-snapshot-duration)`: Default **$500\text{ ms}$** ceiling (in `backups.conf`).
       - If chunk extraction does not conclude within $500\text{ ms}$, the coordinator invalidates `snapshot_generation_token` and marks snapshot `FAILED`. Late chunk results are discarded, and partial files are purged.
       - `finally release`: All event quiesce filters are unconditionally cleared in a `finally` block to prevent lingering frozen states.
  6. **Cryptographic Checksums & Application Integrity:**
     - Relying solely on provider `ETag`s is **STRICTLY PROHIBITED** due to multipart ETag variance across providers and encryption modes.
     - Every artifact in the `BackupSet` computes and records an application-level **SHA-256** checksum.
     - Restore pipelines unconditionally verify SHA-256 hashes prior to executing destructive restore units.
   7. **Retention & Rotation Policy (`RetentionPolicy`) — Recoverable Deletion Workflow:**
      - Retention operates strictly on complete `BackupSet`s, never on dangling individual objects.
       - **Publication & Recoverable Deletion Ordering (Marker / Tombstone Protocol):**
         - *Publication Ordering:* (1) Capture artifacts $\to$ (2) Upload artifacts $\to$ (3) Publish immutable manifest $\to$ (4) Verify all required artifacts/checksums $\to$ (5) Publish `AVAILABLE.marker` LAST $\to$ (6) SQL `BackupCatalogRecord` transitions to `AVAILABLE`.
         - *Deletion Ordering:* (1) SQL `BackupCatalogRecord` transitions to `DELETING` $\to$ (2) Remove/invalidate `AVAILABLE.marker` FIRST (or publish explicit deletion tombstone) $\to$ (3) Perform per-destination idempotent artifact cleanup $\to$ (4) Remove manifest when destination policy allows $\to$ (5) SQL transitions to `DELETED` only when configured deletion policy is satisfied across all destinations.
         - *Invariant:* Once deletion intent is durably published for a destination, that `BackupSet` ceases to appear as an `AVAILABLE` restore candidate, even if some artifacts still remain on storage and SQL is lost.
         - *No Cross-Destination Atomicity:* No distributed ACID transaction spans local filesystems, AWS S3, Cloudflare R2, and generic object stores.
         - Once `DELETING` begins, the BackupSet is immediately disqualified as an active restore candidate. Each configured destination cleanup is independently tracked and idempotent. Failures remain in `DELETING` / `RECOVERY_REQUIRED` until retried. Reaches `DELETED` only when configured policy is satisfied across all destinations. In `MIRRORED` backups, local deletion success + remote deletion failure persists progress and safely retries without impossible atomic rollbacks.
         - Supported policy strategies: `keep-last-n`, `age-based`, `scheduled-generations`, and `protected-manual-pins`.
  8. **Credential Security & Redaction:**
     - Object storage access keys, secret keys, and tokens are treated as infrastructure secrets.
     - Credentials MUST NEVER appear in domain models, database rows, public API events, backup manifests, log messages, or diagnostic dumps.
     - Diagnostic logging sanitizes output to expose only `providerId`, endpoint hostnames, bucket names, and object keys with credentials completely redacted.
   4. **Disaster Recovery, Restore Operations & Economic Safety Policy:**
      - Command: `/is admin rollback <island_id> [timestamp/latest] [--mode=<mode>]`
      - **Safety-Critical Restore Pipeline:**
        $$\text{Locate BackupSet} \to \text{Verify Manifest} \to \text{Verify Compatibility} \to \text{Download Artifacts} \to \text{Verify Checksums} \to \text{Fence Authority} \to \text{Enter Quarantine} \to \text{Restore State/World} \to \text{Reconciliation} \to \text{Commit Version/Authority} \to \text{Return Active}$$
        - *Fail-Closed Invariant:* The restore pipeline unconditionally aborts and fails closed if:
          1. Any required artifact listed in the manifest is missing from storage.
          2. Any artifact exhibits a cryptographic SHA-256 checksum mismatch.
          3. Data envelope, plugin, or GameMode versions are incompatible.
          4. Target authority epoch or database version changes during restore preparation.
          5. Required storage provider capabilities are unavailable.
        - *No Partial Success:* Performing a best-effort destructive partial restore and marking success is strictly prohibited.
      - **Durable Restore Operation State Machine (`restore_operations` & `restore_unit_progress`):**
        - To survive server crashes mid-restore without corrupting world blocks or leaving orphan states, every restore executes through a durable state machine:
          `PREPARING` $\to$ `QUARANTINED` $\to$ `APPLYING_WORLD` $\to$ `WORLD_APPLIED` $\to$ `APPLYING_METADATA` $\to$ `VERIFYING` $\to$ `COMMITTED` (or `FAILED` / `RECOVERY_REQUIRED`).
        - **Durable Write-Ahead Unit Protocol (`restore_unit_progress`):**
          - `last_completed_unit` in `restore_operations` serves solely as an indexing/monitoring optimization; the canonical progress state machine resides in `restore_unit_progress` (`restore_id, unit_id, generation, state, source_checksum, started_at, completed_at`).
          - For each chunk or region unit:
            1. Persist `APPLY_INTENT` with `source_checksum` and commit to SQL.
            2. Apply unit in valid Folia `RegionScheduler` context using **idempotent/deterministic replacement** (target state is replaced with snapshot unit state; incremental/additive operations are prohibited).
            3. Verify checksum/state against `source_checksum`.
            4. Persist `APPLIED` / `VERIFIED` with `completed_at = CURRENT_TIMESTAMP`.
          - If a crash occurs during `APPLY_INTENT`, on reboot the recovery worker discovers the unverified unit and safely re-executes the replacement idempotently.
        - **Quarantine Enforcement:**
          - The island remains strictly locked under `quarantine_state = 'RESTORING'` across server reboots.
          - **Invariant:** *The island MUST NOT exit quarantine until all units are `VERIFIED`, metadata application is complete, and the restore state machine transitions durably to `COMMITTED`.*
      - **`ECONOMIC_ITEM_STATE` Taxonomy & Entity Policy:**
        - **Taxonomy of Economic-Bearing World State:**
          1. Container block entities (chests, double chests, trapped chests, shulker boxes, barrels, hoppers, droppers, dispensers, furnaces, blast furnaces, smokers, brewing stands, chiseled bookshelves, crafters).
          2. Player-created or temporary container inventories.
          3. Shulker box item contents nested within other blocks.
          4. Item-holding entities (item frames, glowing item frames, armor stands, minecarts with chests/hoppers).
          5. Dropped `Item` entities on the ground.
          6. Entity equipment and mob inventories (mobs holding or wearing armor/weapons/items).
          7. Other platform-specific item-bearing adapters.
        - **Entity Restore Policy:**
          - *Economic-Bearing Entities:* Historical restore is **disabled** unless explicitly reconciled via a future ledger-aware mechanism.
          - *Non-Economic Entities (Animals, passive mobs):* Documented restore policy using UUID deduplication.
        - *Strict Accounting Invariant:* **A snapshot that is not DB/world point-in-time consistent MUST NOT restore historical economic item state on top of current durable economic state without ledger-aware reconciliation.**
      - **Deterministic Safe Restore Modes:**
        1. `GEOMETRY_ONLY`: Restores blocks, biomes, and non-economic block state. Restores NO historical item contents (`ECONOMIC_ITEM_STATE` skipped/cleared).
        2. `WORLD_CONTENT_SAFE`: Restores geometry and explicitly non-economic supported entities and tile states. Restores NO historical economic item state.
        3. `FULL_ISLAND`: Restores geometry, allowed island metadata, membership/flags/upgrades where permitted by contract. **STILL DOES NOT** automatically rewind: bank balances (`island_banks`), player inventories (`profile_inventories`), vault pages (`island_vault_pages`), transaction history (`bank_transactions`), or economic item-bearing world contents (`ECONOMIC_ITEM_STATE`).
        4. `ECONOMIC_RECONCILIATION_RESTORE`: Future ledger-aware historical container reconciliation capability. Status in current architecture: **`NOT SUPPORTED`**. Historical container items are NEVER quietly restored.
      - **Rollback Version Invalidation:** Upon `COMMITTED`, database version is monotonically bumped past the live version (`version = max(current_version, snapshot_version) + 100`), instantly invalidating any stale in-flight operations or cluster node caches.
   5. **Automatic Safety Triggers:**
      - Automatic snapshot creation before island deletion or reset (`/is reset`).
      - Periodic cron snapshot task during off-peak server hours.

---

### 2.30 Flight Integration & Boundary Enforcement Policy (`IslandPermission.FLY`, `uxm-tempfly` Bridge)
* **Decision:** Decoupled Standalone Enterprise Plugin (`uxm-tempfly`) with Clean Boundary Interception and Fall-Damage Shielding in Skyblock Core.
* **Core Architecture & Invariants:**
  1. **Strict SRP & Standalone Flight Engine:**
     - Flight timer calculation, particle trails, fly-blocks, and velocity sync reside in dedicated `uxm-tempfly` plugin to enable cross-gamemode utility (Survival, Hub, Skyblock).
  2. **Skyblock Boundary & Permission Enforcement:**
     - Skyblock exposes `IslandPermission.FLY` and `IslandFlag.VISITOR_FLY` allowing island owners to restrict flight for visitors or untrusted roles.
     - When a flying player crosses the perimeter out of their permitted island into the void or an un-permitted island, Skyblock fires `PlayerLeaveIslandTerritoryEvent`, prompting `uxm-tempfly` to cleanly disable flight mode.
  3. **Fall-Damage Shield Window:**
     - A temporary 10-second anti-fall damage invulnerability tag is applied whenever flight is automatically revoked at a boundary, ensuring players never die to boundary edge physics.

---

### 2.31 Hardware & Anti-Lag Tile/Entity Limit Subsystem (`IslandLimitService`, `limits.conf`, `upgrades.conf`)
* **Decision:** Event-Driven O(1) Atomic Primitive Delta Tracking with Upgrades Extension, Periodic Async Reconciliation, and `/is limits` GUI.
* **Core Architecture & Invariants:**
  1. **Schema-Driven Limits (`limits.conf`):**
     - Base quotas defined per block material and entity type:
       - **Tile Entities / Redstone:** `HOPPER`, `PISTON`, `STICKY_PISTON`, `OBSERVER`, `DROPPER`, `DISPENSER`, `BREWING_STAND`.
       - **Living Entities & Vehicles:** `VILLAGER`, `ARMOR_STAND`, `MINECART`, `BOAT`, `SPAWNER` (interfacing with `uxm-stacker`).
     - Global limit bypass permission: `uxmskyblock.bypass.limits`.
  2. **Dynamic Progression Integration (`upgrades.conf`):**
     - Limit categories scale dynamically through island upgrades (e.g. Hopper Tier 1: 50 -> Tier 2: 100 -> Tier 3: 250). Purchasing an upgrade atomically updates the in-memory cap.
  3. **Event-Driven O(1) Folia Region Interception:**
     - In-memory tracked per island using primitive `fastutil` `Object2IntOpenHashMap<LimitType>`.
     - `BlockPlaceEvent` and `EntitySpawnEvent` run on the owning region context, checking current count against effective limit. If count exceeds or equals limit, cancelled immediately (`event.setCancelled(true)`), dispatching a localized MiniMessage sound and actionbar error.
     - `BlockBreakEvent`, `BlockExplodeEvent`, `EntityDeathEvent`, and piston displacement events decrement atomic counters within the owning region context.
  4. **Periodic Reconciliation & Drift Prevention:**
     - Periodic off-peak async task (or `/is recalc`) verifies chunk tile counts via Folia `RegionScheduler` to heal any potential drift caused by crashes or external plugins.
  5. **Player Management GUI:**
     - `/is limits` opens an interactive `uxmlib-menu` / Bedrock form displaying categorized progress bars (current vs maximum) and direct upgrade action buttons.

---

### 2.32 Anti-Alt & Starter Economy Protection Subsystem (`IslandAntiAbuseService`, `anti_abuse.conf`)
* **Decision:** Hard Reset Inventory/Enderchest Purge with Quarantine Transfer Window, Reset Cooldowns, and Co-op Hopping Locks.
* **Core Architecture & Invariants:**
  1. **Strict Hard Purge on Island Reset/Deletion:**
     - Whenever an island is deleted or reset via `/is reset` or `/is delete`:
       - The island owner and active members experience a mandatory complete inventory, ender chest, equipment, and experience wipe (`purge-inventory-on-reset: true`).
       - Completely neutralizes the exploit vector of transferring starter items (lava, ice, saplings, iron tools) across repeated reset loops.
  2. **Quarantine Window on New Island Creation:**
     - Newly initialized islands enter a configurable quarantine duration (default: 15 minutes, `quarantine-duration: 15m`):
       - Item dropping (`PlayerDropItemEvent`) is disabled within the island boundary.
       - Visitor entry (`IslandFlag.VISITOR_ACCESS`) is forcibly locked until quarantine expiration to prevent physical item hand-offs.
  3. **Reset Cooldown & Daily Limit Policy:**
     - A strict reset cooldown (default: 12 hours, `reset-cooldown: 12h`) is enforced between island deletions/re-creations.
     - Configurable daily limit enforced via `max-resets-per-day: 3` (bypassable via permission `uxmskyblock.bypass.resetlimits`).
  4. **Co-op Hopping Lock:**
     - Players departing or kicked from a co-op team enter a mandatory quarantine cooldown (`coop-join-cooldown: 24h`), prohibiting immediate recruitment into other islands to eliminate nomadic chest/vault pillaging.

---

### 2.33 Automated Seasons & Competitive Payout Subsystem (`IslandSeasonService`, `seasons.conf`)
* **Decision:** Autonomous Season Lifecycle with Immutable Leaderboard Freeze, Queued Reward Action Dispatcher, Database Archiving, and Automatic World/State Wipe.
* **Core Architecture & Invariants:**
  1. **Schema-Driven Season Timeline (`seasons.conf`):**
     - Configures season lifecycle duration (e.g. 45 days, `duration: 45d` or exact target timestamp).
     - Automated countdown alerts, broadcast announcements, and bossbars at T-7 days, T-24h, T-1h, and final 10-minute intervals.
  2. **Immutable Leaderboard Snapshot & Freeze:**
     - Upon reaching season expiration (T-0), island score mutations (level recalculations, bank deposits, spawner placements) are frozen.
     - An immutable snapshot of Top $N$ placements across tracked metrics (`LEVEL`, `BANK`, `WORTH`) is captured and persisted to `islands_season_<season_number>` in SQL.
  3. **Autonomous Reward Action Dispatcher:**
     - Action pipelines declared per tier rank (e.g. 1st, 2nd, 3rd, 4th-10th):
       - `actions`: `[console] voucher give %leader% STORE_100USD`, `[console] crate give %leader% seasonal 10`.
     - **Offline Queuing (`pending_rewards`):** If a winning leader or member is offline at the moment of season transition, actions are safely persisted into `pending_rewards` and automatically dispatched on their next server login.
  4. **Archival & Zero-Touch Season Reset:**
     - Historical season data is fully queryable in-game via `/is season top <season_number>` through paginated `uxmlib-menu` and Bedrock forms.
     - The active world region grids and dynamic island rows are cleanly wiped and re-initialized via Folia time-sliced tasks, seamlessly transitioning the server to Season $N+1$ without requiring manual admin intervention.

---

### 2.34 Flexible Island Boosters & Multipliers Subsystem (`IslandBoosterService`, `boosters.conf`)
* **Decision:** Configurable Declarative Multiplier Engine with Category-Specific Stacking Strategies, Pause-on-Idle Semantics, and Interactive GUI.
* **Core Architecture & Invariants:**
  1. **Schema-Driven Categories (`boosters.conf`):**
     - Supports extensible multiplier targets: `SPAWNER_RATE`, `CROP_GROWTH`, `ORE_GENERATOR`, `MOB_EXP`, `ISLAND_WORTH`, `MISSION_REWARDS`.
  2. **Configurable Stacking Strategies (`stack-mode` per category):**
     - `DURATION`: Multiplier remains constant while active durations sum additively (e.g. 1h + 1h = 2h), constrained by `max-duration`.
     - `MULTIPLIER`: Multipliers compound or additively stack (e.g. 1.5x -> 2.0x -> 2.5x), constrained by `max-multiplier` hard cap.
     - `REPLACE`: Newer, higher-tier booster replaces an inferior active booster.
  3. **Multi-Booster Expiry Semantics (`duration-policy` in `MULTIPLIER` mode):**
     - `INDEPENDENT`: Each applied booster retains its own timestamp, decaying multipliers step-by-step as each expires.
     - `REFRESH`: Applying a new booster refreshes the overall duration timer to the full duration.
  4. **Pause-on-Idle Semantics (`pause-when-empty`):**
     - When zero island members are online, booster timers dynamically freeze to prevent wasted progression while offline, unfreezing seamlessly upon player reconnection.
  5. **Player Management & Visual Feedback:**
     - `/is booster` GUI presents active categories with live animated MiniMessage progress bars, current multipliers, and remaining time.
     - Actionbar HUD and `/is info` dynamically highlight active booster states.

---

### 2.35 Social Discovery, Island Ratings & Guestbook Subsystem (`IslandSocialService`, `social.conf`)
* **Decision:** Extensible Rating & Guestbook Engine Governed by `RatingPolicy` with Paginated Interactive Guestbook and Multi-Layered Anti-Abuse Filters (Subject to Open Product Decision A).
* **Core Architecture & Invariants:**
  1. **Rating Policy & Scoring Calculation:**
     - Concrete scoring scales and aggregation formulas are governed by `RatingPolicy` / `RatingAggregationPolicy` (e.g. weighted averages, minimum confidence thresholds, or Bayesian models configurable in `social.conf`).
  2. **Multi-Layered Anti-Abuse & Fraud Protection:**
     - **Dwell Time Requirement:** Visitors must physically spend a configurable duration on the target island before rating unlocks.
     - **Server Playtime Threshold:** Accounts must meet playtime thresholds to cast votes, preventing fresh burner alts from skewing scores.
     - **Subnet/IP Blacklist & Affiliation:** Votes from the same IP/subnet as the island members, or votes cast by co-op members/allies, are rejected.
     - Exactly 1 active rating per profile per subject (players may update their rating at any time idempotently).
  3. **Interactive Guestbook (`/is guestbook`):**
     - Visitors can submit signed MiniMessage notes via `/is guestbook sign <message>` (subject to length limits and chat regex safety).
     - Island owners can inspect, delete, or feature/pin (`PIN`) up to 3 priority entries at the top of the guestbook via `uxmlib-menu` and Bedrock forms.
  4. **Persistence & Caching:**
     - Ratings and guestbook records persist in `social_ratings` and `guestbook_reviews` SQL tables, with cached top entries and current aggregated score cached in memory.

---

### 2.36 Standalone Discord Webhook Notification Subsystem (`IslandDiscordWebhookService`, `discord.conf`)
* **Decision:** Pure Standalone Asynchronous HTTP Webhook Engine with Zero Third-Party Plugin Ties, Token-Bucket Rate Limiting, and Rich Embed Templating.
* **Core Architecture & Invariants:**
  1. **Strictly Standalone Architecture:**
     - Operates with zero reliance on Discord bots or external plugins (e.g. DiscordSRV).
     - Directly dispatches HTTP POST payloads to user-configured Webhook URLs using Java 25 `HttpClient` asynchronously.
  2. **Categorized Notification Endpoints (`discord.conf`):**
     - Channels declared independently per event topic:
       - `milestones`: Island level milestones (e.g. Level 1,000, 5,000, 10,000 reached) featuring leader skin avatar, island name, and member list.
       - `leaderboards`: Periodic or season-end Top 10 leaderboard summary embeds.
       - `alliances`: Diplomatic pact creations and dissolutions.
       - `admin_audit`: High-priority staff alerts (unusual level surges, manual administrative rollbacks, island deletions).
  3. **Non-Blocking Rate-Limiting & Asynchronous Queue:**
     - To comply with Discord API limits (HTTP 429 Too Many Requests) and eliminate any potential Folia tick disruption, all webhook dispatches flow through a dedicated background `LinkedBlockingQueue` governed by a token-bucket rate limiter (max 2-3 requests/second).
  4. **Declarative Embed Formatting:**
     - Embed templates configure dynamic colors, embed titles, descriptions, and inline fields with placeholder resolution (`%island_name%`, `%leader%`, `%level%`, `%rank%`).

---

### 2.37 Multi-Dimension Architecture & Portal Linkage Subsystem (`IslandDimensionService`, `dimensions.conf`)
* **Decision:** Config-Driven Hybrid Dimension Provider Supporting Both 1-to-1 Private Island Dimensions and Shared Wilderness Worlds.
* **Core Architecture & Invariants:**
  1. **Declarative Dimension Configuration (`dimensions.conf`):**
     - Each extra dimension (`NETHER`, `THE_END`) can be independently mapped to either:
       - `PRIVATE_ISLAND`: Island coordinates mirror 1-to-1 to a corresponding dimension grid (`skyblock_nether`, `skyblock_the_end`). Entering a portal automatically generates/pastes a specialized dimension schematic (Nether fort outpost, End crystal platform) at the island's relative position. Permissions and boundaries inherit identically from the Overworld island.
       - `SHARED_WORLD`: Portals transport players to a centralized, shared wilderness world (`world_nether`, `world_the_end`) with optional recurring world-reset/regen schedules and full open-world PvP dynamics.
       - `DISABLED`: Portal activation is prohibited, returning a localized MiniMessage warning.
  2. **Folia-Safe Portal Teleport Interception (`PlayerPortalEvent`):**
     - Intercepts portal activation asynchronously. Calculates destination coordinates on the target world's Folia `RegionScheduler`.
     - Validates destination safety before finalizing teleportation via `SafeTeleportEngine`.
  3. **Multi-Dimension Asset & Schematic Lifecycle:**
     - In `PRIVATE_ISLAND` mode, deleting an island (`/is delete`) or purging chunks cleanses all 3 coordinate slots across Overworld, Nether, and The End in parallel without blocking region execution contexts.

---

### 2.38 Third-Party Plugin Migration Policy & Architectural Boundary
* **Decision:** Explicit Rejection of Third-Party Competitor Migration (Zero Legacy Baggage, Clean-Room Policy).
* **Core Architecture & Invariants:**
  1. **Strict Rejection Policy:**
     - Ingestion or automated migration from legacy competitor plugins (SuperiorSkyblock2, BentoBox, IridiumSkyblock, ASkyBlock) is strictly prohibited.
     - **Rationale:** Legacy competitor schemas carry fragmented NMS metadata, non-Folia thread assumptions, inconsistent coordinate math, and untracked edge cases. Ingesting arbitrary external schemas poses unacceptable risks of data corruption, race conditions, and architectural pollution.
  2. **Clean-Room Ecosystem Invariant:**
     - All servers adopting UXPLIMA Skyblock deploy natively against our clean-room, strictly typed `TxSql` schema and hexagonal grid engine, ensuring relational ACID consistency and eliminating legacy migration baggage.

---

### 2.39 Island Bankruptcy & Upkeep Failure Subsystem (`IslandBankService`, `bank.conf`)
* **Decision:** Grace-Period Debt Escalation and Quarantine Lockout without Data Loss or Automated Island Deletion.
* **Core Architecture & Invariants:**
  1. **Configurable Upkeep Switch (`bank.conf`):**
     - Upkeep defaults to disabled (`upkeep.enabled: false`).
     - When enabled, runs an automated scheduled task to debit maintenance fees from the primary `IslandBank` wallet.
  2. **Two-Stage Failure Lifecycle:**
     - **Stage 1: Grace Period (`BANKRUPTCY_GRACE`):**
       - If bank funds are insufficient during an upkeep cycle, the island enters a 48–72 hour grace window (`grace-duration: 72h`).
       - Members receive persistent login alerts and actionbar warnings with the outstanding debt amount and countdown timer. All island functions remain active.
     - **Stage 2: Quarantine Lockout (`BANKRUPTCY_LOCKED`):**
       - Upon grace expiration with outstanding unpaid balance, the island locks down:
         - Visitor warps and access are blocked.
         - Spawners, custom generators, and crop growth tick events are suppressed within island bounds.
         - Member interactions are restricted strictly to `/is bank deposit` and viewing debt status.
  3. **Instant Atomic Remediation:**
     - The moment sufficient funds are deposited to settle the arrears, the lockout status clears atomically in memory and database, restoring all island functions immediately.
  4. **Non-Deletion Architectural Policy:**
     - Bankruptcy does not trigger automated island deletion or chunk wiping; player builds remain preserved indefinitely.

---

### 2.40 Administrative Quarantine & Freeze Subsystem (`IslandAdminFreezeService`, 4-Dimensional Orthogonal State Model)
* **Decision:** 4-Dimensional Orthogonal Domain State Architecture, Strict State Transition Invariants, O(1) Folia Region Interception, Immediate Visitor Eviction, and Staff Inspection Tools.
* **Core Architecture & Invariants:**
  1. **4-Dimensional Orthogonal State Architecture (Eliminating State Collisions):**
     - Because an island can simultaneously suffer financial bankruptcy and an administrative freeze, collapsing these into a single restriction enum is defective. The domain explicitly decouples island state into four independent, composable dimensions:
       - **`IslandLifecycle`:** `ACTIVE`, `DELETING`, `RECYCLING`, `ARCHIVED`
       - **`ResidencyState`:** `LOADED` (active in L1 JVM heap), `UNLOADED` (cold in SQL)
       - **`EconomicState`:** `NORMAL`, `BANKRUPTCY_GRACE`, `BANKRUPTCY_LOCKED`
       - **`AdministrativeState`:** `NORMAL`, `FROZEN`
     - **Architectural Justification:** Modeling `EconomicState` and `AdministrativeState` as distinct, strongly typed enums rather than an untyped set provides compile-time exhaustiveness checks (`switch`), prevents invalid state combinations, and ensures that settling bankruptcy debt never accidentally unfreezes an island under staff investigation (and vice-versa).
     - **Composition Example:** An island can exist as:
       `[Lifecycle: ACTIVE, Residency: UNLOADED, Economic: BANKRUPTCY_LOCKED, Administrative: FROZEN]`.
       When staff execute `/is admin unfreeze`, `AdministrativeState` transitions to `NORMAL`, while `EconomicState` correctly remains `BANKRUPTCY_LOCKED`.
  2. **State Transition Invariants:**
     - **Lifecycle Guard:** When `IslandLifecycle` is in `DELETING`, `RECYCLING`, or `ARCHIVED`, all mutations to `EconomicState` and `AdministrativeState` are strictly rejected.
     - **Economic Escalation Order:** `EconomicState` cannot jump directly from `NORMAL` to `BANKRUPTCY_LOCKED`; it must transit through `BANKRUPTCY_GRACE`.
     - **Instant Remediation:** Depositing sufficient funds to clear arrears transitions `EconomicState` from `BANKRUPTCY_LOCKED` or `BANKRUPTCY_GRACE` to `NORMAL` atomically.
     - **Administrative Authority:** `AdministrativeState` transitions (`NORMAL` $\leftrightarrow$ `FROZEN`) are strictly restricted to authenticated operator commands (`/is admin freeze|unfreeze`) or verified automated anti-exploit heuristics.
  3. **Sub-Microsecond Region Event Interception:**
     - Evaluated at the top of all Folia region event listeners:
       - Block breaking (`BlockBreakEvent`), block placement (`BlockPlaceEvent`), block interactions (doors, chests, hoppers, furnaces).
       - Entity damage/killing and physical item dropping (`PlayerDropItemEvent`).
       - Island bank debits/withdrawals and island resets (`/is reset`).
     - When `administrativeState == AdministrativeState.FROZEN`, any unauthorized player action is instantly aborted (`event.setCancelled(true)`) with a MiniMessage alert stating the freeze rationale.
  4. **Visitor Eviction & Perimeter Lockdown:**
     - Activating freeze instantly teleports all non-staff visitors to `/spawn` and prohibits warp navigation into the island coordinates.
  5. **Privileged Staff Investigation Tools:**
     - Staff holding `uxmskyblock.admin.inspect` bypass all freeze restrictions:
       - Can inspect chest inventories, inspect block worth breakdowns (`/is admin inspect <island>`), or execute rollbacks (`/is admin rollback`).
     - Administrative release executed seamlessly via `/is admin unfreeze <island>`, atomically restoring `administrative_state = NORMAL`.

---

### 2.41 Core Operational Configuration & Gameplay Toggles (`config.conf`, `settings.conf`)
* **Decision:** Granular, Declarative Quality-of-Life, Combat Protection, Performance Optimization, and Economy Settings.
* **Core Architecture & Invariants:**
  1. **Quality-of-Life & Player Ergonomics:**
     - `obsidian-to-lava: true`: Controlled by anti-exploit accidental tracking defined in Section 2.42(1).
     - `void-teleport.members: true`, `void-teleport.visitors: true`: Controlled by zero-velocity shield defined in Section 2.42(2).
     - `default-command-action: "auto"`: `/is` dynamically maps to creation GUI for players without an island, or home teleport/menu for existing owners.
     - `island-names.enabled: true`: Support for `/is rename <name>` with length bounds (3-16 chars), profanity filters, and MiniMessage styling.
     - `teleport-warmup: 3s`: Movement/combat-interrupted teleport warmup for `/is home`, `/is warp`, `/is visit` (bypassable via `uxmskyblock.bypass.warmup`).
  2. **Security & Anti-Trap Combat Invariants:**
     - `teleport-on-pvp-enable: true`: Toggling island PvP on immediately evicts external visitors to `/spawn` to prevent malicious entrapment.
     - `immune-to-pvp-when-teleport: true`: 10-second PvP invulnerability window upon warp arrival into PvP-enabled islands.
     - `stop-border-crossing: true`: Physical repulsive barrier preventing walking or pearl-glitching past island boundaries into the void.
  3. **Folia Performance & AFK Optimization:**
     - `disable-redstone-offline: true`: Suspends redstone clock updates, observers, and pistons on islands when zero members are online.
     - `afk-integrations.disable-spawning: true`, `disable-redstone: true`: Suppresses entity spawner ticks and redstone physics when all island members enter AFK state.
     - `nether-roof: false`: Generates open-sky Nether islands in `skyblock_nether`, omitting massive bedrock ceiling layers to slash disk usage and lighting calculations by ~40%.
  4. **Economy & Endgame Mechanics:**
     - `sync-worth-with-shop: "BUY"`: Governed by the reactive dynamic pricing engine defined in Section 2.42(8).
     - `negative-level-allowed: false`: Bounds island level floor strictly to 0.
     - `end-dragon-fight.enabled: true`: Per-island respawnable Ender Dragon boss encounters within dedicated `skyblock_the_end` coordinates.

---

### 2.42 Enterprise Operational Invariants & Elevated Competitor Enhancements
* **Decision:** Replacing Competitor Anti-Patterns with Anti-Exploit Obsidian Tracking, Zero-Velocity Void Shields, Adaptive Folia Backpressure, Kinetic Wards, and Reactive Dynamic Pricing.
* **Core Architecture & Invariants:**
  1. **Anti-Exploit Obsidian Recovery (`protection.conf`):**
     - Rather than allowing any placed obsidian to be converted to lava (which creates infinite shop lava dupe exploits), `obsidian-recovery` tracks accidental cobblestone generator water-lava contact via transient PDC tags expiring after 60 seconds (`accidental-only: true`). Only genuine generator misfires can be collected back into empty buckets, accompanied by a sound cue (`ITEM_BUCKET_FILL_LAVA`) and smoke particle burst.
  2. **Zero-Velocity Void Recovery & Kinetic Shield (`protection.conf`):**
     - Resolves the common Folia velocity carryover death bug by completely resetting the falling player's velocity vector to `Vector(0, 0, 0)`, wiping accumulated fall distance, and granting a 10-second `fall-damage-shield` upon teleporting to the island safety anchor.
  3. **Adaptive Folia Backpressure Budgets (`performance.conf`):**
     - Eliminates static batch sizes that worsen server lag spikes. Schematics and deletion engines dynamically monitor target Folia region TPS:
       - Normal state ($\text{TPS} \ge 19.5$): Up to 128 blocks/tick pasting and 100 chunks/sec deletion.
       - Backpressure throttle ($\text{TPS} < 19.5$): Automatically steps down to 16 blocks/tick to protect server tick cadence (`adaptive-throttle: true`).
  4. **Kinetic Ward Teleport Cleansing (`protection.conf`):**
     - Rather than brutally despawning hostile mobs on teleport (which deletes players' named pets, custom bosses, or tag-locked mobs), teleport arrivals radiate a 5-block kinetic repulsive wave (`kinetic-repulse: true`) pushing hostile entities outwards with particle effects.
  5. **Native Async Structure Suppression (`world.conf`):**
     - Prevents catastrophic 30-second server freezes caused by void-world locate searches (cartographer treasure maps, dolphins, Eyes of Ender) by hooking Paper/Folia structure generation and suppressing configured targets (`minecraft:*_mansion`, `minecraft:*_monument`, `minecraft:buried_treasure`, `minecraft:trial_chambers`) with zero-latency "not found" returns.
  6. **Interactive Multi-Layer Web Maps (`Dynmap`, `BlueMap`, `Pl3xMap`):**
     - Exposes distinct web map layers:
       - Gold boundary highlights for Top 10 leaderboards.
       - Alliance-colored territory borders.
       - Clickable rich HTML tooltips displaying Island Level, Net Worth, Bank Balance, and Member skin heads.
  7. **Categorical Declarative Interactables (`interactables.conf`):**
     - Groups blocks into semantic HOCON blocks (`DOORS_AND_GATES`, `REDSTONE_TRIGGERS`, `CONTAINERS`, `WORKSTATIONS`) mapped dynamically to `IslandPermission` bitsets, eliminating hardcoded block checks.
  8. **Reactive Event-Driven & Dynamic Pricing Engine (`levels.conf`):**
     - To support modern dynamic economy plugins (EconomyShopGUI Dynamic Pricing, DynamicShop), the valuation engine listens to price-change events (`EconomyShopPriceUpdateEvent`) for instant RAM updates and employs a 2-minute Caffeine TTL async background refresh for non-event shops.
     - Implements a configurable `damping-factor: 0.85` to neutralize market speculation arbitrage.

---

## 3. Architecture Specification Verification & Sign-Off

This document consolidates and finalizes all architectural invariants, distributed concurrency protocols, and gameplay systems for UXPLIMA Skyblock:
- **Folia Concurrency Isolation:** 5,120-block heuristic, dynamic region splitting, and non-blocking scheduling.
- **Distributed Safety:** Single-writer node leases, SQL OCC / CAS versioning, transactional outbox + Redis Streams, fail-closed degradation policies, and operation idempotency.
- **Modular Domain Architecture:** Orthogonal 3D state machine, Service Registry capability model, restart-only module lifecycles, and multi-profile inventory isolation.
- **Enterprise Storage & Snapshots:** SQL sole source of truth, Zstandard compression, mutation fencing, and monotonic version invalidation.

Approved for transition to implementation planning.
