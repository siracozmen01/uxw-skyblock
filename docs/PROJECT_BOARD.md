# Project Task & Milestone Board (Kanban)

> **Policy:** This board must be kept up-to-date with every pull request, milestone, and development cycle as mandated in [PROJECT_RULES.md](PROJECT_RULES.md).
> An interactive visual version of this board is available at [`project-board.html`](../project-board.html).

---

## Current Sprint Overview

* **Phase:** Phase 2 — Persistence & Multi-Profile Authority (Phase 1 COMPLETE — WP2-005 COMPLETED)
* **Architecture Board State:** FROZEN (Architecture Frozen by Product Owner: YES — 2026-09-13)
* **Implementation Started:** YES
* **Phase 1 Started:** YES (Phase 1 COMPLETE — 7 / 7 completed)
* **Phase 2 Started:** YES (WP2-005 COMPLETED)
* **P1-001 Status:** COMPLETED (commit `906bba0`)
* **P1-002 Status:** COMPLETED (commit `1ea566b`)
* **P1-003 Status:** COMPLETED (commit `da7e626`)
* **P1-004 Status:** COMPLETED (commit `e8175c5`)
* **P1-005 Status:** COMPLETED (commit `f4e8b7e`)
* **P1-006 Status:** COMPLETED (commit `5b1550f`)
* **P1-007 Status:** COMPLETED (commit `0c681c0`)
* **WP2-001 Status:** COMPLETED (commit `fd90b2d`)
* **WP2-002 Status:** COMPLETED (commit `e458bdd`)
* **WP2-003 Status:** COMPLETED (commit `909c7f4`)
* **WP2-004 Status:** COMPLETED (commit `5e74f76`)
* **WP2-005 Status:** COMPLETED
* **Target Release:** v0.1.0-SNAPSHOT
* **Platform:** Paper 26.2+ & Folia on Java 25+
* **Overall Card Completion:** 88% (35 / 40 cards completed)
* **Phase 1 Tasks Completed:** 7 / 7 completed (P1-001..P1-007 Completed)

---

## Kanban Columns

### 📋 Backlog (Future Milestones)
* [ ] **[ISL-010]** Island biome changer subsystem & GUI
* [ ] **[ISL-011]** Custom Island Schematics & starter presets (Desert, Nether, Classic, Cave)
* [ ] **[LVL-003]** Island level leaderboard caching & top-10 hologram integration

### 📌 To Do (Upcoming Tasks)
* [ ] **[TST-001]** Test infrastructure setup (MockBukkit harness, jqwik)
* [ ] **[CORE-001]** Core Domain: `Island`, `IslandId`, `IslandRole`, `IslandFlags` records

### 🔄 In Progress
* *No active items currently in progress.*

### 🔍 In Review
* *No active items currently in review.*

### ✅ Done (Completed)
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
