# Project Task & Milestone Board (Kanban)

> **Policy:** This board must be kept up-to-date with every pull request, milestone, and development cycle as mandated in [PROJECT_RULES.md](PROJECT_RULES.md).
> An interactive visual version of this board is available at [`project-board.html`](../project-board.html).

---

## Current Sprint Overview

* **Phase:** Phase 3 — V1 Complete Production Execution (P0 Correctness & P1 Advanced Subsystems)
* **Architecture Board State:** FROZEN (Architecture Frozen by Product Owner: YES — 2026-09-13)
* **Implementation Started:** YES
* **Phase 1 Started:** YES (Phase 1 COMPLETE — 7 / 7 completed)
* **Phase 2 Started:** YES (Phase 2 COMPLETE — 38 / 38 completed)
* **Phase 3 Started:** YES (Active P0 Correctness & P1 Advanced Subsystems)
* **Current Active Task:** SES-001 (Player Session Authority Acquisition Hardening)
* **Target Release:** v0.1.0-SNAPSHOT (Production V1 Complete)
* **Platform:** Paper 26.2+ & Folia on Java 25+
* **Overall Card Completion:** 61.6% (45 / 73 cards completed)
* **Phase 1 Tasks Completed:** 7 / 7 completed
* **Phase 2 Tasks Completed:** 38 / 38 completed
* **Phase 3 Tasks Active:** 28 cards total (14 P0 Critical Correctness + 14 P1 Advanced Subsystems)

---

## Kanban Columns

### 📋 Backlog (Future Milestones & P1 Advanced Subsystems)
* [ ] **[MOD-001]** Dynamic FeatureModule Architecture (`FeatureModule`, `ModuleRegistry`, `modules.conf` per Section 2.25)
* [ ] **[SEA-001]** Automated Seasons & Competitive Payout Subsystem (`IslandSeasonService`, `seasons.conf`, V13 migration per Section 2.33)
* [ ] **[SOC-001]** Social Discovery, Ratings & Guestbook Subsystem (`IslandSocialService`, `social.conf`, V14 migration per Section 2.35)
* [ ] **[DIS-001]** Standalone Discord Webhook Notification Subsystem (`IslandDiscordWebhookService`, `discord.conf` per Section 2.36)
* [ ] **[ALN-001]** Island Alliances & Diplomatic Relations Subsystem (`IslandAllianceService`, `alliances.conf`, V15 migration per Section 2.28)
* [ ] **[SHO-001]** Dynamic Shop Pricing Subsystem (`DynamicPricingEngine`, `shop.conf` per Section 2.42.8)
* [ ] **[ACC-001]** TemporaryAccessGrant Subsystem (Expiring visitor/coop grants per Section 2.42)
* [ ] **[RWD-001]** Durable Offline Reward Inbox (`RewardInboxService`, V16 migration per Section 2.42)
* [ ] **[BAK-002]** General Object Storage: S3/R2 Remote Storage Adapter (`S3ObjectStorageAdapter` per Section 2.29)
* [ ] **[WAR-001]** Public Warps & Safe Teleport Engine (`IslandWarpService`, V17 migration per Section 2.10)
* [ ] **[VLT-001]** Shared Island Vault & Paged Inventory Subsystem (`IslandVaultService`, V18 migration per Section 2.20)
* [ ] **[CHT-001]** Island Private Chat Subsystem (`IslandChatService` per Section 2.24)
* [ ] **[INA-001]** Leader Inactivity & Succession Lifecycle (`IslandInactivityService` per Section 2.22)
* [ ] **[FRZ-001]** 4-Dimensional Administrative Quarantine & Freeze Subsystem (`IslandAdminFreezeService` per Section 2.40)

### 📌 To Do (Upcoming P0 Correctness Sprints)
* [ ] **[SES-002]** Runtime Self-Fencing & Fail-Closed Guard (Session lease loss fails closed, disconnects player, cancels timers)
* [ ] **[PRF-001]** Canonical Active Profile Context Resolution (Eliminate ad-hoc `ProfileId(player.getUniqueId())` across commands, menus, PAPI, API)
* [ ] **[PRF-002]** Profile Switch Version & Full State Restore + Startup Recovery (Inventory version, enderchest, effects, stats, crash recovery)
* [ ] **[SES-003]** Clean Quit / Offline / Graceful Shutdown Sequence (DRAINING -> final flush -> OFFLINE release)
* [ ] **[EVT-002]** Outbox Fail-Closed on Zero Consumers & Atomic Event Staging in Mutations (Never complete claims when zero consumers present)
* [ ] **[EVT-003]** Durable Event Transport & Redis Streams Adapter (Redis Streams transport for cross-server event fanout and inbox deduplication)
* [ ] **[SCH-001]** Folia-Safe Schematic Pasting on Region Scheduler & Safe Teleport (Dispatch chunk pasting to Folia region thread for target island coordinates)
* [ ] **[PRT-001]** Spatial Island Protection Indexing & Startup Persistence (Protection listener loads persisted islands on cold-start)
* [ ] **[SEC-001]** Compiled Namespaced Permission Architecture (`PermissionKey` -> `PermissionRegistry` -> `PermissionId` -> `PermissionSet` bitmasks)
* [ ] **[PAPI-001]** Zero-DB PlaceholderAPI Render Path (Async background refresh cache for rankings/leaderboards)
* [ ] **[API-001]** Public API Implementation Delegating to Canonical Domain Use Cases (Wire `:api` contracts to real application domain services)
* [ ] **[ECO-001]** Durable Economy Saga & External Vault Bridge (V11 migration `economy_sagas` table, two-phase saga coordinator)
* [ ] **[ISL-002]** Island Creation Idempotency & Database Unique Constraints (V12 migration unique constraints on coords and profile ownership)

### 🔄 In Progress
* [/] **[SES-001]** Player Session Authority Acquisition Hardening (Dual-mode `SELECT ... FOR UPDATE` row locks, monotonic DB-clock takeover, `RECOVERING` state machine transition)

### 🔍 In Review
* *No active items currently in review.*

### ✅ Done (Completed)
* [x] **[ISL-010]** Island Biome System & Modification (`:core` domain `IslandBiome` with displayName/resourceKey/requiredLevel, application port `BiomeModificationPort`, `:bukkit-adapter` asynchronous `BukkitBiomeAdapter` updating chunk biomes, `IslandCommandTree` `/is biome <type>` subcommand, MockBukkit test verification) — COMPLETED
* [x] **[ISL-011]** Custom Island Schematics & Starter Presets (`:core` domain `StarterPreset`, application `StarterPresetCatalog` with Classic, Desert, Nether, Cave presets, `:bukkit-adapter` `StarterSchematicEngine` generating starter island structures, `IslandCommandTree` `/is create [preset]` subcommand, MockBukkit test verification) — COMPLETED
* [x] **[EVT-001]** Distributed Outbox & Event Engine (`:core` domain `EventId`, `OutboxStatus`, `OutboxEventRecord`, `OutboxClaim`, application ports `OutboxPort`, `ConsumerInboxPort`; `:persistence-adapter` canonical SQL `TransactionalOutboxAdapter` with dual-mode transactions, SQLite `BEGIN IMMEDIATE` vs MySQL/PostgreSQL row locking with PostgreSQL `SKIP LOCKED`, batch lease claiming, stale claim completion fencing, and DLQ escalation; `ConsumerInboxAdapter` with dialect-specific idempotent deduplication via `INSERT OR IGNORE` in SQLite, `INSERT IGNORE` in MySQL/MariaDB, and `ON CONFLICT DO NOTHING` in PostgreSQL; V9 migrations with `outbox_events` and `consumer_inbox` tables and composite indexes; fast-lane SQLite and dual-lane Testcontainers MariaDB/PostgreSQL cross-dialect verification) — COMPLETED
* [x] **[BAK-001]** Object Storage & Backup Engine (`:core` domain `StorageBucket`, `StorageObjectMetadata`, `BackupSetId`, `BackupType`, `BackupLifecycleState`, `BackupManifest`, `BackupCatalogRecord`, application ports `ObjectStoragePort`, `BackupCatalogPort`, `DatabaseBackupPort`, `BackupService`; `:persistence-adapter` local filesystem object storage adapter `LocalFilesystemStorageAdapter` with path traversal guards, atomic file writes, and SHA-256 metadata computation; canonical SQL `PlayerBackupCatalogAdapter` with dialect-specific upsert; V8 migrations with `backup_operations` catalog and root/state indexes; fast-lane SQLite and dual-lane Testcontainers MariaDB/PostgreSQL cross-dialect verification) — COMPLETED
* [x] **[LVL-003]** Island Level, Upgrades & Leaderboards (`:core` domain `IslandMaterialIndex` with amortized O(1) dynamic block worth recalculation, `IslandLevelService`, `UpgradeId`, `UpgradeDefinition`, `IslandUpgradeService`, `IslandLeaderboardService`; `:persistence-adapter` canonical SQL `PlayerIslandUpgradeAdapter` and `PlayerIslandLeaderboardAdapter` with atomic upsert; V7 migrations with `island_upgrades` table and worth/bank index scans; dual-lane Testcontainers MariaDB/PostgreSQL and fast-lane SQLite verification) — COMPLETED
* [x] **[BNK-001]** Island Bank & Multi-Currency Economy (`:core` domain `IslandBank`, `BankTransaction`, `BankTransactionOutcome` sealed ADT, application port `IslandBankPort`; `:persistence-adapter` canonical SQL-backed `PlayerIslandBankAdapter` with exact integer minor units, OCC monotonic version CAS, authority row locking on `island_authorities`, scoped idempotency reservation on `processed_operations`, append-only audit trail logging in `bank_transactions`; V6 migrations for `island_banks`, `bank_transactions`, and `processed_operations`; dual-lane Testcontainers MariaDB/PostgreSQL and fast-lane SQLite cross-dialect verification) — COMPLETED
* [x] **[ISL-001]** Island Persistence, Storage Adapter & Authorities (`:core` domain `IslandLocation`, `IslandAuthorityOutcome`, `IslandAuthorityRecord`, application ports `IslandStoragePort`, `IslandAuthorityPort`; `:persistence-adapter` canonical SQL-backed `PlayerIslandStorageAdapter` with dual-mode transactions, bounds/flags/roles/members mapping, distributed authority leasing with DB-clock expiry and fenced takeovers; V5 migrations for `islands`, `island_authorities`, `island_locations`, `island_members`, `island_roles`, `island_role_permissions`, and `island_flags`; dual-lane Testcontainers MariaDB/PostgreSQL and fast-lane SQLite cross-dialect verification) — COMPLETED
* [x] **[WP2-006]** Profile Switch Write-Ahead State Machine (`:core` domain `ProfileSwitchOperation` sealed ADT, `ProfileSwitchState`, `ProfileSwitchPort`, `:persistence-adapter` canonical SQL-backed state machine with `player_sessions` row locking, CAS active switch reservation on `player_accounts`, V4 migrations for `profile_switch_operations`, fast-lane SQLite and dual-lane Testcontainers MariaDB/PostgreSQL cross-dialect verification) — COMPLETED
* [x] **[CORE-001]** Core Domain Records & Value Objects (`:core` domain `Island`, `IslandId`, `IslandMember`, `IslandRole`, `IslandFlags`, `IslandBounds`, `IslandPermission` records, role weight hierarchy, and jqwik property tests) — COMPLETED
* [x] **[TST-001]** Test infrastructure setup (MockBukkit harness in `:bukkit-adapter`, jqwik property testing in `:core`) — COMPLETED
* [x] **[WP2-005]** Immediate Economic Inventory Mutation Journal (`:core` domain/application inventory mutation journal port and outcomes, `:persistence-adapter` canonical SQL-backed two-phase write-ahead journal and participant adapter, V3 migrations for `inventory_mutation_journals` and `inventory_mutation_participants`, SQLite writer serialization via `BEGIN IMMEDIATE`, Testcontainers MariaDB/PostgreSQL `SELECT ... FOR UPDATE` row-lock serialization proof) — COMPLETED
* [x] **[WP2-004]** Handoff Finalization & Durable Inventory Version (`:core` domain/application finalization port, `:persistence-adapter` canonical SQL-backed handoff finalization flush under DRAINING session, atomic synchronization of `profile_inventories.profile_inventory_version` and `player_sessions.last_durable_inventory_version`, crash-window recoverability, SQLite writer serialization via `BEGIN IMMEDIATE`, Testcontainers MariaDB/PostgreSQL `SELECT ... FOR UPDATE` row-lock serialization proof) — COMPLETED
* [x] **[WP2-003]** Authoritative Mutation & OCC Foundation (`:core` domain/application inventory checkpoint port and outcome records, `:persistence-adapter` canonical SQL-backed profile inventory mutation with OCC versioning on `profile_inventories`, session-guarded transactions, DB clock leasing, SQLite writer serialization via `BEGIN IMMEDIATE`, Testcontainers MariaDB/PostgreSQL `SELECT ... FOR UPDATE` row-lock serialization proof) — COMPLETED
* [x] **[WP2-002]** Player Session Authority Lifecycle (`:core` domain/application session authority contracts, `:persistence-adapter` canonical SQL-backed player session fencing lifecycle around `player_sessions`, DB clock leasing, SQLite writer serialization, Testcontainers MariaDB/PostgreSQL cross-dialect verification) — COMPLETED
* [x] **[WP2-001]** Production Persistence Foundation (`:persistence-adapter` production schema V1 migrations for `player_accounts`, `player_profiles`, `player_sessions`, `SkyblockMigrations` registry, SQLite fast lane and Testcontainers MariaDB/PostgreSQL cross-dialect verification) — COMPLETED (commit `fd90b2d`)
* [x] **[P1-007]** Dual-Lane Database Test Foundation (`:persistence-adapter` test infrastructure for SQLite fast lane & Testcontainers MariaDB/PostgreSQL integration lane; portable SQL contract, MigrationRunner parity, `SELECT ... FOR UPDATE` row locking) — COMPLETED
* [x] **[P1-006]** Persistence / MigrationRunner Harness (`:persistence-adapter` integration harness verifying uxmlib-storage MigrationRunner directly) — COMPLETED (commit `5b1550f`)
* [x] **[P1-005]** Configuration Boundary Separation (`:bukkit-adapter` config loader -> pure `:core` policy `PlayerStateDurabilityConfig`) — COMPLETED (commit `f4e8b7e`)
* [x] **[P1-004]** Public API Foundation (`:api` module contracts — `NamespacedId`) — COMPLETED (commit `e8175c5`)
* [x] **[P1-003]** Pure Domain Primitives & Package Foundation (`PlayerUuid`, `ProfileId`, `IslandId`, `Result`, `Unit`) — COMPLETED (commit `da7e626`)
* [x] **[P1-002]** Architecture Dependency Fences (ArchUnit fences for `:api`, `:core`, `:persistence-adapter`, `:bukkit-adapter`) — COMPLETED (commit `1ea566b`)
* [x] **[P1-001]** Build Foundation & Multi-Module Scaffolding (`:api`, `:core`, `:persistence-adapter`, `:bukkit-adapter`) — COMPLETED (commit `906bba0`)
* [x] **[GOV-001]** Comprehensive Project Governance, Coding Standards & Testing Rules (`docs/PROJECT_RULES.md`)
* [x] **[ARCH-002]** Skyblock Core Architecture & Gameplay Specification (`docs/superpowers/specs/2026-09-12-skyblock-core-design.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[ARCH-003]** Persistence & Database Schema Specification (`docs/PERSISTENCE_SPECIFICATION.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[REV-001]** Project Governance Review — Approved & Frozen by Product Owner (2026-09-13)
* [x] **[ARCH-004]** GameMode Architecture Specification (`docs/GAMEMODE_ARCHITECTURE.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[SETUP-001]** Git repository initialization & `.gitignore` configuration
* [x] **[REF-001]** Local clone of `uxm-lib` and `uxm-essentials` reference repositories
* [x] **[REF-002]** Local clone of 7 competitor repositories (`Skyllia`, `SuperiorSkyblock2`, `BentoBox`, `bskyblock`, `IridiumSkyblock`, `NewSky`, `FabledSkyBlock`)
* [x] **[ANA-001]** Exhaustive Competitor Analysis & Architectural Benchmark (`docs/COMPETITOR_ANALYSIS.md`)
* [x] **[BLD-001]** Multi-project Gradle setup (`build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml`)
* [x] **[BLD-002]** `buildSrc` conventions (`uxmskyblock.java-conventions.gradle.kts`, Spotless, Error Prone, NullAway)
* [x] **[DOC-001]** Hexagonal & DDD Architecture Specification (`docs/ARCHITECTURE.md`)
* [x] **[DOC-002]** Coding Standards & Java 25 Quality Mandates (`docs/CODING_STANDARDS.md`)
* [x] **[DOC-003]** Universal English Naming & Method Conventions (`docs/NAMING_AND_METHOD_CONVENTIONS.md`)
* [x] **[DOC-004]** Documentation & "Why, Not What" Commenting Standards (`docs/DOCUMENTATION_AND_COMMENTS.md`)
* [x] **[DOC-005]** Zero-Hardcoding Configuration & i18n Standards (`docs/CONFIGURATION_AND_LOCALIZATION.md`)
* [x] **[DOC-006]** Error Handling & Sealed Results Specification (`docs/ERROR_HANDLING.md`)
* [x] **[DOC-007]** Platform & Folia Native V1 Concurrency Model (`docs/PLATFORM_AND_TECH_STACK.md`)
* [x] **[DOC-008]** Testing Standards & ArchUnit Drift Guards (`docs/TESTING_STANDARDS.md`)
* [x] **[DOC-009]** Project Rules & Definition of Done (`docs/PROJECT_RULES.md`)
* [x] **[DOC-010]** Enterprise Foundation Spec: API, REST, Commands, i18n & Permissions (`docs/ENTERPRISE_FOUNDATION.md`)
* [x] **[DOC-011]** Network & Platform Integration Spec: Multi-Server, Redis, Velocity, Folia & Bedrock (`docs/NETWORK_AND_PLATFORM_INTEGRATION.md`)
* [x] **[DOC-012]** Extreme Scale Architecture Spec: 100,000 Players Optimization (`docs/EXTREME_SCALE_ARCHITECTURE.md`)

---

## Extended Roadmap & Reference Items (Outside Canonical Kanban Board)

The following items represent broader project roadmap tasks, tools, and backlog references that are tracked outside the canonical 35-card sprint Kanban board:

* `UPG-002`: Island upgrades system (crop growth, generator rates, spawner speed)
* `PROT-004`: WorldGuard & Lands soft-dependency adapter bridge
* `NET-001`: Velocity / Redis cross-server island visit bus (`uxmlib-redis`)
* `CORE-002`: Core Domain: `IslandRepository` and `WorldGridPort` interfaces
* `WRD-001`: Spiral grid coordinate allocator & chunk safety engine
* `CMD-001`: Main `/island` command tree via `uxmlib-command`
* `GUI-001`: Main island management menu via `uxmlib-menu` & `uxmlib-bedrock`
* `TOOL-001`: Superpowers Skills Suite installation & integration (`docs/SUPERPOWERS_WORKFLOW.md`)
* `UI-001`: Interactive Project Dashboard & Visual Kanban Board (`project-board.html`)
* `GOV-002`: Project Governance & Coding Standards Review (historical reference alias for `REV-001`)
