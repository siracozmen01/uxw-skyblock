# Platform & Technology Stack: Paper 26.2+, Folia, Bedrock & uxm-lib

## 1. Platform Requirements

| Component | Requirement |
| :--- | :--- |
| **Server Platform** | **Paper 26.2+** or **Folia** |
| **Java Environment** | **Java 25+** (JvmVendor: Adoptium) |
| **Build System** | **Gradle (Kotlin DSL)** with `buildSrc` conventions |
| **Core Foundation Library** | **`uxm-lib` (UXPLIMA Toolkit)** |
| **Bedrock Middleware** | **Geyser / Floodgate** (via `uxmlib-bedrock` & `uxmlib-menu`) |

---

## 2. Native V1 Folia Concurrency Model

Folia breaks Minecraft's single main thread into multiple independent region threads ticking distinct chunks concurrently. To run natively on Folia in V1, the following rules are non-negotiable:

### 2.1 Absolute Ban on `BukkitScheduler`
* Any call to `Bukkit.getScheduler()`, `plugin.getServer().getScheduler()`, `BukkitRunnable`, `runTask()`, `runTaskLater()`, or `runTaskTimer()` is **strictly prohibited**.
* Core/application code uses the platform-neutral Skyblock `SchedulerPort`; the Bukkit adapter implements it using `uxmlib-common`'s Folia-ready `Scheduler`.

### 2.2 Hexagonal Scheduler Port & Adapter Pattern
In alignment with the immutable `uxmEssentials` house architecture, `uxmlib-common`'s `Scheduler` operates on Bukkit types (`org.bukkit.Location`, `org.bukkit.entity.Entity`, `org.bukkit.World`) and therefore resides strictly on the adapter side:
```
:core
  ↓
Platform-neutral application SchedulerPort (PlayerRef/PlayerId, WorldPosition, WorldId)
  ↓
Implemented by :bukkit-adapter (FoliaSchedulerAdapter)
  ↓
uxmlib-common Scheduler
  ↓
Paper / Folia scheduler APIs (RegionScheduler, EntityScheduler, GlobalRegionScheduler, AsyncScheduler)
```
Core port inputs must use platform-neutral identities/value objects such as `PlayerRef` / `PlayerId`, `WorldPosition`, and `WorldId`, and MUST NOT use `org.bukkit.entity.Player`, `Entity`, `Location`, or `World`.

* **Entity Operations:** When interacting with a player or entity (e.g. teleporting to island, giving items, opening inventory), core schedules via `SchedulerPort.entity(playerRef)`, which `:bukkit-adapter` executes on `uxmlib-common`'s `scheduler.entity(player)`.
* **World & Block Operations:** When pasting a schematic, modifying blocks, or updating a chunk within an island boundary, core schedules via `SchedulerPort.region(worldId, chunkX, chunkZ)`, which `:bukkit-adapter` executes on `uxmlib-common`'s `scheduler.region(world, chunkX, chunkZ)`.
* **Global Server Operations:** For broadcast messages, global leaderboard refreshes, or tick metrics, core schedules via `SchedulerPort.global()`, mapped to `scheduler.global()`.
* **Async Operations:** For database I/O, file reading, and network calls, core schedules via `SchedulerPort.async()`, mapped to `scheduler.async()`.

---

## 3. V1 Geyser & Bedrock Support

Bedrock players connecting via Geyser/Floodgate must experience a first-class, touch-friendly UI rather than clumsy chest emulation where appropriate.

### 3.1 Automatic Dual-Platform UI (`uxmlib-menu`)
* `uxmlib-menu` automatically detects whether the viewer is a Java player or a Bedrock player via `BedrockDetector` and delivers:
  - **Java Players:** Native Custom Inventory GUI (Chest, Hopper, etc.).
  - **Bedrock Players:** Native Geyser/Floodgate Forms (SimpleForm, ModalForm, CustomForm).
* All menus defined in `menus/*.conf` provide native Bedrock button mappings, descriptions, and icon bindings without duplicating logic.

---

## 4. Mandatory `uxm-lib` Module Mapping

To prevent re-implementing functionality already provided by `uxm-lib`, the following module mapping is strictly enforced across the plugin:

| Feature / Requirement | `uxm-lib` Module | What Must Be Used (Never Re-implemented) |
| :--- | :--- | :--- |
| **Scheduling** | `:core` (`SchedulerPort`) / `:bukkit-adapter` (`uxmlib-common`) | Platform-neutral application SchedulerPort implemented via `uxmlib-common` `Scheduler` abstraction (global, region, entity, async). |
| **Text & Colors** | `uxmlib-common` | `Text.component()`, `Theme`, `Styler`, StyleTokens (`<primary>`, `<accent>`). |
| **Config & i18n** | `uxmlib-common` | `HoconConfig`, `RecordConfig`, `ConfigProperty`, Message Catalog. |
| **Commands** | `uxmlib-command` | Brigadier `Cmd`, `@Command`, `@Subcommand`, `@Arg`, `@Range`, `@Cooldown`. |
| **Items & Heads** | `uxmlib-item` | `ItemBuilder`, `SkullData`, PDC helpers, Item serialization. |
| **Menus & Dialogs** | `uxmlib-gui` & `uxmlib-menu` | `Menus`, `Guis`, `TextInput`, `MenuSpecLoader`, `ActionRegistry`. |
| **Bedrock Forms** | `uxmlib-bedrock` | `BedrockDetector`, `BedrockScreen`, `BedrockButton`, `BedrockIcons`. |
| **Persistence** | `uxmlib-storage` | HikariCP `Database`, `Sql`, `TxSql`, versioned SQL migrations, Caffeine cache. |
| **HUD & Overlays** | `uxmlib-hud` | Flicker-free Sidebar, ActionBars, BossBars, Tablist, Nametags. |
| **Condition & Economy**| `uxmlib-condition` | Condition/Action engine, multi-economy `Wallet` abstraction. |
| **Integrations** | `uxmlib-integration` | PlaceholderAPI expansion, Vault / Treasury, WorldGuard region queries. |
