# UXPLIMA Skyblock — GameMode Architecture Specification

**Document Version:** 1.3.0
**Date:** 2026-09-13
**Status:** FROZEN BY PRODUCT OWNER — 2026-09-13
**Authors:** UXPLIMA Architecture Team & Lead Systems Architect
**Platform Target:** Paper 26.2+ & Folia on Java 25+

---

## 1. Executive Summary & Design Principles

UXPLIMA Skyblock is an enterprise-grade distributed Minecraft gameplay platform. In historic Skyblock implementations, plugins frequently suffered from tight coupling: treating "Skyblock" as the sole hardcoded gameplay loop, embedding mode checks via monolithic `if/else` branches, confusing world storage formats with game modes, conflating profile restrictions with gameplay archetypes, and assuming that every gameplay session is an individual or team "Island" with a fixed square territory.

This specification establishes a **declarative, composition-based GameMode Architecture** for UXPLIMA Skyblock. It guarantees that any gameplay mode—from classic sky islands to one-block generators, chunk-unlock territories, underwater survival, multi-dimensional mirrored realms, or ocean trading vessels—can be expressed cleanly through decoupled primitive components without requiring conditional branching (`if (mode == X)`) anywhere in the core engine.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                     PLAYER ACCOUNT                                     │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ 1..N
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                     PLAYER PROFILE                                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • ProfileId (UUID)                                                                     │
│ • Ruleset: CLASSIC | IRONMAN | HARDCORE | STRANDED (Profile-Scoped Policy Layer)       │
│ • Participations: Collection of GameModeParticipations (Membership / Join state)       │
│ • activeGameModeInstanceId: UUID (Convenience / Active Navigation Pointer)             │
│ • Profile-Scoped Wallets, Cooldowns & Local Inventory Snapshot                         │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ N..1 (Typed Relational Participation)
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   GAMEMODE INSTANCE                                    │
│                    (Canonical Shared/Solo Gameplay Session Aggregate)                  │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • instanceId: UUID PK                                                                  │
│ • gameModeId: GameModeId (e.g. 'uxm:skyblock', 'uxm:tradewinds')                      │
│ • versionRecord: { implementationVersion, schemaVersion, contentDefinitionVersion }    │
│ • lifecycleState: CREATING | ACTIVE | SUSPENDED | ARCHIVED                             │
│ • primaryGameplayRootRef: PrimaryGameplayRootRef (RootTypeId + AggregateId)            │
│ • authorityBinding: AuthorityBinding (ISLAND | PLAYER | INSTANCE | MODE_OWNED)         │
│ • dimensionInstances: Map<DimensionId, DimensionInstance>                              │
│ • participations: List<GameModeParticipation>                                          │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ Resolves via Descriptor Registry
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   RESOLVED GAMEMODE                                    │
│                           (Runtime Active Archetype Engine)                            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • descriptor: GameModeDescriptor (Declarative HOCON / Content Pack)                    │
│ • dimensions: Map<DimensionId, DimensionDefinition>                                    │
│ • startStrategy: StartStrategy + 0..N StartTemplates                                   │
│ • progressionStrategies: 0..N ProgressionStrategy (Multi-source, Optional)             │
│ • territoryStrategies: 0..N TerritoryStrategy (Spatial Protection, Optional)           │
│ • boundCapabilities: Set<NamespacedId>                                                 │
│ • participationEvaluator: ParticipationCompatibilityEvaluator                          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Foundational Invariants

1. **Orthogonal Separation (Ruleset $\neq$ GameMode):**
   * A **Profile Ruleset** (`CLASSIC`, `IRONMAN`, `HARDCORE`, `STRANDED`) defines *economic, social, and life-loss policy restrictions* scoped strictly to the individual player profile.
   * A **GameMode** (`uxm:skyblock`, `uxm:oneblock`, `uxm:tradewinds`, etc.) defines *world topologies, start mechanics, spatial structures, environmental hazards, and gameplay archetypes*.
   * Profile Rulesets and GameModes are **independently modeled orthogonal axes with explicit compatibility evaluation**.
2. **Canonical State Ownership via `GameModeInstance`:**
   * A `GameMode` is **NOT** owned by a single `Profile`. Multiple profiles can participate in the exact same team gameplay session (e.g. three profiles sharing one SkyBlock island, or two profiles crewing one TradeWinds vessel).
   * **Invariant:** *A shared gameplay instance has exactly one canonical GameMode identity (`GameModeInstance.gameModeId`) regardless of how many profiles participate in it.*
3. **Non-Island Primary Root Authority Binding:**
   * Distributed single-writer authority is not restricted to islands or players. Every mutable canonical GameMode root (Island, Vessel, Course, Claim) binds to an **`AuthorityBinding`** that reuses the core distributed lease fencing and row lock serialization protocol.
   * **Invariant:** *Every mutable canonical GameMode root that may be accessed from multiple backend nodes MUST have exactly one authoritative writer scope or an explicitly documented immutable/read-only model.*
4. **Generalization Beyond the Island (`PrimaryGameplayRootRef`):**
   * The core domain does **NOT** assume that every gameplay session is an island. An instance's primary anchor is an extensible `PrimaryGameplayRootRef` (`uxm:island`, `uxm:vessel`, `uxm:parkour_course`, `uxm:creative_area`, `uxm:multidim_claim`).
5. **Generic Durable Economic Inventory Port:**
   * Durability semantics (write-ahead journal, OCC versioning, outbox replication, authority fencing) are shared across all economic inventories; canonical ownership is partitioned by aggregate type (`Profile`, `Vessel`, `IslandVault`). Vessel cargo is owned by the Vessel aggregate, not the player profile.
6. **Extensible Dimension Identity (`DimensionId`):**
   * Dimensions are identified by extensible namespaced IDs (`minecraft:overworld`, `uxm:upside_down`, etc.), decoupling world generation and links from closed core enums.
7. **Clean Infrastructure Decoupling:**
   * World storage mechanisms (`Anvil`, `SlimeWorldManager` / `AdvancedSlimePaper`) are infrastructure adapters (`WorldStorageProvider`) and are 100% orthogonal to GameModes.

---

## 2. Competitor Benchmarks & Fact Verification

All competitor mechanics in this specification are strictly verified against local workspace sources (`references/competitors/`) and authoritative upstream repositories.

### 2.1 Poseidon (BentoBox Addon)
* **Verified Mechanics:**
  - Underwater survival where normal breathing rules are inverted (players receive water breathing / night vision in water).
  - Movement survival rule: player must keep moving while submerged to survive (`MovementSurvivalRule` / suffocation on stagnation).
  - Land and surface exposure hazard: sunlight and dry air burn/damage the aquatic player (`SurfaceExposureHazard`).
  - Flooded Nether dimension: the Nether is filled with water oceans instead of lava/air (`FloodedNetherDimension`).
  - Lore/challenges progression: quest book guiding aquatic progression.
  - Starter choices: based on shipwreck variants (`shipwreck`, `shipwreck + small monument`, `shipwreck + monuments`).
* **Source Corrections:**
  - *No "water pressure" or "ConduitProgression" engine exists in upstream Poseidon.* These unverified assumptions are removed.
  - *End World:* Upstream documentation explicitly states that the End world is "currently underdeveloped". UXPLIMA defines an explicit configurable dimension policy without asserting competitor parity for the End.

### 2.2 StrangerRealms (BentoBox Addon)
* **Verified Mechanics:**
  - Multi-dimensional gameplay: Overworld, Upside Down (which *replaces* the Nether), and the End.
  - Block-for-block dynamically generated inverted/distressed realm mirroring the Overworld (`UpsideDownTopology`).
  - Corrupted mob transformations and dimension-specific monster variations.
  - Cross-dimension Glimmer interaction and Warped Compass tracking.
  - Claims spanning dimensions: a claim in the Overworld protects territory in the Upside Down and End simultaneously.
  - Dynamic claim growth based on team member count.
  - Dynamic global world border expanding across the network.
* **Source Corrections:**
  - *No "rift portals", "toxic ash", or "toxic spores" exist in upstream StrangerRealms.*
  - *End Topology:* In upstream StrangerRealms, claims protect territory in the End, but the End itself is NOT a "mirrored End topology"; it is standard End terrain under claim protection.

### 2.3 AcidIsland (BentoBox Addon)
* **Verified Mechanics:**
  - Island surrounded by a hazardous ocean: acid water immersion causes damage/poison, and acid rain inflicts damage upon sky exposure.
  - Water purification mechanics (`WaterPurificationMechanic`):
    1. Rain collection via dripstone into cauldrons.
    2. Water bottle/bucket smelting in furnaces.
    3. Water bottles brewed with coal in brewing stands.
  - Modern versions feature sulfur sea, sulfur vents, and geysers as specialized environmental mechanics (kept as mode-owned capabilities).
* **Source Corrections:**
  - *No "sponge purification" or "distillation blocks" exist in upstream AcidIsland.*

### 2.4 Brix (BentoBox Addon)
* **Verified Mechanics:**
  - Protected creative building areas (`ProtectedBuildAreaTerritory`) for solo or team building.
  - Configurable world generation (void, flat, or custom terrain).
  - Showcase and visiting integration through compatible external addons.
  - Zero mandatory progression, zero survival hazards, zero hunger/damage.
* **Source Corrections:**
  - *Brix carries NO mandatory progression system.* A GameMode must be permitted to declare `0` progression strategies.
  - *Brix is NOT a "CoursePlot".*

### 2.5 Parkour (BentoBox Addon)
* **Verified Mechanics:**
  - Creative course construction with gold pressure plates marking start and finish.
  - Survival play mode for obstacle runs (enforcing standard player hitboxes and movement physics).
  - Timer tracking, high scores, checkpoints, team course building, and public course listing/warps.
* **Source Corrections:**
  - Upstream executes runs in *survival mode* (not adventure mode). UXPLIMA abstracts this via a configurable `CoursePlayExecutionPolicy`.

### 2.6 CaveBlock (BentoBox Addon)
* **Verified Mechanics:**
  - Subterranean world topology (`SubterraneanWorldTopology`): players are enclosed in solid rock/caves.
  - Cave carvers, ravines, and configurable ore/resource generation throughout subterranean stone.
  - Dimension-aware generation (subterranean Nether and End).
* **Source Corrections:**
  - *No specific proprietary "3D Perlin noise" algorithm or custom "toxic gas hazard" exists in CaveBlock.* Darkness and mobs represent standard vanilla mechanics.

### 2.7 ChunkBlock (BentoBox Addon)
* **Verified Mechanics:**
  - Single regenerating magic block, starting with exactly 1 accessible chunk.
  - Configurable dimension policy: Nether and End are disabled by default, but can be enabled with independent center chunks.
  - Island level is used as currency to unlock adjacent chunks.
  - LIFO relock order if island level drops, with safe player eviction (no block deletion).

### 2.8 Skyllia & SkylliaAcidRain
* **Verified Source:** `references/competitors/Skyllia/addons/SkylliaAcidRain`
* **Classification:** **NOT A GAMEMODE.** It is an **Environmental Hazard Feature Addon** (`HazardProvider`).

### 2.9 World Storage Integration (ASWM / SlimeWorld)
* **Verified Sources:** `references/competitors/NewSky` (ASWM), SuperiorSkyblock2 (Slime hook).
* **Classification:** **NOT A GAMEMODE.** They are **WorldStorageProvider** infrastructure adapters.

---

## 3. Verified Competitor Mapping Table

| Competitor Reference | Source Reference | Verified Mechanics | World Topology | Start Strategy | Progression Model | Territory Model | Dimensions Supported | Authority Scope | Specialized Capabilities | UXPLIMA Architectural Mapping |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **BentoBox BSkyBlock** | `references/competitors/bskyblock` | Classic sky survival on floating schematic island | `VOID` | `SCHEMATIC` | Island Level & Challenges | `FIXED_CUBOID` | Overworld, Nether, End | `ISLAND` | `island-level`, `schematic-loader` | **GameMode (`uxm:skyblock`)**: `VoidTopology` + `SchematicStart` + `FixedBoundsTerritory` |
| **BentoBox AOneBlock** | Upstream AOneBlock | Endless mining of a single regenerating magic block | `VOID` | `MAGIC_BLOCK` | Ordered Counted Phases | `FIXED_CUBOID` | Overworld, Nether, End | `ISLAND` | `regenerating-block`, `phase-progression` | **GameMode (`uxm:oneblock`)**: `VoidTopology` + `MagicBlockStart` + `PhaseBlockProgression` |
| **BentoBox ChunkBlock** | Upstream ChunkBlock | Magic block on 1 chunk; unlock adjacent chunks using island level | `VOID` | `MAGIC_BLOCK` + Single Chunk | Block Phases + Chunk Unlocking | `CHUNK_GRAPH` | Overworld (Nether/End optional) | `ISLAND` | `regenerating-block`, `chunk-territory`, `territory-currency` | **GameMode (`uxm:chunkblock`)**: `VoidTopology` + `ChunkGraphTerritory` + Configurable Dimensions |
| **BentoBox AcidIsland** | Upstream AcidIsland | Island surrounded by toxic ocean; rain/water damage; cauldron/furnace/brewing purify | `OCEAN` (Acid Water) | `SCHEMATIC` | Island Level & Purification | `FIXED_CUBOID` | Overworld, Nether, End | `ISLAND` | `acid-hazard`, `water-purification` | **GameMode (`uxm:acid_island`)**: `OceanTopology` + `AcidHazardEngine` + `WaterPurificationMechanic` |
| **BentoBox CaveBlock** | Upstream CaveBlock | Subterranean cave survival surrounded by solid rock and ores | `SUBTERRANEAN` | `SCHEMATIC` (Cavern) | Stone/Ore Mining & Level | `FIXED_CUBOID` | Overworld, Nether, End | `ISLAND` | `subterranean-generator`, `ore-distribution` | **GameMode (`uxm:caveblock`)**: `SubterraneanTopology` + `CavernStart` + `FixedBoundsTerritory` |
| **BentoBox SkyGrid** | Upstream SkyGrid | Survival on a sparse 3D grid of blocks at regular coordinate intervals | `SPARSE_GRID` | Safe Grid Block Spawn | Grid Exploration & Survival | `FIXED_CUBOID` | Overworld, Nether, End | `ISLAND` | `sparse-grid-generator`, `palette-distribution` | **GameMode (`uxm:skygrid`)**: `SparseGridTopology` + `GridSpawnStart` + `FixedBoundsTerritory` |
| **BentoBox Boxed** | Upstream Boxed | Survival in a small boxed territory expanded by vanilla advancements | `NORMAL_GENERATED` | Small Box Claim | Advancement Completion | `EXPANDING_BOX` | Overworld, Nether, End | `INSTANCE` | `advancement-progression`, `expanding-border` | **GameMode (`uxm:boxed`)**: `NormalGeneratedTopology` + `AdvancementProgression` + `ExpandingBoxTerritory` |
| **BentoBox Poseidon** | Upstream Poseidon | Underwater survival, inverted breathing, keep moving rule, land burning, flooded nether | `OCEAN` (Deep Water) | Shipwreck / Monument Template | Lore/Challenges Book | `FIXED_CUBOID` | Overworld + Flooded Nether (End underdeveloped) | `INSTANCE` | `underwater-breathing`, `movement-survival`, `flooded-nether` | **GameMode (`uxm:poseidon`)**: `OceanTopology` + `ShipwreckStart` + `FloodedNetherDimension` |
| **BentoBox StrangerRealms** | Upstream StrangerRealms | Overworld linked to inverted Upside Down (replacing Nether) & End; dynamic mirrored generation | `NORMAL` + `MIRRORED_REALM` | Existing Terrain Claim | Cross-Realm Progression | `MIRRORED_TERRITORY` | Overworld, Upside Down, End | `INSTANCE` | `dimension-mirror`, `glimmer-sync`, `warped-compass` | **GameMode (`uxm:stranger_realms`)**: `DimensionLinkPolicy` + `MirroredTerritory` + Member-based Growth |
| **BentoBox TradeWinds** | Upstream TradeWinds | Ocean navigation, vessel as primary asset, cargo hold, NPC ports, trading, ship ranks | `PROCEDURAL_OCEAN` | Cargo Vessel Spawn | Wealth & Vessel Rank | `0` (Optional Islet later) | Overworld Ocean | `INSTANCE` (Vessel) | `vessel-entity`, `npc-market`, `maritime-reputation` | **GameMode (`uxm:tradewinds`)**: `OceanTopology` + `VesselStart` + `VesselProgression` (Zero initial territory) |
| **BentoBox Parkour** | Upstream Parkour | Creative course building with gold plates; survival obstacle runs; checkpoints, timers | `VOID` / Custom | Course Build Area | Timed Course Runs | `PROTECTED_BUILD_AREA` | Overworld | `INSTANCE` (Course) | `checkpoint-engine`, `course-timer`, `leaderboard-metrics` | **GameMode (`uxm:parkour`)**: `VoidTopology` + `ProtectedBuildArea` + `TimedCourseProgression` |
| **BentoBox Brix** | Upstream Brix | Protected creative plot building, shared building, showcase integration | `VOID` / Flat / Custom | Creative Plot | None (`0` Progression) | `PROTECTED_BUILD_AREA` | Overworld | `INSTANCE` (Plot) | `creative-capabilities`, `showcase-visitor` | **GameMode (`uxm:brix`)**: `VoidTopology` + `ProtectedBuildArea` + Zero Progression |
| **Skyllia SkylliaAcidRain** | `references/competitors/Skyllia` | Periodic Folia entity-scheduled acid damage on sky/water exposure | N/A | N/A | N/A | N/A | N/A | N/A | `entity-scheduler-hazard` | **NOT A GAMEMODE — HAZARD FEATURE ADDON**: Mapped to reusable `AcidHazardEngine`. |
| **NewSky / ASWM** | `references/competitors/NewSky` | Fast binary world serialization and asynchronous SRF world loading | N/A | N/A | N/A | N/A | All | N/A | `binary-world-storage` | **NOT A GAMEMODE — INFRASTRUCTURE ADAPTER**: Mapped to `WorldStorageProvider`. |

---

## 4. Distributed Authority Binding for Primary Gameplay Roots

To ensure complete crash-consistency and eliminate split-brain write conflicts across federated Folia nodes, every `GameModeInstance` binds to an **`AuthorityBinding`**:

```java
public sealed interface AuthorityBinding permits
    AuthorityBinding.IslandAuthorityBinding,
    AuthorityBinding.PlayerSessionAuthorityBinding,
    AuthorityBinding.GameModeInstanceAuthorityBinding,
    AuthorityBinding.ModeOwnedAuthorityBinding {

    AuthorityScope scope();

    record IslandAuthorityBinding(UUID islandId) implements AuthorityBinding {
        @Override public AuthorityScope scope() { return AuthorityScope.ISLAND; }
    }

    record PlayerSessionAuthorityBinding(UUID playerUuid) implements AuthorityBinding {
        @Override public AuthorityScope scope() { return AuthorityScope.PLAYER; }
    }

    record GameModeInstanceAuthorityBinding(UUID instanceId) implements AuthorityBinding {
        @Override public AuthorityScope scope() { return AuthorityScope.GAME_MODE_INSTANCE; }
    }

    record ModeOwnedAuthorityBinding(NamespacedId providerId, UUID rootAggregateId) implements AuthorityBinding {
        @Override public AuthorityScope scope() { return AuthorityScope.MODE_OWNED; }
    }
}
```

### 4.1 Mode-Owned Authority Boundary & Fencing Semantics
To support extensible game mode roots (such as vessels, courses, or creative plots) without modifying core enums:
* **`ModeOwnedAuthorityProvider` Contract:**
The core/public GameMode extension API **NEVER exposes raw SQL table names, dialect details, or persistence implementations**. External plugins provide ONLY stable logical identity:
```java
public interface ModeOwnedAuthorityProvider {
    NamespacedId providerId(); // Stable namespaced provider ID: e.g. "uxm:trade_winds", "brix:creative"
    String rootAggregateKey(UUID rootAggregateId); // Stable logical aggregate key
}
```
* **Core-Owned Persistence & Logical Storage (`mode_owned_authorities`):**
  - Canonical authority storage remains strictly under core-owned persistence semantics.
  - External Paper plugins register a `ModeOwnedAuthorityProvider` via the public API registration SPI; they **CANNOT inject arbitrary SQL authority tables into the core transaction protocol**.
  - All mode-owned authorities are persisted in the core-owned table:
```sql
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
```
  - This table does NOT reinvent the authority algorithm; it reuses the exact same canonical row serialization (`SELECT ... FOR UPDATE` / `BEGIN IMMEDIATE`), epoch fencing (`authority_epoch`), monotonic advancement on takeover, and stale-write rejection semantics.
  - Private core registries and persistence adapters are internal implementation details, not external plugin APIs.

* **Approved Authority Mapping Matrix:**
  - **Island-Derived Modes (`ISLAND` Authority Scope):**
    - Modes: `SkyBlock` (`uxm:skyblock`), `OneBlock` (`uxm:oneblock`), `ChunkBlock` (`uxm:chunkblock`), `AcidIsland` (`uxm:acid_island`), `CaveBlock` (`uxm:caveblock`), `SkyGrid` (`uxm:skygrid`).
    - Primary root type: `uxm:island`.
    - Authority binds to `ISLAND` authority (`island_authorities`) keyed by `primary_root_aggregate_id` (`island_id`).
  - **Non-Island / Root-Generic Modes (`GAME_MODE_INSTANCE` Authority Scope):**
    - Modes: `Boxed` (`uxm:boxed`), `Poseidon` (`uxm:poseidon`), `StrangerRealms` (`uxm:stranger_realms`), `TradeWinds` (`uxm:trade_winds`), `Parkour` (`uxm:parkour`), `Brix` (`uxm:brix`).
    - Authority binds to `GAME_MODE_INSTANCE` authority (`game_mode_instance_authorities`) keyed by `instance_id`.
  - **Mode-Owned Extension Modes (`MODE_OWNED` Authority Scope):**
    - Strictly an extension path when truly custom authority ownership is required by third-party extensions.
    - Resolves via registered `ModeOwnedAuthorityProvider` (`providerId` + `rootAggregateKey`) and binds to core-owned `mode_owned_authorities`.
  - *Note:* Unapproved pseudo-modes (e.g. `uxm:void_world`) are strictly excluded from the authority matrix and catalog.

* **Unified Authority Invariants:** All authority bindings (whether player, island, instance, or mode-owned) strictly reuse the SAME canonical distributed authority algorithm:
  1. **Canonical Row Lock:** Exclusive row lock (`SELECT ... FOR UPDATE` on MySQL/PostgreSQL, `BEGIN IMMEDIATE` on SQLite) on the canonical authority table.
  2. **Epoch & Lease Fencing:** Mutations are guarded by `authority_epoch` and `lease_expires_at >= CURRENT_TIMESTAMP`.
  3. **Fail-Closed Takeover:** When a node crashes or partitions, takeovers increment `authority_epoch` and fence stale delayed writes from the crashed node.
  4. **Bounded Transaction Deadlines:** Nominal lease expiry during execution does not revoke an already-authorized transaction holding the canonical row lock within its operational budget.

### 4.2 Generic Instance & Authority Logical Schema (`game_mode_instances`, `game_mode_dimension_instances`, `game_mode_instance_authorities`, `mode_owned_authorities`)
The canonical physical relational schema is formally defined in [`docs/PERSISTENCE_SPECIFICATION.md`](PERSISTENCE_SPECIFICATION.md#25-canonical-gamemode-aggregate--participation-subsystem-game_mode_instances-game_mode_dimension_instances-game_mode_participations-game_mode_instance_authorities-mode_owned_authorities). Below is the logical aggregate schema:

* **Canonical Logical DDL:**
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
```
* **Dialect-Neutral Mutation Semantics:** Rather than relying on dialect-specific `ON UPDATE CURRENT_TIMESTAMP` clauses, all updating statements explicitly assign `updated_at = CURRENT_TIMESTAMP` in standard SQL:
```sql
UPDATE game_mode_instance_authorities
SET authoritative_node = :node,
    authority_epoch = authority_epoch + 1,
    lease_expires_at = :newLease,
    updated_at = CURRENT_TIMESTAMP
WHERE instance_id = :instanceId;
```
* **Canonical DB Clock:** Authority validation and lease expiration comparisons evaluate strictly against the canonical database clock (`CURRENT_TIMESTAMP`), ensuring clock-drift resilience across distributed Folia nodes.

### 4.3 Global Authority Lock Ordering Contract (`AuthorityLockKey`)
To eliminate cross-mode, multi-root, and cross-aggregate deadlocks across all authority scopes:
Any operation requiring row locks on multiple authorities MUST acquire them in strict order defined by one exact comparator `AuthorityLockKey`:

```java
public record AuthorityLockKey(
    int scopeRank, // Explicit stable integer rank: 0=PLAYER_SESSION, 1=ISLAND, 2=GAME_MODE_INSTANCE, 3=MODE_OWNED
    int keyTypeRank, // Explicit stable protocol constant: 0=KEY_TYPE_UUID, 1=KEY_TYPE_NAMESPACED_BYTES (NEVER enum ordinal)
    long uuidMostSignificant,
    long uuidLeastSignificant,
    String namespacedKey // Normalized lowercase UTF-8: providerId/stableAggregateKey
) implements Comparable<AuthorityLockKey> {

    public static final int RANK_PLAYER_SESSION = 0;
    public static final int RANK_ISLAND = 1;
    public static final int RANK_GAME_MODE_INSTANCE = 2;
    public static final int RANK_MODE_OWNED = 3;

    public static final int KEY_TYPE_UUID = 0;
    public static final int KEY_TYPE_NAMESPACED_BYTES = 1;

    public static AuthorityLockKey playerSession(UUID playerUuid) {
        return new AuthorityLockKey(RANK_PLAYER_SESSION, KEY_TYPE_UUID,
            playerUuid.getMostSignificantBits(), playerUuid.getLeastSignificantBits(), "");
    }

    public static AuthorityLockKey island(UUID islandId) {
        return new AuthorityLockKey(RANK_ISLAND, KEY_TYPE_UUID,
            islandId.getMostSignificantBits(), islandId.getLeastSignificantBits(), "");
    }

    public static AuthorityLockKey gameModeInstance(UUID instanceId) {
        return new AuthorityLockKey(RANK_GAME_MODE_INSTANCE, KEY_TYPE_UUID,
            instanceId.getMostSignificantBits(), instanceId.getLeastSignificantBits(), "");
    }

    public static AuthorityLockKey modeOwned(NamespacedId providerId, String stableAggregateKey) {
        String normalized = providerId.asString().toLowerCase(Locale.ROOT) + "/" + stableAggregateKey;
        return new AuthorityLockKey(RANK_MODE_OWNED, KEY_TYPE_NAMESPACED_BYTES, 0L, 0L, normalized);
    }

    @Override
    public int compareTo(AuthorityLockKey other) {
        // 1. Explicit stable scopeRank comparison
        int cmp = Integer.compare(this.scopeRank, other.scopeRank);
        if (cmp != 0) {
            return cmp;
        }
        // 2. Explicit stable keyTypeRank comparison (UUID rank 0 < Namespaced bytes rank 1)
        // Resolves mixed UUID vs String deterministically and null-safely
        cmp = Integer.compare(this.keyTypeRank, other.keyTypeRank);
        if (cmp != 0) {
            return cmp;
        }
        // 3. Canonical payload comparison based on keyTypeRank
        if (this.keyTypeRank == KEY_TYPE_UUID) {
            int msbCmp = Long.compareUnsigned(this.uuidMostSignificant, other.uuidMostSignificant);
            if (msbCmp != 0) {
                return msbCmp;
            }
            return Long.compareUnsigned(this.uuidLeastSignificant, other.uuidLeastSignificant);
        }
        // True unsigned lexicographical comparison of UTF-8 byte arrays (NOT String.compareTo() UTF-16 code units)
        byte[] thisBytes = (this.namespacedKey != null ? this.namespacedKey : "").getBytes(StandardCharsets.UTF_8);
        byte[] otherBytes = (other.namespacedKey != null ? other.namespacedKey : "").getBytes(StandardCharsets.UTF_8);
        return Arrays.compareUnsigned(thisBytes, otherBytes);
    }
}
```
* **Exact Total Ordering Rules:**
  1. **Explicit Stable Scope Rank (`scopeRank`):**
     - Rank 0: `PLAYER_SESSION`
     - Rank 1: `ISLAND`
     - Rank 2: `GAME_MODE_INSTANCE`
     - Rank 3: `MODE_OWNED`
     *(Never relies on Java enum ordinals).*
  2. **Explicit Stable Key Type Rank (`keyTypeRank`):**
     - Rank 0: `KEY_TYPE_UUID`
     - Rank 1: `KEY_TYPE_NAMESPACED_BYTES`
     *(Stable protocol constants ensure null-safe and deterministic mixed comparison).*
  3. **Canonical Payload Comparison:**
     - **UUID 128-Bit Unsigned Binary Order:** Evaluated via `Long.compareUnsigned(mostSignificantBits)`, then `Long.compareUnsigned(leastSignificantBits)`.
     - **Namespaced/Custom ID Order:** Canonical UTF-8 byte arrays compared using unsigned lexicographical ordering (`java.util.Arrays.compareUnsigned(aBytes, bBytes)`). *Note: Java's `String.compareTo()` operates on UTF-16 code units; calling `String.compareTo()` "UTF-8 byte order" is technically false. True UTF-8 byte order requires unsigned byte array comparison.*
* **Universal Application:** The SAME comparator governs:
  - Player ↔ Player (two-player operations lock players in ascending 128-bit unsigned UUID order)
  - Player ↔ Island (Player session locked first, then Island)
  - Player ↔ GameModeInstance (Player session locked first, then GameModeInstance)
  - ModeOwned authority (Player session locked first, then ModeOwned root)
  - Compound multi-inventory operations (e.g. Player inventory $\leftrightarrow$ Vessel cargo)

### 4.4 Architecture Contract Test Specification (`AuthorityLockKeyTotalOrderContractTest`)
The mathematical invariants of `AuthorityLockKey` must be strictly validated by a dedicated contract test suite (specification defined; implementation deferred to Phase 1):
* **Suite Name:** `AuthorityLockKeyTotalOrderContractTest`
* **Required Test Coverage Matrix:**
  1. **UUID vs UUID:** Verifies unsigned 128-bit binary comparison (specifically testing edge cases where MSB is negative in signed two's complement, verifying `0xFFFFFFFFFFFFFFFFL` orders after `0x0000000000000000L`).
  2. **String vs String:** Verifies true unsigned UTF-8 byte ordering across ASCII and multi-byte UTF-8 sequences.
  3. **UUID vs String:** Verifies deterministic ordering resolved via `keyTypeRank` (UUID rank 0 < Namespaced String rank 1), confirming null-safety.
  4. **String vs UUID:** Verifies inverted ordering consistency.
  5. **Comparator Antisymmetry:** Confirms $\text{sgn}(\text{compare}(a, b)) == -\text{sgn}(\text{compare}(b, a))$ for all combinations of authority keys.
  6. **Transitivity:** Confirms that if $\text{compare}(a, b) > 0$ and $\text{compare}(b, c) > 0$, then $\text{compare}(a, c) > 0$.
  7. **Deterministic Serialization / Reconstruction:** Confirms round-trip parsing from canonical string format yields an identical object preserving exact total order.

---

## 5. Participation & Ruleset Compatibility Engine

### 5.1 Typed Relational Participation Logical Schema (`game_mode_participations`)
Rather than maintaining an untyped in-memory set, instance membership is persisted relationally using the canonical logical schema:
```sql
CREATE TABLE game_mode_participations (
    instance_id VARCHAR(36) NOT NULL,
    profile_id VARCHAR(36) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
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

### 5.2 Multi-Axis Participation Compatibility Evaluator
When a player profile attempts to join an existing `GameModeInstance`, the system evaluates two orthogonal axes:
1. **Axis 1:** Profile Ruleset $\times$ Target GameMode
2. **Axis 2:** Joining Profile Ruleset $\times$ Existing Participant Rulesets

```java
public interface ParticipationCompatibilityEvaluator {
    CompatibilityReport evaluateParticipation(
        ProfileRuleset joiningRuleset,
        GameModeDescriptor targetGameMode,
        Set<ProfileRuleset> existingParticipantRulesets,
        ParticipationRole proposedRole,
        Set<NamespacedId> activeCapabilities
    );
}
```

### 5.3 Ruleset Resource Isolation Invariant
> [!IMPORTANT]
> **Strict Policy Non-Violation Invariant:**
> Joining or sharing a `GameModeInstance` MUST NOT create a resource-transfer path that violates either participant's Ruleset guarantees.
>
> *Isolation Enforcement:* If an isolation-sensitive ruleset (e.g. `IRONMAN`) participates in a shared instance, all shared resource vectors (shared chest containers, island vaults, vessel cargo, drop-trading, and market split transactions) are evaluated by capability event guards. The architecture provides the policy interception points; the actual matrix of permitted multi-ruleset co-op combinations remains **UNDECIDED UNTIL PRODUCT OWNER DECLARATION**.

---

## 6. Generic Durable Economic Inventory Port

### 6.1 Single-Owner vs Compound Multi-Owner Mutation Contract
Vessel cargo, island vaults, and player inventories are distinct aggregates that share the same durability contract (`InventoryMutationJournal`, OCC versioning, outbox replication, authority fencing). Crucially, cross-boundary transfers between distinct economic owners cannot be executed as separate, independently committed single-inventory mutations:

```java
public record EconomicInventoryId(NamespacedId ownerType, UUID ownerId, String inventorySlotKey) {}

public record CompoundInventoryParticipant(
    EconomicInventoryId inventoryId,
    long expectedVersion,
    AuthorityBinding authorityBinding,
    InventoryDelta delta,
    byte[] beforeFingerprint,
    byte[] afterFingerprint
) {}

public interface DurableEconomicInventoryPort {
    InventorySnapshot loadDurableInventory(EconomicInventoryId inventoryId);

    // Single-inventory mutation (e.g. routine local crafting, vault withdraw within same owner)
    CommitResult executeMutation(
        UUID operationId,
        CompoundInventoryParticipant participant,
        InventoryMutationIntent intent
    );

    // Multi-inventory compound mutation (e.g. Player Profile Inventory ↔ Vessel Cargo transfer)
    CommitResult executeCompoundMutation(
        UUID operationId,
        List<CompoundInventoryParticipant> participants,
        InventoryMutationIntent intent
    );
}
```

* **Single Canonical Compound Journal Relational Schema:**
The physical DDL, column mappings, and constraints for write-ahead journal tables are canonically owned and maintained in [`docs/PERSISTENCE_SPECIFICATION.md`](PERSISTENCE_SPECIFICATION.md#24-multi-profile--player-session-authority-subsystem-player_accounts-player_profiles-player_sessions-profile_inventories-inventory_mutation_journals-profile_switch_operations):
- **Journal Header (`inventory_mutation_journals`):** `operation_id` (PK), `operation_type`, `state` (`INTENT`, `APPLYING`, `APPLIED`, `COMMITTED`, `ABORTED`, `RECOVERY_REQUIRED`), `participant_count`, `payload`, `expires_at`, `created_at`, `updated_at`.
- **Journal Participants (`inventory_mutation_participants`):** Composite PK `(operation_id, participant_index)`, `inventory_type`, `owner_root_type`, `owner_root_id`, `expected_version`, `authority_type`, `authority_id`, `authority_epoch`, `before_fingerprint`, `after_fingerprint`, `durable_apply_state` (`PENDING`, `APPLIED`, `REVERTED`), `mutation_delta_payload`, `updated_at`.

To eliminate competing schemas and ensure single-truth consistency across all architectural documents, GameMode aggregates strictly consume this canonical persistence contract.


* **Critical Invariants:**
  > **No strong economic inventory mutation may become externally visible before its recovery intent is durably committed.**

  > **A recovery worker must never depend on an operation record that could have been lost in the same crash it is expected to recover from.**

  > **SQL durable state $\neq$ live Folia/Bukkit inventory state.** The complete operation across live player inventory, live vessel/container state, and SQL is recovered through the durable write-ahead journal protocol; it is not one single ACID transaction across all three.

### 6.2 Four-Phase Compound Journal Protocol (Player Profile $\leftrightarrow$ Vessel Cargo Walkthrough)
In a TradeWinds vessel transfer (Player Profile Inventory $\leftrightarrow$ Vessel Cargo):

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│ PHASE 1 — DURABLE INTENT                                                         │
├──────────────────────────────────────────────────────────────────────────────────┤
│ 1. Generate stable operationId (e.g. T123).                                      │
│ 2. Acquire & validate required authority scopes in canonical order:              │
│    - PlayerSessionAuthority (playerUuid)                                         │
│    - GameModeInstanceAuthority (vesselId/instanceId)                             │
│ 3. Read and validate under lock:                                                 │
│    - Source expected version (v12) and before-fingerprint                        │
│    - Destination expected version (v7) and before-fingerprint                    │
│ 4. Persist inventory_mutation_journals INTENT + participants rows containing:    │
│    - operationId = T123, status = 'INTENT'                                       │
│    - Participant 1 (Player): expectedVersion=12, fingerprints, delta             │
│    - Participant 2 (Vessel): expectedVersion=7, fingerprints, delta              │
│ 5. COMMIT durable INTENT to SQL.                                                 │
│    -> Only after this commit may externally visible/live mutation begin!         │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│ PHASE 2 — LIVE / APPLY                                                           │
├──────────────────────────────────────────────────────────────────────────────────┤
│ 6. Apply live inventory mutations in their valid Folia execution contexts:       │
│    - Player inventory mutated on Player's EntityScheduler                        │
│    - Vessel cargo mutated on Vessel's owning RegionScheduler                     │
│ 7. Record in-memory participant apply state (APPLIED_SOURCE, APPLIED_DEST).      │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│ PHASE 3 — DURABLE PARTICIPANT APPLY EVIDENCE                                     │
├──────────────────────────────────────────────────────────────────────────────────┤
│ 8. Update inventory_mutation_participants with durable apply progress:           │
│    - Participant 1 durable_apply_state = 'APPLIED'                               │
│    - Participant 2 durable_apply_state = 'APPLIED'                               │
│ 9. COMMIT durable participant state to SQL.                                      │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│ PHASE 4 — FINAL OCC COMMIT                                                       │
├──────────────────────────────────────────────────────────────────────────────────┤
│ 10. Reacquire/validate canonical authority row locks under bounded transaction:  │
│     - SELECT ... FROM player_sessions WHERE player_uuid = :p FOR UPDATE;         │
│     - SELECT ... FROM game_mode_instance_authorities WHERE instance_id = :v ...   │
│ 11. Persist canonical durable inventory states with OCC:                         │
│     - profile_inventories: v12 -> v13                                            │
│     - vessel_inventories:  v7  -> v8                                             │
│ 12. Mark operationId = T123 as COMMITTED in inventory_mutation_journals.         │
│ 13. COMMIT SQL transaction.                                                      │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Comprehensive Crash Recovery Matrix

| Crash Scenario | State in SQL | Live State (Player / Vessel) | Reconciler Action on Reboot / Takeover | Invariant Guarantee |
| :--- | :--- | :--- | :--- | :--- |
| **A. Crash before INTENT commit** | No record of `T123` in SQL | Untouched (No live mutation was permitted) | None. Normal startup. | Zero mutation occurred; no recovery required. |
| **B. Crash after INTENT commit but before any live mutation** | `INTENT` persisted | Both inventories match `before_fingerprint` | Reconciler inspects slots; finds `BEFORE / BEFORE`. Marks `T123` `ABORTED`. | Safe no-op abort; zero blind refund or deduction. |
| **C. Crash after source live mutation but before destination live mutation** | `INTENT` persisted | Source matches `after_fingerprint`; Destination matches `before_fingerprint` | Reconciler inspects slots; detects partial apply (`AFTER / BEFORE`). Reverts source to `before_fingerprint`, bumps OCC, marks `ABORTED`. | Never blindly duplicate destination or lose source items. |
| **D. Crash after both live mutations but before canonical SQL finalization** | `INTENT` persisted | Both match `after_fingerprint` | Reconciler detects complete in-memory application (`AFTER / AFTER`). Roll-forwards: commits SQL states ($12 \to 13$, $7 \to 8$) and marks `COMMITTED`. | Seamless progress preservation across crash. |
| **E. Crash after SQL COMMITTED** | `COMMITTED` | Both updated to target state | Any retry with `operationId = T123` is an idempotent cached no-op. | Strict operation idempotency. |

### 6.4 Verification Test Specifications
The GameMode economic inventory and portability architecture is verified by the following formal test specifications:
1. **`CompoundInventoryIntentPrecedesLiveMutationTest`:**
   - Verifies that `InventoryMutationJournal` durable `INTENT` commit strictly *happens-before* any live in-memory inventory slot mutation can execute on Folia schedulers.
2. **`CrossOwnerEconomicInventoryTransferContractTest`:**
   - Verifies that transferring items between a Player Inventory (v12) and a Vessel Cargo (v7) binds two authority scopes (`PlayerSessionAuthority` and `GameModeInstanceAuthority`) under a single `operationId`, executing atomically with OCC version increments on both rows.
3. **`CrossOwnerInventoryCrashRecoveryTest`:**
   - Verifies the full crash recovery matrix (including crash after source live apply but before destination live apply), ensuring dual before/after fingerprint reconciliation, deterministic reconciliation of supported states, fail-closed handling into RECOVERY_REQUIRED for ambiguous drift, and zero blind commits or blind refunds.
4. **`GameModeSchemaDialectPortabilityTest`:**
   - Verifies that the canonical logical schema for `game_mode_instance_authorities`, `game_mode_participations`, and mode-specific state compiles and functions identically across all supported database adapters: **PostgreSQL**, **MySQL / MariaDB**, and **SQLite**.

---

## 7. Extensible Dimension & Primary Root Architecture

### 7.1 Dimension Identity (`DimensionId`)
Dimensions are identified by an extensible namespaced identifier rather than a closed enum:

```java
public record DimensionId(NamespacedId id) implements Comparable<DimensionId> {
    public static DimensionId of(String namespacedId) {
        return new DimensionId(NamespacedId.of(namespacedId));
    }
    @Override public int compareTo(DimensionId o) { return id.compareTo(o.id); }
}

public record DimensionDefinition(
    DimensionId dimensionId,
    DimensionSemanticRole semanticRole,       // PRIMARY_OVERWORLD, NETHER, END, CUSTOM
    Optional<DimensionId> replacesVanillaDimension,
    WorldTopologyProvider topologyProvider,
    List<EnvironmentRule> environmentRules,
    DimensionLinkPolicy linkPolicy,
    TypedProviderConfig generationConfig
) {}
```

* **Example (StrangerRealms):**
  - `dimensionId = DimensionId.of("uxm:upside_down")`
  - `semanticRole = DimensionSemanticRole.CUSTOM`
  - `replacesVanillaDimension = Optional.of(DimensionId.of("minecraft:the_nether"))`
  - `linkPolicy = new SynchronizedMirrorLinkPolicy()`

### 7.2 Extensible Primary Gameplay Root (`PrimaryGameplayRootRef`)
```java
public record PrimaryGameplayRootRef(
    NamespacedId rootTypeId, // "uxm:island", "uxm:vessel", "uxm:course_plot", "uxm:creative_plot"
    UUID aggregateId,
    WorldPosition primarySpawnPosition
) {}
```

---

## 8. Provider Configuration & Auto-Update Semantics

### 8.1 Typed Provider Configuration (`TypedProviderConfig`) & `DiagnosticContext`
To prevent unchecked reflection, classloader leaks, and unvalidated `Map<String, Object>` blobs, all diagnostic contextual state uses `DiagnosticContext` (strongly typed, immutable map of diagnostic primitives), and all provider configurations utilize a closed serializable value model:

```java
public record ProviderRef<T>(
    NamespacedId providerId,
    TypedProviderConfig config
) {
    public static <T> ProviderRef<T> of(String providerId, TypedProviderConfig config) {
        return new ProviderRef<>(NamespacedId.of(providerId), config);
    }
}

public sealed interface ConfigValue permits
    ConfigValue.StringValue,
    ConfigValue.IntValue,
    ConfigValue.DoubleValue,
    ConfigValue.BooleanValue,
    ConfigValue.NamespacedIdValue,
    ConfigValue.ListNode {

    record StringValue(String value) implements ConfigValue {}
    record IntValue(int value) implements ConfigValue {}
    record DoubleValue(double value) implements ConfigValue {}
    record BooleanValue(boolean value) implements ConfigValue {}
    record NamespacedIdValue(NamespacedId value) implements ConfigValue {}
    record ListNode(List<ConfigValue> items) implements ConfigValue {}
}

public record TypedProviderConfig(
    NamespacedId schemaId,
    int schemaVersion,
    Map<String, ConfigValue> entries
) {}
```

### 8.2 Strict `AUTO_SAFE_UPDATE` Verification
An instance content pack update may only be categorized as `AUTO_SAFE_UPDATE` if:
1. The provider/content pack explicitly declares a backward-compatible migration descriptor, AND
2. The schema and content integrity validator confirms that no existing stage IDs, milestone triggers, or currency rates are mutated or dropped.
In all other circumstances, updates default to `PINNED` or require explicit administrator execution under `MIGRATED`.

---

## 9. Authority, Ownership & Cardinality Matrix

| Target Mode | Primary Root Type | Persistent State Owner | Authority Scope Binding | Economic Inventories | Territory Cardinality | Progression Cardinality |
| :--- | :--- | :--- | :--- | :--- | :---: | :---: |
| **SkyBlock** | `uxm:island` | `islands` + `island_members` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Fixed Cuboid) | 1 (Island Level) |
| **OneBlock** | `uxm:island` | `oneblock_instance_progress` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Fixed Cuboid) | 1 (Phases) |
| **ChunkBlock** | `uxm:island` | `chunkblock_territory_claims` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Chunk Graph) | 2 (Phases + Territory) |
| **AcidIsland** | `uxm:island` | `islands` + `acid_state` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Fixed Cuboid) | 1 (Island Level) |
| **CaveBlock** | `uxm:island` | `islands` + `subterranean` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Fixed Cuboid) | 1 (Island Level) |
| **SkyGrid** | `uxm:island` | `islands` + `grid_state` | `ISLAND` (`island_authorities`) | Personal + Island Vault | 1 (Fixed Cuboid) | 1 (Island Level) |
| **Boxed** | `uxm:territory_claim` | `boxed_instance_state` | `GAME_MODE_INSTANCE` | Personal | 1 (Expanding Box) | 1 (Advancements) |
| **Poseidon** | `uxm:underwater_claim`| `poseidon_instance_state` | `GAME_MODE_INSTANCE` | Personal | 1 (Fixed Cuboid) | 0..1 (Lore Quests) |
| **StrangerRealms**| `uxm:multidim_claim` | `stranger_claims` | `GAME_MODE_INSTANCE` | Personal + Realm Vault | 3 (Overworld, Upside Down, End) | 1 (Cross-Realm) |
| **TradeWinds** | `uxm:vessel` | `tradewinds_vessels` | `GAME_MODE_INSTANCE` (Vessel) | Personal + Vessel Cargo | **0 Initially** (Optional Islet) | 2 (Trade + Vessel Rank) |
| **Parkour** | `uxm:course_plot` | `parkour_courses` | `GAME_MODE_INSTANCE` (Course) | Personal (None in run) | 1 (Course Plot) | 1 (Timed Runs) |
| **Brix (Creative)**| `uxm:creative_plot` | `brix_plots` | `GAME_MODE_INSTANCE` (Plot) | None | 1 (Plot Area) | **0 (None)** |

---

## 10. Anti-Hardcode Verification (Extensibility Audit)

To guarantee that the core engine remains 100% extensible and decoupled from enum changes:

1. **Can a third-party custom `DimensionId` be added without core enum change?**
   * **YES.** `DimensionId` is a typed wrapper around `NamespacedId`. Any plugin can register `DimensionId.of("myserver:mining_realm")` without altering core code.
2. **Can a third-party custom `PrimaryGameplayRootRef` type be added without core enum change?**
   * **YES.** `PrimaryGameplayRootRef` wraps `NamespacedId rootTypeId`. A custom factory mode can register `NamespacedId.of("factory:conveyor_plot")`.
3. **Can a non-island mutable root obtain fenced authority without core mode-specific `if` branches?**
   * **YES.** Non-island roots bind to `AuthorityBinding.GameModeInstanceAuthorityBinding`, which queries `game_mode_instance_authorities` using the exact same generic lease/epoch fencing SQL queries.
4. **Can a new economic inventory owner reuse journal semantics without being a `ProfileInventory`?**
   * **YES.** `DurableEconomicInventoryPort` operates on `EconomicInventoryId(NamespacedId ownerType, UUID ownerId)`. A vessel cargo or guild bank implements this port and reuses `InventoryMutationJournal` with zero profile coupling.
5. **Can a game mode provide custom starter bundles and multi-dimension structures without raw console commands?**
   * **YES.** `StartTemplateBundle` maps assets by `DimensionId` and executes typed `CreationAction` via registered `CreationActionProvider` SPIs.
6. **Can dynamic scoring metrics be added without hardcoding island level equations?**
   * **YES.** `LeaderboardMetricProvider` allows any game mode or addon to expose custom metrics (e.g. `season:score`, `tradewinds:trade_volume`) with strongly typed values and consistency levels.
7. **Can instance node allocation be customized without modifying the core authority engine?**
   * **YES.** `PlacementStrategyProvider` enables custom node selection heuristics (`ROUND_ROBIN`, `LEAST_LOADED`, `MSPT_AWARE`, `CAPACITY_WEIGHTED`) completely separated from canonical SQL authority.
8. **Can offline catch-up progression be supported without world-loading hacks?**
   * **YES.** `OfflineProgressionProvider` allows game modes to define deterministic mathematical catch-up on instance load (`RECONCILED_ON_LOAD`), strictly preventing simulated chunk loading of idle worlds.

---

## 11. Competitive Architectural Capabilities Integration

The Product-Owner approved competitive features are integrated into the living domain architecture:

### 11.1 Temporary Access Grants & Distributed Termination Anchors
* **Aggregate Model:** `TemporaryAccessGrant(grantId, targetInstanceId, targetRootRef, granteeProfileId, grantedByProfileId, accessPolicy, createdAt, expiresAt, terminationPolicy)`.
* **Root Reference:** Fully qualified via `PrimaryGameplayRootRef(rootTypeId, rootKey)`, never assuming every gameplay root is an island UUID.
* **Termination Policies & Distributed Anchors:**
  - `UNTIL_REVOKED`: Persists until explicitly revoked by an authorized profile.
  - `UNTIL_SESSION_END` (or `UNTIL_LOGOUT`): Anchored to canonical player session generation (`anchorPlayerUuid`, `anchorSessionEpoch` referencing `player_sessions.session_epoch`).
    - *Anchor Invariant:* `anchorPlayerUuid` MUST belong to `granteeProfileId`.
    - *Exact Validity Predicate:* The grant remains session-valid if and only if canonical `player_sessions` represents the *exact same live session generation*:
      - Session row absent $\implies$ **EXPIRED**.
      - Session epoch different (`player_sessions.session_epoch != temporary_access_grants.anchor_session_epoch`) $\implies$ **EXPIRED**.
      - Same epoch but session is not actively live (`state != 'ACTIVE'` e.g. `DRAINING`, `HANDOFF_READY`, `RECOVERING`, `OFFLINE`, `LOCAL_FENCED`, or `lease_expires_at < CURRENT_TIMESTAMP`) $\implies$ **EXPIRED**.
    - Reuses approved canonical session lifecycle/lease semantics directly; never invents new session states or lifecycles.
  - `NODE_PROCESS_RESTART`: Node-local convenience policy evaluated against canonical runtime identity `CurrentNodeProcessIdentity(nodeId, processGenerationId)`.
    - *Exact Validity Predicate:* Invariant `current node/process identity != stored anchor identity` (i.e. `currentNodeProcessIdentity.nodeId() != anchorNodeId || !currentNodeProcessIdentity.processGenerationId().equals(anchorProcessGenerationId)`) $\implies$ **EXPIRED**. Strictly node-local; does NOT become distributed authority and does NOT require Redis for correctness.
  - `UNTIL_TIMESTAMP`: Time-bounded expiration evaluated against database clock.
* **Normalized Permission Model:** Persisted via normalized relational schema (`temporary_access_grants` + `temporary_access_grant_permissions(grant_id, permission_key)`). Stable namespaced `PermissionKey`s remain canonical.
* **Strict Boundary:** `TemporaryAccessGrant` is strictly isolated from `GameModeParticipation` and `IslandMember`. A temporary trust grant never grants permanent membership, bank management, voting rights, or co-op succession status.
* **Ruleset Isolation:** Ruleset constraints (e.g. Ironman trade barriers) are enforced during access evaluation; temporary trust cannot be used as an exploit channel to transfer items or bypass game mode boundaries.

### 11.2 Compiled Namespaced Permission Registry
* **Components:** `PermissionKey` (stable namespaced string, e.g. `uxm:block.break`) $\longrightarrow$ compiled at startup $\longrightarrow$ `PermissionId` (dense integer 0..N, runtime-only) $\longrightarrow$ `PermissionSet` (compact bitset).
* **Hot-Path Invariant:** Once subject/role/context resolution is complete, compiled permission evaluation uses dense `PermissionId` + `PermissionSet` bit lookup without per-check configuration parsing or namespaced-key map traversal. Context resolution itself legitimately utilizes indexed/cached lookups.
* **Visitor Extensions:** Visitor policies are expressed via compiled permissions (interaction, container opening, command restrictions, PvP/damage, item pickup/drop, portals, mobs, temporary trust overrides).

### 11.3 Durable Reward Inbox & Multi-Protocol Delivery
* **Model:** `RewardGrant(grantId, recipientProfileId, sourceType, sourceId, state, createdAt, expiresAt)` + `RewardGrantComponent(componentId, grantId, componentIndex, componentOperationId, componentType, payloadTypeId, payloadSchemaVersion, payloadData, state, journalOperationId)`.
* **Identity Hierarchy & Isolation Invariant:**
  - `RewardGrantId`: Parent aggregate / correlation identity for the overall reward package.
  - `RewardComponentOperationId`: Durable idempotency identity of one specific component delivery.
  - *Isolation Invariant:* Different reward components MUST NOT blindly reuse the parent grant ID across `InventoryMutationJournal`, `processed_operations`, SQL economic OCC, or external economy sagas.
  - *Component Operation Stability:* Each component receives one stable operation ID (`componentOperationId`) identical across retries of that component, deterministically derived from `grantId + componentIndex` (or explicitly persisted).
  - Invariants: `same grant + same component` $\implies$ `same component operation ID`; `different components` $\implies$ `different component operation IDs`.
  - Relational uniqueness: `UNIQUE (grant_id, component_index)` and `UNIQUE (component_operation_id)`.
  - Protocol operation references (`journalOperationId` or equivalent) MUST represent `componentOperationId`, never ambiguously the parent grant ID.
* **Lifecycle:** `PENDING` $\to$ `CLAIMING` $\to$ `CLAIMED` (or `EXPIRED`, `RECOVERY_REQUIRED`). The parent grant transitions to `CLAIMED` only after all required components are durably completed (`state = 'COMMITTED'`).
* **Multi-Protocol Delivery Orchestration (`RewardClaimCoordinator`):**
  - Minecraft / durable economic inventory items $\longrightarrow$ `InventoryMutationJournal` (expected version, OCC, authority fencing, before/after fingerprints, keyed by `componentOperationId`).
  - SQL-owned currencies / bank balances $\longrightarrow$ canonical economic OCC + `processed_operations` / `bank_transactions` (keyed by `componentOperationId`).
  - External Vault / external economy side effects $\longrightarrow$ saga / idempotency recovery contracts (keyed by `componentOperationId`).
  - Non-economic rewards (cosmetics, permissions) $\longrightarrow$ owning bounded-context protocol.
* **Per-Component Progress & Crash Recovery:** Per-component progress is tracked in `reward_grant_components`. If a crash occurs mid-claim, completed components are never re-granted, preventing duplication. Does not invent a new transaction protocol.

### 11.4 Lifecycle & Reset Policy Framework
* **Orchestration:** Replaces scattered event listeners with unified `LifecycleTransition` handlers (`PROFILE_JOINS_INSTANCE`, `PROFILE_LEAVES_INSTANCE`, `PROFILE_KICKED`, `INSTANCE_RESET`, `INSTANCE_DISBAND`, `PROFILE_DEATH`, `INSTANCE_CREATED`).
* **Ruleset Sensitivity:** Composition of Ruleset $\times$ GameMode governs the outcome for player inventories, ender chests, bank balances, XP, health, spawn points, and reset cooldowns. Hardcore lifecycle behavior is supplied by the existing Hardcore Ruleset policy without inventing unapproved death outcomes in this pass.

---

## 12. Architectural Contract Test Specifications

The following 38 architecture contract test suites are formally specified for Phase 1 verification (specification defined; implementation deferred to Phase 1 in strict accordance with governance):

1. **`TemporaryAccessDoesNotCreateMembershipTest`:** Asserts that issuing a `TemporaryAccessGrant` never creates rows in `game_mode_participations` or `island_members`, nor confers team ownership/bank rights.
2. **`TemporaryAccessExpiryPolicyContractTest`:** Asserts that grants expire deterministically under `UNTIL_REVOKED`, `UNTIL_SESSION_END`, `NODE_PROCESS_RESTART`, and `UNTIL_TIMESTAMP`.
3. **`PermissionRegistryCollisionTest`:** Asserts that registering duplicate `PermissionKey` names across providers fails fast and deterministically during initialization.
4. **`CompiledPermissionStableKeyRoundTripTest`:** Asserts that namespaced keys compile to dense integer bitset indexes and resolve back to identical canonical keys without semantic drift.
5. **`RewardClaimIdempotencyContractTest`:** Asserts that duplicate or concurrent claim requests for the same `RewardGrant` execute idempotently without duplicating currency or items.
6. **`RewardClaimInventoryCrashRecoveryTest`:** Asserts that a simulated node crash during multi-component reward claiming is safely recovered without data loss, partial state leakage, or duplicate delivery.
7. **`StartTemplateBundleDimensionResolutionTest`:** Asserts that `StartTemplateBundle` correctly resolves assets and boundaries across arbitrary custom `DimensionId`s.
8. **`CreationActionProviderCollisionTest`:** Asserts that conflicting `CreationAction` provider registrations fail fast during bootstrap.
9. **`LeaderboardMetricProviderContractTest`:** Asserts that dynamic metric providers register, sort, and expose authoritative read contracts across generic gameplay roots.
10. **`LeaderboardProjectionIsNonCanonicalTest`:** Asserts that corrupting or clearing Redis leaderboard projections does not impact the canonical domain source of truth.
11. **`PlacementDoesNotGrantAuthorityTest`:** Asserts that `PlacementStrategy` node recommendations do not acquire authority leases or advance `authority_epoch`.
12. **`RoutingCacheDoesNotOverrideSqlAuthorityTest`:** Asserts that requests routed via stale Redis entries are rejected by SQL epoch fencing.
13. **`ActivityFeedProjectionFailureDoesNotRollbackDomainCommitTest`:** Asserts that outbox-backed projection failures in the user-facing activity feed do not abort committed domain business transactions and are safely retried.
14. **`OfflineNotificationDurabilityTest`:** Asserts that offline notifications persist in SQL across node restarts and deliver cleanly upon player profile login.
15. **`SocialRatingIdempotencyTest`:** Asserts that updating a rating updates the existing record idempotently without corrupting rating aggregation.
16. **`LifecyclePolicyCompositionTest`:** Asserts that Ruleset $\times$ GameMode policy composition produces exact deterministic outcomes for leave, kick, death, and reset events.
17. **`MultiHomeDimensionPolicyTest`:** Asserts that homes validate dimension rules, player permissions, and game-mode capability toggles.
18. **`RulesetCannotBeBypassedByTemporaryTrustTest`:** Asserts that Ironman and Hardcore restriction boundaries cannot be circumvented via `TemporaryAccessGrant` permissions.
19. **`RewardComponentOperationIdentityIsolationTest`:** Asserts that different components within the same `RewardGrant` receive distinct `RewardComponentOperationId`s, preventing operation ID collisions in `InventoryMutationJournal` and `processed_operations`.
20. **`RewardComponentRetryUsesStableOperationIdTest`:** Asserts that retrying a failed or uncommitted reward component delivery uses the identical deterministic `RewardComponentOperationId` (`grantId + componentIndex`), ensuring true idempotency without generating duplicate operations.
21. **`TemporaryAccessCannotAnchorToDifferentPlayerTest`:** Asserts that a `TemporaryAccessGrant` with `UNTIL_SESSION_END` cannot anchor to a player UUID different from the grantee profile's owning player account (`anchor_player_uuid` must belong to `grantee_profile_id`).
22. **`TemporaryAccessSessionGenerationExpiryTest`:** Asserts that a session-bound grant evaluates as expired when the player's session row is absent in `player_sessions`, when `session_epoch` differs from `anchor_session_epoch`, or when session state transitions away from `ACTIVE` (e.g. `OFFLINE`, `HANDOFF_READY`, expired lease).
23. **`NodeProcessRestartGrantExpiryTest`:** Asserts that a `NODE_PROCESS_RESTART` grant is evaluated against `CurrentNodeProcessIdentity(nodeId, processGenerationId)` and immediately expires when the process generation ID or node ID mismatches, functioning strictly node-locally without requiring Redis or distributed synchronization.
24. **`ObjectStorageStreamingTransferContractTest`:** Asserts that `ObjectStoragePort` streaming uploads and downloads transfer large objects with bounded memory usage, without buffering full multi-gigabyte files into the JVM heap.
25. **`ObjectStorageMultipartRecoveryContractTest`:** Asserts that an interrupted multipart object upload can safely abort or resume and retry without leaking orphaned parts or corrupting the target object.
26. **`ObjectStorageProviderCapabilityValidationTest`:** Asserts that configuring a storage provider lacking required capability flags (e.g. `MULTIPART_UPLOAD` or `RANGE_READ`) causes application startup or operation preparation to fail fast and fail closed.
27. **`ObjectStorageChecksumVerificationTest`:** Asserts that object transfers calculate and verify strong application-level SHA-256 checksums, detecting single-bit corruptions independently of provider ETag formats.
28. **`ObjectStorageCredentialRedactionTest`:** Asserts that access keys, secret keys, and session tokens are strictly redacted from diagnostic logs, exception traces, and manifest serialization.
29. **`S3CompatibleProviderAwsCompatibilityContractTest`:** Asserts that the S3-compatible adapter negotiates AWS S3 endpoints, headers, addressing modes, and multipart upload protocols conforming to verified AWS S3 semantics.
30. **`S3CompatibleProviderR2CompatibilityContractTest`:** Asserts that the S3-compatible adapter negotiates Cloudflare R2 endpoints, authentication, and supported capabilities conforming to verified R2 semantics.
31. **`GenericS3EndpointCapabilityNegotiationTest`:** Asserts that generic custom S3-compatible endpoints negotiate capability subsets safely and reject unsupported advanced operations before execution.
32. **`BackupSetPublicationIdempotencyTest`:** Asserts that re-running or retrying an interrupted `BackupSet` publication produces an identical canonical manifest without creating duplicate or conflicting backup entries.
33. **`BackupSetMissingArtifactFailsClosedTest`:** Asserts that attempting to discover or restore a `BackupSet` with one or more missing mandatory artifact files fails closed and marks the set `FAILED` or `PARTIAL`.
34. **`BackupRestoreChecksumMismatchFailsClosedTest`:** Asserts that attempting a restore with an artifact whose SHA-256 hash does not match the manifest immediately aborts prior to executing any destructive world or database operations.
35. **`BackupRestoreAuthorityFenceTest`:** Asserts that restoring an island or GameMode root acquires and validates canonical authority fencing, and fails closed if the authority epoch or DB version advances during preparation.
36. **`DatabaseBackupDialectConsistencyContractTest`:** Asserts that `DatabaseBackupPort` resolves the configured SQL dialect to a supported adapter, captures backups using dialect-correct consistency mechanisms, packages complete artifacts with verified integrity, redacts credentials, ensures cancel/retry safety, records restore compatibility metadata, and fails closed on unsupported dialects (testing semantic contract rather than specific command-line utilities).
37. **`MirroredBackupCompletionPolicyTest`:** Asserts that a `MIRRORED` storage policy marks a `BackupSet` `AVAILABLE` in the catalog only after both local filesystem and remote S3-compatible destinations have durably succeeded.
38. **`MirroredBackupDeletionRecoveryTest`:** Asserts recoverable multi-destination retention deletion (`AVAILABLE` $\to$ `DELETING` $\to$ per-destination cleanup $\to$ `DELETED`), verifying that a partial destination failure (e.g. local deletion succeeds while remote deletion fails) persists progress, keeps the set disqualified from restore in `DELETING` / `RECOVERY_REQUIRED`, and retries safely without assuming impossible cross-provider atomic rollbacks.
39. **`BackupManifestImmutableAfterPublicationTest`:** Asserts that `BackupManifest` (`manifest.json`) is immutable once published, contains no continuously mutable operational state fields (`status = UPLOADING`), and cannot be overwritten by subsequent lifecycle transitions.
40. **`BackupCatalogLifecycleIndependentFromManifestTest`:** Asserts that operational metadata (`BackupOperation` in SQL `backup_operations`) tracks mutable lifecycle progression (`STAGED`, `UPLOADING`, `DELETING`) independently of the immutable `manifest.json`.
41. **`BackupDiscoveryWithoutLiveDatabaseTest`:** Asserts that in the event of a total SQL database loss, remote and local storage scans discover and validate `BackupSet`s using self-contained `manifest.json` files and `AVAILABLE.marker`s, recognizing backups as available restore candidates only when both are present and valid.
42. **`RootRollbackCannotTriggerFullDatabaseRestoreTest`:** Asserts that performing a rollback of a single gameplay root (e.g. `/is admin rollback <island_id>`) restores only the target root's world snapshot and declared dependent state via `RootStateSnapshotPort`, and is strictly blocked from initiating or triggering a full database restore.
43. **`RootStateSnapshotIsolationTest`:** Asserts that `RootStateSnapshotPort` captures and exports only root-scoped relational records and world chunks, preserving unrelated roots and global platform state untouched.
44. **`DatabaseDisasterBackupRestoreScopeTest`:** Asserts that `DatabaseBackupPort` restore operations require explicit administrative disaster-recovery confirmation and operate on the whole relational database, distinct from root-level snapshot rollbacks.
45. **`ProviderCompatibilityCannotBeClaimedBeforeSuitePassesTest`:** Asserts that the system marks AWS S3 and Cloudflare R2 as verified targets only when their respective compliance contract test suites have executed and passed; unverified providers fail closed when strict verification is required.
46. **`AwsCompatibilityRequiresRealProviderEndpointTest`:** Asserts that `S3CompatibleProviderAwsCompatibilityContractTest` requires execution against a real AWS S3 endpoint/account, and that passing against an emulator cannot upgrade AWS S3 to verified status.
47. **`R2CompatibilityRequiresRealProviderEndpointTest`:** Asserts that `S3CompatibleProviderR2CompatibilityContractTest` requires execution against a real Cloudflare R2 endpoint/account, and that passing against an emulator cannot upgrade Cloudflare R2 to verified status.
48. **`EmulatorCannotPromoteProviderToVerifiedTest`:** Asserts that passing test suites on local emulators or compatible mock servers verifies only generic S3 adapter behavior and strictly cannot promote AWS S3 or Cloudflare R2 to verified compatibility targets.
49. **`RootSnapshotDoesNotLeakWorldAccessIntoPersistenceAdapterTest`:** Asserts that `RootRelationalSnapshotPort` and `:persistence-adapter` have zero compile-time or runtime dependencies on Bukkit/Paper or Minecraft world/chunk classes, with world/dimension capture strictly isolated to `WorldDimensionSnapshotPort` in `:bukkit-adapter`.
50. **`BackupQuiesceHasNoUnverifiedNumericSloTest`:** Asserts that mutation-quiesce contracts enforce a bounded quiesce window without asserting unverified numerical SLOs ($p99 < 250\text{ms}$) as release-blocking architecture invariants.
51. **`HybridDurabilityRoutesToOwningSubsystemProtocolTest`:** Asserts that under the Hybrid durability model, critical mutations route strictly to their owning subsystem's approved durability protocol (Minecraft items $\to$ `InventoryMutationJournal`, SQL bank $\to$ SQL OCC/operation protocol, external economy $\to$ saga, profile switch $\to$ `player_sessions` protocol, trade $\to$ compound asset exchange protocol).
52. **`HybridDurabilityDoesNotUseUniversalInventoryJournalTest`:** Asserts that `InventoryMutationJournal` is strictly scoped to economic item inventory mutations and is not used as a universal journal for non-inventory operations like bank balance transfers or profile switches.
53. **`BackupAvailabilityMarkerPublishedLastTest`:** Asserts that during backup publication, the availability marker (`AVAILABLE.marker`) is uploaded to object storage strictly as the final step after all artifacts and `manifest.json` are committed and checksum-verified.
54. **`BackupDeletionInvalidatesDiscoveryBeforeArtifactCleanupTest`:** Asserts that when retention deletion commences, the availability marker is deleted or tombstoned in object storage before individual artifact objects are deleted, immediately hiding the set from disaster-recovery discovery.
55. **`BackupDiscoveryRejectsMissingAvailabilityMarkerTest`:** Asserts that disaster-recovery discovery scanning object storage without an active SQL catalog rejects any backup set lacking a valid availability marker, even if `manifest.json` and artifact objects exist.
56. **`BackupDeletionRemainsSafeAfterSqlLossTest`:** Asserts that if SQL database loss occurs while a backup is in `DELETING` state, the object-storage availability marker removal/tombstone prevents the partially pruned backup from being discovered or restored as a valid candidate.
57. **`MirroredBackupPublicationMarkerIsolationTest`:** Asserts that in a `MIRRORED` storage topology, each storage destination independently manages its own availability marker publication and removal, and failure to publish or delete on one destination does not falsely alter the state of the other.
58. **`HybridDurabilityImmediateMutationBypassesCheckpointTest`:** Asserts that critical/economic mutations requiring immediate durability bypass ambient periodic checkpoint queues and commit immediately through their owning subsystem's protocol.
59. **`HybridDurabilityRoutesImmediateMutationToOwningProtocolTest`:** Asserts that critical and value-sensitive mutations route strictly to their owning subsystem's approved durability protocol (Minecraft items $\to$ `InventoryMutationJournal`, SQL bank $\to$ SQL OCC/operation protocol, external economy $\to$ saga, profile switch $\to$ `player_sessions` protocol, trade $\to$ compound asset exchange protocol).
60. **`AmbientCheckpointDefaultIsSixtySecondsTest`:** Asserts that the default ambient checkpoint interval is 60 seconds when not explicitly overridden in typed configuration.
61. **`AmbientCheckpointIntervalIsConfigurableTest`:** Asserts that the ambient checkpoint interval is loaded from typed configuration (`player-state.ambient-checkpoint-interval`) and dynamically configures the scheduled checkpoint cadence without hardcoded numerical constants.
62. **`AmbientCheckpointDoesNotBypassPlayerSessionAuthorityTest`:** Asserts that periodic ambient checkpoints write player aggregate state strictly through the canonical `player_sessions` row lock and OCC fencing (`authoritative_node`, `session_epoch`, `lease_expires_at`), with no weaker write path.
63. **`AmbientCheckpointVsSessionTakeoverSerializationTest`:** Asserts that a concurrent session takeover or planned acquire blocks on the canonical `player_sessions` row lock until an active ambient checkpoint transaction completes or rolls back; if the session has transitioned away from `ACTIVE`, the ambient checkpoint aborts without writing.
64. **`ImmediateMutationIsNotDowngradedByScheduledCheckpointTest`:** Asserts that an immediate mutation is never downgraded to delayed/checkpointed durability simply because an ambient checkpoint is scheduled or impending.
65. **`FailedAmbientCheckpointRetainsDirtyStateTest`:** Asserts that if an ambient checkpoint fails (e.g. transient DB error), dirty state is retained/re-marked in memory for subsequent retry and never silently cleared or dropped.
66. **`QuitFlushDoesNotWaitForAmbientCheckpointTest`:** Asserts that player disconnect/quit initiates an immediate asynchronous snapshot flush and does not wait for the next periodic ambient checkpoint.
67. **`ProfileSwitchDoesNotWaitForAmbientCheckpointTest`:** Asserts that a profile switch executes its independent multi-phase `profile_switch_operations` protocol immediately and does not defer state persistence to the ambient checkpoint loop.
68. **`HandoffFinalFlushDoesNotWaitForAmbientCheckpointTest`:** Asserts that cross-node handoff completes its final durable commit under canonical session authority immediately without waiting for the ambient checkpoint interval.
69. **`AmbientCheckpointRejectedAfterSessionLeavesActiveTest`:** Asserts that a routine ambient checkpoint prepared during `ACTIVE` state cannot commit if `player_sessions` transitions to `DRAINING` or another non-`ACTIVE` state (`HANDOFF_READY`, `RECOVERING`, `OFFLINE`, `LOCAL_FENCED`) before commit-time authority validation, aborting without canonical write and without clearing ambient dirty state.

---

## 13. Governance & Verification Summary

* **Implementation started:** NO
* **Phase 1 started:** NO
* **Production Java written:** NO
* **Test implementation written:** NO
* **Migration implementation written:** NO
* **Commit performed:** NO
* **Push performed:** NO
* **Architecture board state:** `FROZEN`
* **Architecture frozen by Product Owner:** `YES (2026-09-13)`
* **Product Decision Conflicts:** NONE FOUND (All Product Owner decisions, including the Hybrid Ambient Player-State Durability Model, V1 complete-scope mandate, and general object storage architecture, are fully resolved and reconciled across all living specifications).
