# Project Task & Milestone Board (Kanban)

> **Policy:** This board must be kept up-to-date with every pull request, milestone, and development cycle as mandated in [PROJECT_RULES.md](PROJECT_RULES.md).
> An interactive visual version of this board is available at [`project-board.html`](../project-board.html).

---

## Current Sprint Overview

* **Phase:** Phase 1 — Implementation (P1-001, P1-002, P1-003 Completed; P1-004 In Progress)
* **Architecture Board State:** FROZEN (Architecture Frozen by Product Owner: YES — 2026-09-13)
* **Implementation Started:** YES
* **Phase 1 Started:** YES
* **P1-001 Status:** COMPLETED
* **P1-002 Status:** COMPLETED
* **P1-003 Status:** COMPLETED
* **P1-004 Status:** IN PROGRESS
* **Target Release:** v0.1.0-SNAPSHOT
* **Platform:** Paper 26.2+ & Folia on Java 25+
* **Overall Completion:** 73% (Phase 0 Frozen; P1-001/P1-002/P1-003 Completed)

---

## Kanban Columns

### 📋 Backlog (Future Milestones)
* [ ] **[ISL-010]** Island biome changer subsystem & GUI
* [ ] **[ISL-011]** Custom Island Schematics & starter presets (Desert, Nether, Classic, Cave)
* [ ] **[LVL-003]** Island level leaderboard caching & top-10 hologram integration
* [ ] **[UPG-002]** Island upgrades system (crop growth, generator rates, spawner speed)
* [ ] **[PROT-004]** WorldGuard & Lands soft-dependency adapter bridge
* [ ] **[NET-001]** Velocity / Redis cross-server island visit bus (`uxmlib-redis`)

### 📌 To Do (Upcoming Tasks)
* [ ] **[TST-001]** Test infrastructure setup (MockBukkit harness, jqwik)
* [ ] **[CORE-001]** Core Domain: `Island`, `IslandId`, `IslandRole`, `IslandFlags` records
* [ ] **[CORE-002]** Core Domain: `IslandRepository` and `WorldGridPort` interfaces
* [ ] **[WRD-001]** Spiral grid coordinate allocator & chunk safety engine
* [ ] **[CMD-001]** Main `/island` command tree via `uxmlib-command`
* [ ] **[GUI-001]** Main island management menu via `uxmlib-menu` & `uxmlib-bedrock`

### 🔄 In Progress
* [ ] **[P1-004]** Public API Foundation (`:api` module contracts) — IN PROGRESS

### 🔍 In Review
* *No active items currently in review.*

### ✅ Done (Completed)
* [x] **[P1-003]** Pure Domain Primitives & Package Foundation (`PlayerUuid`, `ProfileId`, `IslandId`, `Result`, `Unit`) — COMPLETED
* [x] **[P1-002]** Architecture Dependency Fences (ArchUnit fences for `:api`, `:core`, `:persistence-adapter`, `:bukkit-adapter`) — COMPLETED
* [x] **[P1-001]** Build Foundation & Multi-Module Scaffolding (`:api`, `:core`, `:persistence-adapter`, `:bukkit-adapter`) — COMPLETED
* [x] **[ARCH-002]** Skyblock Core Architecture & Gameplay Specification (`docs/superpowers/specs/2026-09-12-skyblock-core-design.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[ARCH-003]** Persistence & Database Schema Specification (`docs/PERSISTENCE_SPECIFICATION.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[ARCH-004]** GameMode Architecture Specification (`docs/GAMEMODE_ARCHITECTURE.md`) — FROZEN by Product Owner (2026-09-13)
* [x] **[GOV-002]** Project Governance & Coding Standards Review — Approved & Frozen by Product Owner (2026-09-13)
* [x] **[SETUP-001]** Git repository initialization & `.gitignore` configuration
* [x] **[TOOL-001]** Superpowers Skills Suite installation & integration (`docs/SUPERPOWERS_WORKFLOW.md`)
* [x] **[GOV-001]** Comprehensive Project Governance, Coding Standards & Testing Rules (`docs/PROJECT_RULES.md`)
* [x] **[UI-001]** Interactive Project Dashboard & Visual Kanban Board (`project-board.html`)
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
