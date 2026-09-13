# Project Architectural Rules & Engineering Standards

This directory establishes the binding architectural standards, development rules, and quality benchmarks for the UXPLIMA Skyblock plugin. Every contributor and agent working on this codebase must strictly adhere to these documents.

---

## Standards Directory Index

1. **[Architecture & DDD Specification](ARCHITECTURE.md)**
   - Hexagonal (Ports & Adapters) architecture.
   - Bounded contexts (Island, Membership, Protection, Level, Upgrade, Generator, World).
   - Strict class size budget (maximum 300 lines) and anti-God-class module wiring.

2. **[Coding Standards & Quality Mandates](CODING_STANDARDS.md)**
   - Java 25+ toolchain and language features.
   - Strict JSpecify `@NullMarked` and NullAway zero-tolerance (`-Werror`).
   - Spotless automated formatting with Palantir Java Format.
   - Immutability by default, Records for Value Objects.

3. **[Naming & Method Conventions](NAMING_AND_METHOD_CONVENTIONS.md)**
   - Universal 100% English naming rule across the codebase.
   - Clean, expressive type and method naming without abbreviations.
   - Command-Query Separation (CQS) and method parameter budgets.

4. **[Documentation & Commenting Standards](DOCUMENTATION_AND_COMMENTS.md)**
   - "Why, not what" commenting philosophy.
   - Javadoc requirements on all public APIs and ports.
   - Zero tolerance for dead code, dangling comments, or committed TODOs.

5. **[Configuration & Localization Standards](CONFIGURATION_AND_LOCALIZATION.md)**
   - Absolute zero hardcoding policy (no in-code strings, prices, chances, limits, or materials).
   - HOCON format using `uxmlib-common` typed records.
   - Adventure MiniMessage and semantic styling tokens (no `§` or `&`).

6. **[Error Handling & Resilience](ERROR_HANDLING.md)**
   - Domain errors modeled as sealed results instead of exceptions.
   - Logging severity levels, diagnostic metadata, and player-friendly localized feedback.
   - No silent exception swallowing.

7. **[Platform & Technology Stack](PLATFORM_AND_TECH_STACK.md)**
   - Paper 26.2+ & Folia native V1 concurrency model (complete ban on `BukkitScheduler`).
   - V1 Geyser & Bedrock dual-platform UI via `uxmlib-bedrock` and `uxmlib-menu`.
   - Mandatory `uxm-lib` module mapping table (never re-invent the wheel).

8. **[Testing Standards & Drift Guards](TESTING_STANDARDS.md)**
   - MockBukkit v26.2 in-memory server testing for listeners and commands.
   - ArchUnit bytecode drift guards (Folia threading, legacy chat, SQL injection, layer fences).
   - jqwik property-based testing for coordinate grids and financial invariants.
   - The zero-skipped-tests build mandate.

9. **[Project Governance & Workflow Rules](PROJECT_RULES.md)**
   - Developer-AI explicit approval protocol and pair programming principles.
   - Strict Definition of Done (DoD).
   - Living documentation policy and Conventional Commits.
   - Gradle build conventions and mandatory Project Tracking Board updates.

10. **[Competitor Analysis & Architectural Benchmark](COMPETITOR_ANALYSIS.md)**
    - Teardown of Skyllia, SuperiorSkyblock2, BentoBox/BSkyBlock, DeluxeSkyblock, NewSky, and IridiumSkyblock.
    - 12-dimension feature comparison matrix.
    - Identification of market antipatterns and UXPLIMA Skyblock's strategic advantages.

11. **[Enterprise Foundation: API, REST, Commands & i18n](ENTERPRISE_FOUNDATION.md)**
    - Dual-layer developer API (`:api` query/action separation, `:bukkit-api`).
    - Embedded REST API (`:rest-adapter`) with Bearer token authentication.
    - Brigadier command engine, MiniMessage multi-language i18n, Placeholders, and programmatic permission catalog.

12. **[Network & Platform Integration](NETWORK_AND_PLATFORM_INTEGRATION.md)**
    - V1 multi-server scaling with Velocity proxy broker and Redis Streams/Pub-Sub dual-tier bus.
    - Folia region-threading concurrency model and Paper 26.2+ runtime.
    - Geyser & Floodgate touch-friendly Bedrock forms.

13. **[Extreme Scale Architecture: 100,000 Players](EXTREME_SCALE_ARCHITECTURE.md)**
    - "Unload on Idle" memory management.
    - Intra-JVM keyed island lock striping (`Striped<Lock>`) and durable SQL ACID transactions (`TxSql`).
    - O(1) hybrid delta-tracking island leveling and two-tier Caffeine/Redis caching.

14. **[Superpowers Skills System & Engineering Runbook](SUPERPOWERS_WORKFLOW.md)**
    - Catalog of all 14 Superpowers skills installed in `.agents/skills/`.
    - Mandatory workflows: `brainstorming`, `writing-plans`, `executing-plans`, `test-driven-development`, `systematic-debugging`, `verification-before-completion`.
    - Directory conventions: `docs/superpowers/specs/` and `docs/superpowers/plans/`.

15. **[Persistence & Database Schema Specification](PERSISTENCE_SPECIFICATION.md)**
    - Single-writer authority leases, monotonic epochs, and commit-time `SELECT ... FOR UPDATE` serialization.
    - Write-ahead `InventoryMutationJournal`, transactional outbox, and SQLite `DURABLE_PRODUCTION` validation.
    - Disaster recovery, point-in-time restore modes, and `ECONOMIC_ITEM_STATE` anti-duplication boundary.

16. **[GameMode Architecture Specification](GAMEMODE_ARCHITECTURE.md)**
    - Decoupled `ProfileRuleset` (Classic, Ironman, Hardcore, Stranded) and `GameMode` composition.
    - `GameModeInstance` aggregate with `PrimaryGameplayRootRef` and multi-root `AuthorityBinding`.
    - Cross-owner compound economic inventory transfers with write-ahead intent sequencing.
