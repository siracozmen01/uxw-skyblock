# Architecture Specification: Hexagonal & Domain-Driven Design (DDD)

**Document Version:** 1.3.0
**Date:** 2026-09-13
**Status:** FROZEN BY PRODUCT OWNER — 2026-09-13
**Architecture Board State:** FROZEN

## 1. UXPLIMA House Architecture Reference

**Canonical Reference:** [`https://github.com/UXPLIMA/uxm-essentials`](https://github.com/UXPLIMA/uxm-essentials)

`uxmSkyblock` strictly adheres to `UXPLIMA/uxm-essentials` as its immutable architectural house reference. The architecture follows a disciplined **Hexagonal (Ports and Adapters)** structure combined with **Domain-Driven Design (DDD)** bounded contexts:

* **Pure Domain Model:** Entities, aggregates, value objects, and domain events implemented in pure Java with **zero external dependencies**.
* **Application / Use-Case Layer:** Use-case interactors, orchestrators, and transaction boundaries that operate on pure domain aggregates.
* **Explicit Inbound (Driving) & Outbound (Driven) Ports:** Interfaces defined entirely within the domain and application layers without leaking infrastructure types.
* **Separated Adapter Modules:** Infrastructure adapters live in dedicated adapter submodules. Platform shells (Paper/Folia) and persistence adapters (SQL/storage) are strictly decoupled.
* **Composition-Root Wiring:** Dependency injection and service composition occur strictly at the outer platform shell boundary during application bootstrap.
* **ArchUnit Boundary Enforcement:** Automated architectural fitness functions strictly enforce module purity and package boundaries.

### 1.1 Invariant: Pure Core (`:core`)
The `:core` module contains **pure Java domain and application business logic**.
* **Forbidden in `:core`:**
  - Minecraft / Paper / Bukkit / Folia APIs (`org.bukkit.*`, `io.papermc.*`)
  - JDBC / SQL database drivers (`java.sql.*`, HikariCP)
  - Redis clients / transports (`io.lettuce.*`)
  - Configuration libraries (`org.spongepowered.configurate.*`)
  - Plugin APIs (`net.milkbowl.vault.*`, `me.clip.placeholderapi.*`, `org.geysermc.floodgate.*`)
  - **`uxm-lib` infrastructure adapters** (`uxmlib-storage`, `uxmlib-redis`, etc.)
* `:core` depends **strictly** on `:api` and standard annotations (`org.jspecify.annotations.*`). Outbound infrastructure adapters do NOT belong in `:core`.

```
       +--------------------------------------------------------------+
       |                       INBOUND ADAPTERS                       |
       |  (Brigadier Commands, Bukkit Event Listeners, GUIs, Bedrock) |
       +------------------------------+-------------------------------+
                                      |
                                      v (Calls Driving Ports)
       +--------------------------------------------------------------+
       |                 APPLICATION SERVICES & USE CASES              |
       |             (Orchestration, Transaction Boundaries)          |
       +------------------------------+-------------------------------+
                                      |
                                      v (Operates On)
       +--------------------------------------------------------------+
       |                      CORE DOMAIN MODEL                       |
       |    (Entities, Aggregates, Value Objects, Domain Events)      |
       |             *PURE JAVA — ZERO INFRASTRUCTURE*                |
       +------------------------------+-------------------------------+
                                      |
                                      v (Defines Driven Ports)
       +--------------------------------------------------------------+
       |                      OUTBOUND PORTS                          |
       |  (Repository Ports, Region Port, Economy Port, Journal Port) |
       +------------------------------+-------------------------------+
                                      ^
                                      | (Implements Driven Ports)
       +--------------------------------------------------------------+
       |                      OUTBOUND ADAPTERS                       |
       | (:persistence-adapter, :redis-adapter, Vault Adapter, FAWE)  |
       +--------------------------------------------------------------+
```

### 1.2 Competitive Analysis & Design Principles
Competitor implementations (e.g. BentoBox, SuperiorSkyblock, IridiumSkyblock) serve strictly as **empirical evidence and functional input, NEVER as architecture templates to copy**.
* **Canonical Design Direction:**
  $$\text{Competitor Gameplay Mechanic} \longrightarrow \text{Extract Reusable Domain Requirement} \longrightarrow \text{Model with UXPLIMA Hexagonal + DDD Architecture}$$
* **Explicitly Rejected Competitor Anti-Patterns:**
  1. **Untyped Maps:** BentoBox-style untyped `Map<String, Object>` request metadata, reflection-based dispatch, or arbitrary object parameters are strictly prohibited. All contracts are compile-time strongly typed Java records and sealed interfaces.
  2. **Custom Addon Classloaders:** Private custom addon classloaders (e.g. BentoBox custom jar loader) are rejected. Modularity is governed by standard Java SPIs, Spring/Dagger-style explicit composition roots, or the host Paper plugin classloader.
  3. **Redis as Canonical Authority:** Using Redis as a primary source of authority or persistence is rejected. Redis serves strictly as an ephemeral cache and pub/sub transport; relational SQL remains the sole canonical source of truth for all authoritative state, epoch fencing, and durable mutation journals.
  4. **Raw Console-Command Lists:** Executing raw `List<String>` console commands as a universal game action or template creation mechanism is rejected. Domain actions are modeled via strongly typed `CreationAction` and `CreationActionProvider` SPIs with schema-validated configurations.
  5. **Account UUID Authorization:** Using Minecraft account `player_uuid` as the primary authorization or data scoping key is rejected. All gameplay state, membership, rewards, homes, and transactions are strictly **profile-scoped** (`profile_id`), preserving multi-profile isolation (`CLASSIC`, `IRONMAN`, `HARDCORE`, `STRANDED`).
  6. **Floating-Point Money:** Floating-point currency (`double`, `float`) is strictly prohibited. Currencies are represented by 64-bit integer minor units (`long` cents/satoshis) via `CurrencyAmount`.
  7. **Bukkit Types in Pure Domain:** Importing Bukkit/Paper types (`org.bukkit.*`, `io.papermc.*`) into `:core` or `:api` is strictly prohibited.
  8. **God Manager / Service Architectures:** Monolithic 2,000-line "Manager" classes are rejected in favor of focused, single-responsibility domain aggregates, interactors, and explicit application ports.

### 1.3 Product Owner Release-Scope Rule — V1 Only
The Product Owner has explicitly rejected the obsolete Day-0 / Day-1 / contracts-now / implementation-later / post-launch scope model.
* **Single Release Target:** **V1**.
* **Universal Rule:** Every Product-Owner-approved capability in the architecture must have its production implementation completed in V1:
  $$\text{APPROVED PRODUCT REQUIREMENT} \implies \text{V1 COMPLETE IMPLEMENTATION REQUIRED}$$
* **Configurability vs Implementation Boundary:**
  - **OPTIONAL MODULE $\ne$ DEFERRED MODULE:** A feature being optional means the server operator can enable, disable, or tune it in configuration, NOT that the implementation is left unfinished.
  - **OPTIONAL PROVIDER $\ne$ FUTURE IMPLEMENTATION:** Provider abstractions exist so different concrete backends can be selected; V1 required providers must be completely implemented in production code.
  - **CONTRACT DEFINED $\ne$ V1 COMPLETE:** Defining an SPI or interface is step one; full production implementation is mandatory for all approved V1 capabilities.
* **Scope Application:** All approved advanced subsystems (Seasons, Social Ratings & Guestbook, Discord Webhooks, Alliances, Dynamic Shop Pricing), competitive capabilities, and general object storage with local and remote S3-compatible providers (AWS S3 & Cloudflare R2) are V1 requirements.

---

## 2. Bounded Contexts & Aggregate Model

The domain is divided into distinct, loosely coupled **Bounded Contexts**. Each bounded context owns its entities, value objects, ports, and services:

1. **GameMode Instance & Archetype Context (`com.uxplima.uxmskyblock.gamemode`)**
   - **Aggregate Root:** `GameModeInstance`
   - **Entities & Value Objects:** `GameModeDescriptor`, `DimensionDefinition`, `DimensionId`, `PrimaryGameplayRootRef`, `TypedProviderConfig`, `StartTemplateBundle`, `CreationAction`.
   - **Responsibilities:** Archetype lifecycle (12 target modes including SkyBlock, OneBlock, ChunkBlock, AcidIsland, CaveBlock, SkyGrid, Boxed, Poseidon, StrangerRealms, TradeWinds, Parkour, Brix), content pack versioning (`implementationVersion`, `schemaVersion`, `contentDefinitionVersion`), instance update policies (`PINNED`, `MIGRATED`, `AUTO_SAFE_UPDATE`), dimension routing, and primary gameplay root reference.
   - **Start Template Bundles:** Multi-dimension / multi-root starter assets (`StartTemplateBundle`) mapped by extensible `DimensionId`, eligibility policies, and typed `CreationAction` executions (never raw console command strings).
   - **Offline Simulation Policy:** Explicit policy distinguishing `REALTIME_SIMULATION` (only loaded Folia regions), `PAUSED_WHEN_UNLOADED`, `RECONCILED_ON_LOAD` (deterministic catch-up), and `PROVIDER_DEFINED_OFFLINE` (SPI). Never force-loads worlds or bypasses authority for fake progress.
   - **Supported Primary Roots:** Open and namespaced root type IDs (e.g. `uxm:island`, `uxm:vessel`, `uxm:course_plot`, `uxm:creative_plot`, `uxm:territory_claim`).

2. **Profile & Ruleset Context (`com.uxplima.uxmskyblock.profile`)**
   - **Aggregate Root:** `PlayerProfile`
   - **Entities & Value Objects:** `PlayerAccount`, `ProfileRuleset` (`CLASSIC`, `IRONMAN`, `HARDCORE`, `STRANDED`), `ProfileLifecycleState`.
   - **Responsibilities:** Multi-profile isolation per player account, profile creation/deletion, profile-switch validation, ruleset economic policy enforcement (e.g. auction/trading/co-op restrictions on Ironman profiles).
   - **Single Canonical Source of Active Profile:**
     - Live Online Authority: `player_sessions.active_profile_id` (guarded by node session lease).
     - Offline / Default Profile: `player_accounts.active_profile_id`.
     - Synchronization Invariant: Modified strictly under an exclusive row lock on `player_sessions`.

3. **Participation, Co-op Membership & Temporary Access Context (`com.uxplima.uxmskyblock.participation`)**
   - **Aggregate Roots:**
     - `GameModeParticipation` (Canonical Cross-Mode Participation)
     - `TemporaryAccessGrant` (Conceptual Aggregate for Time-Bounded / Session Trust)
   - **Domain Projection:** `IslandMember` / `IslandTeam` (Island-Specific Domain Projection)
   - **Responsibilities:** Profile-scoped co-op membership (`PRIMARY KEY (instance_id, profile_id)`), role permissions, multi-axis compatibility evaluation (joining profile ruleset $\times$ target mode $\times$ existing participants).
   - **Temporary Access Grants & Distributed Termination Semantics:**
     - **Aggregate Model:** `TemporaryAccessGrant(grantId, targetInstanceId, targetRootRef, granteeProfileId, grantedByProfileId, accessPolicy, createdAt, expiresAt, terminationPolicy)`.
     - **Target Root Identity:** Fully qualified via `PrimaryGameplayRootRef(rootTypeId, rootKey)` (e.g. `uxm:island` + islandId, `uxm:vessel` + vesselKey), never assuming every root is an island UUID.
     - **Termination Policies & Concrete Distributed Anchors:**
       - `UNTIL_REVOKED`: Persists until explicitly revoked by an authorized profile.
       - `UNTIL_SESSION_END` (or `UNTIL_LOGOUT`): Anchored to canonical player session generation (`anchor_player_uuid`, `anchor_session_epoch`).
         - *Anchor Invariant:* `anchor_player_uuid` MUST belong to `grantee_profile_id`.
         - *Exact Validity Predicate:* Remains session-valid only while canonical `player_sessions` represents the SAME live session generation: session row absent $\implies$ expired; session epoch different $\implies$ expired; same epoch but session state is no longer live/active (`state != 'ACTIVE'` or `lease_expires_at < CURRENT_TIMESTAMP`) $\implies$ expired. Reuses approved canonical session lifecycle/lease semantics.
       - `NODE_PROCESS_RESTART`: Node-local convenience policy evaluated against canonical runtime identity `CurrentNodeProcessIdentity(nodeId, processGenerationId)`.
         - *Exact Validity Predicate:* Invariant `current node/process identity != stored anchor identity` $\implies$ expired. Strictly node-local; does NOT become distributed authority and does NOT require Redis for correctness.
       - `UNTIL_TIMESTAMP`: Time-bounded expiration evaluated against database clock.
     - **Normalized Permission Persistence:** Uses typed normalized relational persistence (`temporary_access_grants` + `temporary_access_grant_permissions(grant_id, permission_key)`). Stable namespaced keys remain canonical.
     - *Critical Invariant:* `TemporaryAccessGrant != GameModeParticipation` and `TemporaryAccessGrant != IslandMembership`. A trusted visitor NEVER silently becomes a permanent member. Ruleset compatibility and security barriers strictly apply.
   - *Invariant:* Membership is strictly profile-scoped (`profile_id`), never account-scoped (`account_uuid`), ensuring multi-profile players maintain isolated co-ops across Classic and Ironman profiles.

4. **Distributed Authority & Fencing Context (`com.uxplima.uxmskyblock.authority`)**
   - **Entities & Value Objects:** `AuthorityBinding`, `AuthorityLockKey`, `AuthorityEpoch`, `LeaseExpiration`.
   - **Tables:** `island_authorities`, `player_sessions`, `game_mode_instance_authorities`, `mode_owned_authorities`.
   - **Responsibilities:** Distributed single-writer authority leases, monotonic epoch advancement on takeover, canonical row-lock serialization (`FOR UPDATE` / `BEGIN IMMEDIATE`), fail-closed timeout defense, local monotonic self-fencing.
   - **Global Authority Lock Ordering Contract:**
      - Multi-resource operations lock authorities in strict order of `AuthorityLockKey(scopeRank, keyTypeRank, uuidMsb, uuidLsb, namespacedKey)`:
        1. Explicit stable scope rank: `PLAYER_SESSION` (Rank 0) < `ISLAND` (Rank 1) < `GAME_MODE_INSTANCE` (Rank 2) < `MODE_OWNED` (Rank 3). Never relies on Java enum ordinal.
        2. Explicit stable key type rank: `KEY_TYPE_UUID` (Rank 0) < `KEY_TYPE_NAMESPACED_BYTES` (Rank 1). Resolves mixed UUID vs String comparison deterministically and null-safely.
        3. UUID keys: Compared in unsigned 128-bit binary order (`Long.compareUnsigned(msb)` then `Long.compareUnsigned(lsb)`).
        4. Namespaced keys: Evaluated via unsigned lexicographical comparison of canonical UTF-8 byte arrays (`Arrays.compareUnsigned`).
      - This single exact comparator governs Player ↔ Player, Player ↔ Island, Player ↔ GameModeInstance, ModeOwned authority, and multi-inventory compound operations without deadlock.

5. **Economic Inventory & Mutation Journal Context (`com.uxplima.uxmskyblock.inventory`)**
   - **Entities & Value Objects:** `EconomicInventoryId`, `CompoundInventoryParticipant`, `InventoryMutationIntent`, `InventoryDelta`.
   - **Tables:** `profile_inventories`, `inventory_mutation_journals`, `inventory_mutation_participants`.
   - **Responsibilities:** Durable write-ahead journal protocol for single-owner and compound multi-owner mutations (Player Profile $\leftrightarrow$ Vessel Cargo), OCC version verification, dual-slot before/after fingerprint checking, crash recovery reconciliation.
   - **Hybrid Durability Model:** Operates under the resolved **`HYBRID`** durability model:
     - Critical/economic item mutations execute immediately through `InventoryMutationJournal`.
     - Non-inventory financial transactions route to SQL OCC / `processed_operations` or external sagas.
     - Routine non-critical ambient inventory/state progress targets a configurable 60s default checkpoint cadence without hardcoded numerical constants.
     - Periodic ambient checkpoints serialize strictly under canonical `player_sessions` row locks (`FOR UPDATE`) with epoch/lease fencing, requiring `state == 'ACTIVE'` (rejects/aborts without canonical write if `state != 'ACTIVE'` e.g. `DRAINING`, without clearing dirty state).

6. **Island Domain Context (`com.uxplima.uxmskyblock.island`)**
   - **Aggregate Root:** `Island`
   - **Responsibilities:** Island lifecycle (creation, deletion, reset, center coordinates, bounding box/region, biome, spawn point, status, net worth, level score).

7. **Financial & Banking Context (`com.uxplima.uxmskyblock.economy`)**
   - **Aggregate Root:** `IslandBank`
   - **Entities & Value Objects:** `BankTransaction`, `CurrencyAmount` (Exact Minor Units).
   - **Responsibilities:** Island co-op treasury, multi-currency wallets (Primary Currency, Crystals, Island EXP), OCC balance mutations, audit trail logging (`bank_transactions`).

8. **Vault & Shared Container Context (`com.uxplima.uxmskyblock.vault`)**
   - **Aggregate Root:** `IslandVaultPage`
   - **Entities & Value Objects:** `VaultEditSession`, `EscrowJournal`.
   - **Responsibilities:** Paged co-op vaults, write-ahead escrow journal for item transfers, session lease serialization, fail-closed conditional rollback on crash.

9. **Protection & Compiled Permission Context (`com.uxplima.uxmskyblock.protection`)**
   - **Entities & Value Objects:** `PermissionKey`, `PermissionRegistry`, `PermissionId`, `PermissionSet`, `CompiledRolePolicy`, `IslandPermissionOverride`, `IslandFlags`.
   - **Responsibilities:** Spatial boundary enforcement, visitor policies, and high-performance permission evaluation.
   - **Compiled Namespaced Permission Architecture:**
     - `PermissionKey`: Stable namespaced string identifier (e.g. `uxm:block.break`, `uxm:container.open`, `uxm:member.invite`, `thirdparty:machine.use`). Used exclusively for configuration, public API, and persistence.
     - `PermissionRegistry`: Startup/lifecycle compilation resolving registered keys into dense integer `PermissionId`s (0..N). Dense integers are runtime optimizations only and NEVER persisted.
     - `PermissionSet`: Compact bitset representation allowing hot-path bit-testing.
     - `CompiledRolePolicy`: Role-to-PermissionSet precompiled mapping.
     - Registration Lifecycle: Registration Phase $\to$ Validation (collisions fail deterministically) $\to$ Compiled Snapshot $\to$ Controlled Rebuild.
     - **Hot-Path Execution Guarantee:** Once subject/role/context resolution is complete, compiled permission evaluation uses dense `PermissionId` + `PermissionSet` bit lookup without per-check configuration parsing or namespaced-key map traversal. Context resolution itself legitimately utilizes indexed/cached lookups.
   - **Visitor Policy Extensions:** Expresses interaction permissions, command restrictions, PvP/damage policies, item pickup/drop, portals, mob interactions, and temporary trust overrides through compiled permission sets rather than ad-hoc booleans.

10. **Progression & Generic Leaderboard Context (`com.uxplima.uxmskyblock.leaderboard`)**
    - **Aggregate Root:** `IslandLevel`
    - **Entities & Value Objects:** `LeaderboardMetricId`, `LeaderboardMetricProvider`, `LeaderboardEntry`.
    - **Responsibilities:** Block weight scoring, asynchronous chunk scanning, progression milestones.
    - **Generic Leaderboard Metric Providers & Authoritative Read Contracts:**
      - Open metrics via `LeaderboardMetricProvider`: `uxm:level`, `uxm:worth`, `uxm:bank_balance`, `uxm:rating`, `uxm:mission_points`, `uxm:unique_visitors`, `season:score`, `thirdparty:custom`.
      - Strongly typed values (never forced to floating point), explicit sort directions, and consistency classifications (`EVENT_DRIVEN_EXACT`, `PERIODIC_ASYNC_SCAN`, `BATCH_SCHEDULED`).
      - **Source-of-Truth Invariant:** The Leaderboard subsystem does NOT own the canonical value of every metric merely because it ranks it. `LeaderboardMetricProvider` exposes the authoritative source/read contract (e.g. Level context owns `uxm:level`, Economy context owns `uxm:bank_balance`, Social context owns `uxm:rating`, registering plugin owns `thirdparty:factory_output`).
      - Leaderboard SQL materializations, Redis Sorted Sets, and local caches are non-canonical projections/indexes unless the owning bounded context defines otherwise. Historical leaderboard snapshots are durable historical records without becoming live metric truth.

11. **Disaster Recovery, BackupSet & General Object Storage Context (`com.uxplima.uxmskyblock.restore`, `com.uxplima.uxmskyblock.storage.object`)**
    - **Aggregate Roots & Domain Concepts:** `BackupSet`, `RestoreOperation`.
    - **Operational Metadata vs Immutable Manifest vs Publication Marker:**
      - `BackupOperation` / `BackupCatalogRecord`: Operational metadata stored in canonical SQL persistence (`backup_operations` / backup catalog table). Owns the mutable lifecycle state machine (`PLANNED`, `CAPTURING`, `STAGED`, `UPLOADING`, `VERIFYING`, `AVAILABLE`, `FAILED`, `PARTIAL`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`).
      - `BackupManifest`: Immutable, self-contained disaster-recovery document published/finalized only upon a captured backup generation and stored alongside artifacts as `manifest.json`. Contains stable facts (`backupSetId`, `backupType`, `subject/root identity` if applicable, `capture timestamp`, `authority/version evidence`, `schema versions`, `plugin/GameMode versions`, strong SHA-256 artifact checksums, compression, encryption metadata, and `consistencyResult`). Continuous mutable operation-state fields (`status = UPLOADING`) are strictly prohibited in the manifest.
      - `BackupPublicationMarker` (`AVAILABLE.marker` / typed discovery marker): A dedicated discovery marker proving that this backup generation was fully published, integrity-verified, and remains an eligible restore candidate. Published LAST in storage; removed/tombstoned FIRST during deletion.
      - **Disaster Recovery Discovery Without Live Database:** A backup in object storage is recognized as an `AVAILABLE` restore candidate if and only if: (1) immutable `manifest.json` exists; (2) `AVAILABLE.marker` exists; (3) manifest/artifact validation succeeds. Even if SQL is lost mid-deletion, the missing/tombstoned marker prevents partially deleted backups from ever being restored.
    - **Driven Infrastructure Ports (defined in `:core` application boundary):**
      - `ObjectStoragePort`: Platform-neutral object storage outbound port defined in `:core`. Consumed by Application Use Cases (`BackupApplicationService`, `SnapshotRestoreService`, etc.), NOT directly invoked by pure domain entities. Reusably serves world snapshots, database backup exports, reports, and durable binary artifacts.
      - `DatabaseBackupPort`: Database-wide disaster backup outbound port defined in `:core` application boundary, implemented by `:persistence-adapter` or dedicated database-backup adapter. Decoupled from concrete database CLI utilities; enforces a dialect-correct consistent export and restore validation semantic contract.
      - **Root Snapshot Hexagonal Adapter Ownership:**
        - `RootRelationalSnapshotPort`: Outbound port in `:core`, implemented by `:persistence-adapter` for root-scoped relational data (island level, custom name, flags, permissions, upgrades). Pure relational persistence without Minecraft world or Bukkit dependencies.
        - `WorldDimensionSnapshotPort`: Outbound port in `:core`, implemented by `:bukkit-adapter` (or dedicated world snapshot adapter) for world/dimension chunk extraction and Folia thread coordination.
        - `RootStateSnapshotPort`: Coarse application-level coordinator façade in `:core` that composes `RootRelationalSnapshotPort` and `WorldDimensionSnapshotPort`.
        - *Strict Invariant:* Database persistence adapter (`:persistence-adapter`) $\ne$ Minecraft world snapshot adapter (`:bukkit-adapter`). The persistence adapter MUST NEVER depend on Bukkit, Paper, or world classes.
    - **ObjectStoragePort Capabilities & Discovery (`ObjectStorageCapabilities`):**
      - Operations: put/upload, get/download, streaming upload/download, head/metadata lookup, existence lookup, delete, list with pagination tokens, range reads, multipart upload/abort, server-side copy, and conditional operations.
      - Typed capability discovery: `MULTIPART_UPLOAD`, `RANGE_READ`, `SERVER_SIDE_COPY`, `CONDITIONAL_WRITE`, `PRESIGNED_URL`, `NATIVE_OBJECT_VERSIONING`, `OBJECT_LOCK`, `PROVIDER_LIFECYCLE_RULES`, `CHECKSUM_ALGORITHMS`.
      - Fail-Closed Validation: If a configured provider lacks a required capability for an active use case, startup validation fails clearly and closes.
    - **Target Adapters & Real Provider Verification Boundary:**
      - *V1 Required Verified-Compatibility Targets:* Amazon AWS S3 and Cloudflare R2.
      - *Current Implementation Status:* NOT YET IMPLEMENTED / NOT YET COMPATIBILITY-VERIFIED (zero production Java or test implementation yet written).
      - *Canonical Verification Distinction:*
        - Generic S3 Adapter Tests: Local emulators / compatible test servers MAY be used to test generic S3 adapter behavior.
        - AWS S3 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderAwsCompatibilityContractTest`) MUST pass against a real AWS S3 endpoint/account.
        - Cloudflare R2 Verified Compatibility: Provider-specific integration suite (`S3CompatibleProviderR2CompatibilityContractTest`) MUST pass against a real Cloudflare R2 endpoint/account.
        - *Emulator Rule:* Passing against an emulator can NEVER upgrade AWS S3 or Cloudflare R2 to VERIFIED status.
      - *General Integration Scope:* Provides reusable object-storage semantics and capability discovery; does not claim full AWS SDK feature coverage, complete AWS S3 API coverage, or complete R2 parity with AWS. Commercial pricing properties are non-architectural and excluded.
      - `LocalFilesystemStorageAdapter`: Structured local directory hierarchy with atomic move or manifest commit marker.
      - `S3CompatibleObjectStorageAdapter`: S3 API client supporting endpoint, region, bucket, credential references, path prefixes, addressing modes (`PATH_STYLE` vs `VIRTUAL_HOSTED`), timeout/retry policy, bounded streaming, multipart upload, and checksum policy.
      - *Generic S3-Compatible Endpoints:* Supported and configurable, with capability negotiation distinguishing verified targets from generic endpoints.
    - **Storage Topology Policy:** Configurable as `LOCAL`, `REMOTE`, or `MIRRORED` (requires both local and remote completion before marking `AVAILABLE`).
    - **Backup Classification & Restore Boundaries:**
      - `ROOT_BACKUP`: Root-scoped durable state and world snapshot targeting one stable gameplay root (`PrimaryGameplayRootRef`). Restoring a root backup restores only that root and its declared dependencies; never triggers a full database restore.
      - `DATABASE_DISASTER_BACKUP`: Whole-database disaster recovery via `DatabaseBackupPort`. Restoring a database disaster backup is a catastrophic, full-database disaster recovery workflow. A whole database is NEVER restored as an implicit side effect of an individual root rollback.
    - **Orthogonal Consistency Taxonomy:**
      - *Backup Lifecycle / Operational State:* `PLANNED`, `CAPTURING`, `STAGED`, `UPLOADING`, `VERIFYING`, `AVAILABLE`, `PARTIAL`, `FAILED`, `RECOVERY_REQUIRED`, `DELETING`, `DELETED`.
      - *Capture Coordination Mechanism / Evidence:* `QUIESCED` (capture performed during a bounded mutation-quiesce window, without unverified numeric latency constants), `VERSION_FENCED`, `TRANSACTION_SNAPSHOT`, `ROOT_MUTATION_FENCE`.
      - *Consistency Guarantee:* `FULL_RESTORE_CONSISTENT`, `ROOT_CONSISTENT`, `BEST_EFFORT / PARTIAL_NOT_RESTORABLE` (partial captures are never advertised as restorable unless an explicit restore mode supports that partial artifact set).
    - **Publication & Recoverable Deletion Ordering (Marker / Tombstone Protocol):**
      - *Publication Ordering:* (1) Capture artifacts $\to$ (2) Upload artifacts $\to$ (3) Publish immutable manifest $\to$ (4) Verify all required artifacts/checksums $\to$ (5) Publish `AVAILABLE.marker` LAST $\to$ (6) SQL `BackupCatalogRecord` transitions to `AVAILABLE`.
      - *Deletion Ordering:* (1) SQL `BackupCatalogRecord` transitions to `DELETING` $\to$ (2) Remove/invalidate `AVAILABLE.marker` FIRST (or publish explicit deletion tombstone) $\to$ (3) Perform per-destination idempotent artifact cleanup $\to$ (4) Remove manifest when destination policy allows $\to$ (5) SQL transitions to `DELETED` only when configured deletion policy is satisfied across all destinations.
      - *No Cross-Destination Atomicity:* No distributed ACID transaction spans local filesystems, AWS S3, Cloudflare R2, and generic object stores. In `MIRRORED` backups, each destination tracks its marker publication and cleanup independently; partial destination failures persist progress and safely retry.
    - **Safety-Critical Fail-Closed Restore Pipeline:**
      - Sequence: locate BackupSet $\to$ verify manifest $\to$ verify compatibility $\to$ verify/download artifacts $\to$ verify checksums $\to$ fence target authority $\to$ quarantine state $\to$ restore durable state/world artifacts $\to$ reconciliation $\to$ commit version/authority $\to$ return to active.
      - Strict Fail-Closed Rule: Missing artifacts, missing availability marker, checksum mismatch, version incompatibility, authority changes, or missing provider capabilities abort restore immediately; partial destructive restore is strictly forbidden.
    - **Credential Security:** Credentials (access keys, secrets, tokens) are NEVER stored in domain models, manifests, database rows, logs, or diagnostic dumps. Logging redacts all secrets.
    - **Large Object Streaming:** Bounded streaming buffers and multipart uploads prevent multi-gigabyte `byte[]` heap exhaustion.
    - **Retention Policy (`RetentionPolicy`):** Evaluated strictly on complete `BackupSet`s (`keep-last-n`, `age-based`, `scheduled-generations`, `protected-pins`).

12. **Durable Reward & Inbox Context (`com.uxplima.uxmskyblock.reward`)**
    - **Aggregate Root:** `RewardGrant`
    - **Entities & Value Objects:** `RewardGrantComponent`, `RewardPayload`, `RewardSource` (missions, challenges, seasons, admin compensation, events, votes, progression, social).
    - **Responsibilities:** Durable decoupled reward distribution. Separates "reward earned" from "physical inventory insertion".
    - **Identity Hierarchy & Isolation Invariant:**
      - `RewardGrantId`: Parent aggregate / correlation identity for the overall reward package.
      - `RewardComponentOperationId`: Durable idempotency identity of one component delivery.
      - *Isolation Invariant:* Different reward components MUST NOT blindly reuse the parent grant ID across `InventoryMutationJournal`, `processed_operations`, SQL economic OCC, or external economy sagas.
      - *Component Operation Stability:* Each component receives one stable operation ID (`componentOperationId`) identical across retries of that component, deterministically derived from `grantId + componentIndex` (or explicitly persisted).
      - Invariant: `same grant + same component` $\implies$ `same component operation ID`; `different components` $\implies$ `different component operation IDs`.
      - Relational uniqueness: `UNIQUE (grant_id, component_index)` and `UNIQUE (component_operation_id)`.
      - Protocol operation references (`journal_operation_id` or equivalent) MUST represent `component_operation_id`, not ambiguously the parent grant ID.
    - **Lifecycle:** States `PENDING` $\to$ `CLAIMING` $\to$ `CLAIMED` (or `EXPIRED`, `RECOVERY_REQUIRED`). The grant transitions to `CLAIMED` only after all required components are durably completed.
    - **Delivery Protocol Orchestration (`RewardClaimCoordinator`):** Component delivery reuses the appropriate already-approved protocol for each component type, keyed by `component_operation_id`:
      - Minecraft / durable economic inventory items $\longrightarrow$ `InventoryMutationJournal` (expected OCC version, authority fencing, before/after fingerprints).
      - SQL-owned currency / bank balance $\longrightarrow$ canonical economic OCC + operation-id contract (`processed_operations`, `bank_transactions`).
      - External Vault / external economy side effects $\longrightarrow$ saga / idempotency / recovery contract.
      - Notification / cosmetic / non-economic rewards $\longrightarrow$ owning bounded-context protocol.
    - Per-component claim progress is persisted in `reward_grant_components` to safely recover partial crashes without duplicate reward distribution.

13. **Placement & Routing Context (`com.uxplima.uxmskyblock.placement`)**
    - **Entities & Value Objects:** `PlacementDecision`, `PlacementIntent`, `RoutingState`.
    - **Responsibilities:** Instance allocation, node load balancing, and proxy routing dispatch.
    - **Strict Three-Way Separation of Concerns:**
      1. **AuthorityState:** Canonical SQL ownership (`authoritative_node`, `authority_epoch`, `lease_expires_at`, transactional fencing tokens).
      2. **RoutingState:** Where traffic should currently be routed (`HOSTED`, `MOVING`, `UNHOSTED`). Ephemeral projection in Redis/memory.
      3. **PlacementDecision:** Where an unloaded or newly created instance SHOULD be hosted via pluggable `PlacementStrategy` (`ROUND_ROBIN`, `LEAST_LOADED`, `MSPT_AWARE`, `CAPACITY_WEIGHTED`).
    - *Critical Invariant:* Placement recommendations do NOT grant authority; routing caches do NOT grant authority; Redis state NEVER supersedes SQL fencing.

14. **Activity Feed & Offline Notification Context (`com.uxplima.uxmskyblock.notification`)**
    - **Entities & Value Objects:** `ActivityEvent`, `Notification`, `NotificationCategory`.
    - **Responsibilities:**
      - **Player-Facing Activity Feed:** User-facing projection digest (member joins/leaves, role changes, bank transactions, upgrades, boosters, warps). Strictly separated from the immutable security/compliance audit log.
      - **Durable Event Outbox Delivery:** Projections are fed via the approved transactional outbox (`outbox_events`):
        $$\text{Domain Commit} \longrightarrow \text{Durable Outbox Event} \longrightarrow \text{Retryable Activity Projection / Notification Creation}$$
        Projection failures do not roll back business state AND do not lose feed events or notifications.
      - **Durable Offline Notification Inbox:** Profile-scoped durable notifications (invites, kicks, role updates, trust changes, reward alerts, bank activities). Relational SQL is canonical; Redis Pub/Sub accelerates online real-time delivery only. Presentation belongs exclusively in platform UI adapters.
      - **Typed & Versioned Payload Contract:** Extensible payloads require `payload_type_id`, `payload_schema_version`, and `payload_data`. Unknown/newer versions remain preserved without unsafe mutation.

15. **Social & Discovery Context (`com.uxplima.uxmskyblock.social`)**
    - **Entities & Value Objects:** `SocialSubjectRef`, `SocialRating`, `GuestbookEntry`, `SubjectBookmark`, `PublicWarpDirectory`.
    - **Generic Subject Reference:** Operates on `SocialSubjectRef(subjectTypeId, subjectKey)` across generic roots (Islands, Parkour Courses, Creative Areas, TradeWinds ports). Does not assume social subject == `GameModeInstance`.
    - **Neutral Rating Policy:** Governed by `RatingPolicy` / `RatingAggregationPolicy` (concrete scoring scales and aggregation formulas are configurable and decoupled from core domain contracts).
    - **Features:** Ratings, interactive guestbooks/reviews, unique visitor counters, favorites/bookmarks, categorized public directory, and discovery sorting/filtering. Explicit privacy, visibility, and moderation policies.

16. **Lifecycle & Reset Policy Context (`com.uxplima.uxmskyblock.lifecycle`)**
    - **Entities & Value Objects:** `LifecycleTransition`, `LifecyclePolicy`, `ResetAllowance`.
    - **Responsibilities:** Deterministic orchestration of player and instance lifecycle transitions (`PROFILE_JOINS_INSTANCE`, `PROFILE_LEAVES_INSTANCE`, `PROFILE_KICKED`, `INSTANCE_RESET`, `INSTANCE_DISBAND`, `PROFILE_DEATH`, `INSTANCE_CREATED`).
    - **Policy Composition:** Ruleset $\times$ GameMode composition explicitly defining policy actions across inventories, ender chests, currencies, XP, health/hunger, locations, progression states, and cooldowns. Hardcore lifecycle behavior is supplied by the existing Hardcore Ruleset policy without inventing unapproved death outcomes in this pass.

17. **Multiple Homes Context (`com.uxplima.uxmskyblock.home`)**
    - **Aggregate Root:** `Home`
    - **Entities & Value Objects:** `HomeId`, `GamePosition`, `HomeScope` (`PERSONAL`, `CO_OP`), `HomeLimitPolicy`.
    - **Stable World Identity:** Canonical home location references `instance_id`, `dimension_instance_id` (stable `WorldRef`), `position`, and `rotation`. Platform `world_name` resolution is delegated to platform storage adapters; display names are non-authoritative metadata. Governed by `HomeLimitPolicy` (dimension-aware, role/permission restricted, GameMode capability-gated).

---

## 3. Physical Module Topology & Platform Neutrality

The repository build layout is partitioned into distinct Gradle submodules to enforce architectural boundaries in lockstep with `uxm-essentials`:

```
uxmSkyblock (Root)
│
├── :api                     [CONTRACT DEFINED — SCAFFOLDED] Zero Minecraft / Platform Dependencies
│   └── Public published external developer API: interfaces, records, events, value objects.
│
├── :core                    [CONTRACT DEFINED — SCAFFOLDED] Pure Java Core Business Logic & Use Cases
│   └── Pure DDD aggregates, domain entities, use cases, internal driven ports (SchedulerPort, repositories).
│
├── :persistence-adapter     [CONTRACT DEFINED — SCAFFOLDED] Database & Storage Outbound Adapter
│   └── Implements persistence ports via uxmlib-storage (Database, Sql, TxSql, MigrationRunner, runtime JDBC).
│
├── :bukkit-adapter          [CONTRACT DEFINED — SCAFFOLDED] Platform Implementation Shell
│   └── Implements SchedulerPort via uxmlib-common, Paper/Folia listeners, Brigadier commands, plugin bootstrap.
│
├── :bukkit-api              [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
│   └── Optional legacy Bukkit-facing API shell for third-party Bukkit plugins.
│
├── :rest-adapter            [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
│   └── External administrative HTTP / REST adapter with idempotency enforcement.
│
├── :redis-adapter           [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
│   └── Distributed messaging and cross-node cache synchronization adapter.
│
└── :velocity-adapter        [PLANNED — NOT IMPLEMENTED IN CURRENT PHASE]
    └── Optional proxy-level routing / party sync adapter.
```

### 3.1 Strict Platform & Module Boundary Rules
- **`:api` Module (Public External Developer API):**
  - Separately published, supported external developer API.
  - Contains **zero** platform dependencies (`org.bukkit.*`, `io.papermc.*`) and zero internal implementation details.
  - Exposes only intentional stable extension points, service interfaces, events, and value objects (e.g. `IslandApi`, `ProfileApi`, `GameModeApi`, `NamespacedId`, `WorldPosition`).
  - **Internal application ports (e.g. `SchedulerPort`, persistence repositories, transaction coordinators, outbox publishers) MUST NOT be exposed in `:api`** merely because they are interfaces; they remain internal to `:core`.
- **`:core` Module (Application Domain, Use Cases & Internal Driven Ports):**
  - Implements application use cases and business domain logic in pure Java.
  - Owns domain aggregates, entities, and internal application ports (`SchedulerPort`, `IslandRepository`, `ProfileRepository`, `InventoryMutationJournalPort`, `TransactionCoordinator`).
  - Has zero dependencies on Bukkit, JDBC, Redis, Configurate, or concrete adapter modules.
- **`:persistence-adapter` Module:**
  - Implements internal persistence outbound ports defined by `:core`.
  - Consumes `uxmlib-storage` (`Database`, `Sql`, `TxSql`, `MigrationRunner`, and storage caches).
  - Owns relational database schema mapping, SQL dialect adaptations, connection pooling, and declares runtime JDBC drivers (`mariadb-java-client`, `postgresql`).
- **`:bukkit-adapter` Module:**
  - Platform shell permitted to import `org.bukkit.*` and `io.papermc.paper.*`.
  - Implements internal application ports such as `SchedulerPort` using `uxmlib-common`'s Folia `Scheduler`.
  - Translates Bukkit events into platform-neutral domain commands.
  - Assembles application wiring and lifecycle bootstrap.
- **ArchUnit Enforcement Status:**
  - ArchUnit architecture tests are a mandatory requirement during implementation to enforce these module boundaries and prevent threading drift.
  - **Current factual repository status:** ArchUnit enforcement implementation is `NOT YET WRITTEN` (scaffolded dependencies configured; tests will be written during Phase 1 in accordance with governance).

---

## 4. Dependency Ownership Policy (`uxm-lib` First)

In accordance with UXPLIMA architectural standards, foundational infrastructure is centrally provided by [`uxm-lib`](https://github.com/UXPLIMA/uxm-lib):

$$\text{uxmSkyblock} \longrightarrow \text{uxm-lib Public Modules} \longrightarrow \text{Third-Party Implementations}$$

### 4.1 Verified Upstream `uxm-lib` Capability Matrix
* **Storage & Migrations (`uxmlib-storage`):**
  - HikariCP-backed `Database` connection management.
  - `Sql` and `TxSql` fluent query abstractions.
  - Native migration execution via `MigrationRunner`. **Flyway is NOT used.**
  - Caffeine-backed cache utilities (`Cache`, `CachedStorage`, `PlayerProfileCache`) live in `uxmlib-storage`.
  - Bundles `sqlite-jdbc` as transitive API dependency; requires consumer-provided `runtimeOnly` drivers for MariaDB/PostgreSQL.
* **Configuration & Scheduling (`uxmlib-common`):**
  - Provides `HoconConfig`, `RecordConfig`, `ConfigProperty`, and scheduler/text/config primitives.
  - Does NOT provide YAML parsing or a `ConfigManager` god-class.
  - `Scheduler` abstraction operates on Bukkit types and is consumed exclusively within `:bukkit-adapter`.
* **Redis Pub/Sub Bus (`uxmlib-redis`):**
  - Provides low-level binary `byte[]` Redis Pub/Sub messaging (`LettuceRedisBus`, `RedisBus`).
  - Does NOT provide Redis Streams transport, generic L2 KV/TTL caching, or Sorted Sets.
* **Integrations (`uxmlib-integration`):**
  - Provides abstractions for Vault economy, PlaceholderAPI expansion, and permission systems.
* **Bedrock & GUI (`uxmlib-bedrock`, `uxmlib-menu`, `uxmlib-gui`):**
  - Provides Floodgate form abstractions and inventory menu builders.

### 4.2 Formal uxm-lib Capability Gaps & Blocking Classification
1. **`UXM-LIB CAPABILITY GAP — DURABLE REDIS STREAMS`:**
   - **Why Skyblock Requires It:** Cross-node transactional outbox event streaming and reliable consumer-group fan-out for multi-server Folia clusters.
   - **V1 Implementation Requirement:** Required for multi-node cluster deployment; non-blocking for single-node local testing.
   - **Upstream Extension Required:** Yes, before cluster deployment. Direct Lettuce Streams in Skyblock is prohibited.
   - **Fallback:** Local transactional SQL polling on `outbox_events` table (`WHERE published_at IS NULL ORDER BY event_seq`).
   - **Classification:** `BLOCKING BEFORE PHASE 1 CLUSTER IMPLEMENTATION` (Non-blocking for single-node development).

2. **`UXM-LIB CAPABILITY GAP — REDIS KV/TTL ROUTING DIRECTORY`:**
   - **Why Skyblock Requires It:** Low-latency proxy-to-server routing directory (`uxmskyblock:route:island:<id>`) with TTL cache eviction.
   - **V1 Implementation Requirement:** Required before multi-node proxy forwarding is enabled.
   - **Upstream Extension Required:** Yes, before multi-node proxy routing.
   - **Fallback:** Authoritative SQL queries against canonical `island_authorities` and `player_sessions` tables.
   - **Classification:** `BLOCKING BEFORE MULTI-NODE PROXY ROUTING` (Non-blocking for single-node development).

3. **`UXM-LIB CAPABILITY GAP — REDIS SORTED-SET LEADERBOARD`:**
   - **Why Skyblock Requires It:** Real-time $O(\log N)$ leaderboard ranking (`ZADD`, `ZREVRANGE`) across high-frequency island level updates.
   - **V1 Implementation Requirement:** Optional performance optimization; canonical SQL is fully functional.
   - **Upstream Extension Required:** Yes, before Redis-backed leaderboards are implemented.
   - **Fallback:** Canonical relational SQL index scan (`SELECT island_id FROM islands ORDER BY level_score DESC LIMIT :k`) with local Caffeine caching in `:persistence-adapter`.
   - **Classification:** `OPTIONAL / NON-BLOCKING` (Feature-specific optimization).

4. **`UXM-LIB CAPABILITY GAP — METRICS`:**
   - **Why Skyblock Requires It:** Plugin telemetry, active player/island metrics, and performance counters.
   - **V1 Implementation Requirement:** Optional observability.
   - **Upstream Extension Required:** Centralized metrics module in `uxm-lib` or explicit Product Owner approval. Direct `bStats` dependency is removed.
   - **Fallback:** SLF4J structured audit logging and JVM standard MBeans.
   - **Classification:** `OPTIONAL / NON-BLOCKING`.

5. **`UXM-LIB CAPABILITY GAP — GENERAL OBJECT STORAGE / S3-COMPATIBLE STORAGE`:**
   - **Why Skyblock Requires It:** General object storage abstraction for world snapshots, database backup exports, reports, and durable binary artifacts with S3-compatible integration (AWS S3 & Cloudflare R2).
   - **V1 Implementation Requirement:** Mandatory for V1 completion.
   - **Upstream Extension Required:** Centralized object-storage capability module in `uxm-lib` or explicit Product Owner library exception. Direct AWS SDK / HTTP client coupling in pure `:core` is strictly prohibited.
   - **Fallback:** Local filesystem storage adapter (`LocalFilesystemStorageAdapter`).
   - **Classification:** `BLOCKING BEFORE OBJECT-STORAGE IMPLEMENTATION` (ultimately required for V1 completion).
   - **Preserved Dependency Direction:**
     $$\text{Skyblock Application} \longrightarrow \text{ObjectStoragePort} \longleftarrow \text{Skyblock Object Storage Adapter} \longrightarrow \text{uxm-lib Object Storage Capability} \longrightarrow \text{S3-Compatible Client}$$

---

## 5. Modularity & Anti-God-Class Architecture

### 5.1 Class Size & Complexity Guidelines
- **Target Size:** Classes stay within **300 to 400 lines of code**.
- **Maximum Ceiling:** 500 lines for cohesive, highly encapsulated domain entities.
- **Strict Prohibition:** Monolithic God-classes (1,000+ lines) are strictly prohibited. Every class adheres to the Single Responsibility Principle (SRP).

### 5.2 Context Wiring Pattern
Each bounded context provides a dedicated, self-contained wiring coordinator:
- `GameModeWiring`, `ProfileWiring`, `ParticipationWiring`, `AuthorityWiring`
- `InventoryWiring`, `IslandWiring`, `EconomyWiring`, `VaultWiring`
- `ProtectionWiring`, `LevelWiring`, `RestoreWiring`, `RewardWiring`
- `PlacementWiring`, `NotificationWiring`, `SocialWiring`, `LifecycleWiring`, `HomeWiring`

A top-level bootstrap coordinator (`SkyblockBootstrap`) in `:bukkit-adapter` merely coordinates the sequential startup and shutdown of the context wiring modules.

### 5.3 Public Extension SPIs & Capability Contracts
To allow extensible ecosystem contributions without modifying `:core` or converting internal strategies into public API mechanically:
* **Placement of Extension Seams:**
  - **Public Extension SPIs:** Defined in `:api` only where a genuine third-party extension seam exists.
  - **Internal Driven Ports:** Remain strictly internal to `:core` (e.g. `SchedulerPort`, repository ports, outbox publishers).
* **Approved Candidate Public Provider SPIs:**
  1. `PermissionDefinitionProvider`: Enables third-party plugins to contribute namespaced `PermissionKey`s during the permission registration lifecycle.
  2. `CreationActionProvider`: Enables custom archetype/island creation actions (e.g. schematic pasting, custom entity spawning) without hardcoded command strings.
  3. `LeaderboardMetricProvider`: Supplies dynamic, non-island-level scoring metrics with explicit sort direction and consistency tiers.
  4. `PlacementStrategyProvider`: Contributes node allocation heuristics (`ROUND_ROBIN`, `LEAST_LOADED`, `MSPT_AWARE`, `CAPACITY_WEIGHTED`).
  5. `SocialCapabilityProvider`: Supplies domain hooks for ratings, guestbooks, and discovery engines across generic gameplay roots.
  6. `OfflineProgressionProvider`: Contributes deterministic offline catch-up calculation for custom game mechanics without loading worlds.
* **Provider Lifecycle & Governance:**
  - Registered providers must declare explicit capability metadata (`requires`, `optional`, `provides`, and capability version).
  - Provider registration collisions fail fast and deterministically during bootstrap.
