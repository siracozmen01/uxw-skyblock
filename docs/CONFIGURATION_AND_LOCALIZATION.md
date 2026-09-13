# Configuration & Localization Standards

## 1. Zero Hardcoding Policy (Strict Enforcement)

* **No Player-Facing Strings in Code:** No chat message, title, actionbar text, item name, or lore line may ever be hardcoded inside Java files.
* **Data-Driven Gameplay & Balance Values:** Gameplay, balance, and progression numbers—such as island sizes, member limits, upgrade costs, generator drop chances, progression thresholds, and cooldown timers—must be data-driven and defined in configuration files, never hardcoded in Java classes.
* **Technical Invariants as Typed Code Constants:** Technical invariants and protocol guarantees—such as protocol/schema version numbers, bit widths, fixed enum/state encodings, and bounded technical safety limits justified by implementation—remain strongly typed code constants. Java numeric literals are not blanket-banned where representing structural or protocol invariants.
* **No Material or Sound Constants in Code:** Any icon material, particle effect, or sound associated with a gameplay feature or menu must be configurable.
* **All Defaults Configurable:** Even default settings for newly created islands (e.g. default PvP status, default visitor permissions) must be read from configuration defaults.

---

## 2. Configuration System Architecture (`uxmlib-common`)

We use **HOCON (Human-Optimized Config Object Notation)** provided by `uxmlib-common` (`HoconConfig` / `RecordConfig`):

### 2.1 Configuration File Topology
The plugin data directory is structured cleanly:
```
plugins/uxmSkyblock/
├── config.conf           # Core mechanics, storage settings, island grid parameters
├── modules.conf          # Feature toggle switches (e.g. upgrades=true, generators=true)
├── islands.conf          # Island starter presets, biomes, boundaries, template definitions
├── upgrades.conf         # Multi-tier island upgrade trees, costs, and effects
├── generators.conf       # Cobblestone and basalt generator block drop tables
├── messages/
│   ├── messages_en.conf  # English language catalog (default)
│   └── messages_tr.conf  # Turkish language catalog
└── menus/
    ├── island-main.conf  # Data-driven main island menu (uxmlib-menu)
    ├── island-upgrades.conf
    └── island-members.conf
```

### 2.2 Strongly Typed Record Configs
- Configuration nodes must be mapped directly into immutable Java `record` objects using `RecordConfig` or `ConfigProperty` from `uxmlib-common`.
- Configuration records are validated at startup. If an operator provides invalid values (e.g. negative radius, missing template file), the plugin fails fast with a descriptive error before corrupted state can enter the domain.

---

## 3. Localization & Adventure MiniMessage Standards

### 3.1 MiniMessage Only (No Legacy Colors)
* **`§` and `&` are Strictly Forbidden:** Legacy section and ampersand color codes are rejected by static checkers and runtime parsers.
* All rich text uses Adventure MiniMessage format (e.g. `<green>Island created successfully!</green>`).

### 3.2 Semantic Styling & Theme Tokens
Messages must use semantic style tags defined in `uxmlib-common` style tokens:
* `<primary>`: Main informational text color.
* `<secondary>`: Accompanying neutral or descriptive text.
* `<accent>`: Highlights, names, coordinates, numbers.
* `<success>`: Confirmations, level-ups, successful transactions.
* `<error>`: Failure notices, permission denials, invalid arguments.
* `<warning>`: Cautionary alerts, countdowns.

### 3.3 Multi-Locale Resolution
* Messages are resolved per-player using the player's client locale (or fallback to server default).
* Interpolations and placeholders must be passed safely using MiniMessage `Placeholder.parsed()` or `Placeholder.component()`, preventing format injection attacks.
