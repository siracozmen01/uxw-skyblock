# Naming & Method Standards

## 1. Universal English Language Mandate

* **100% English:** Every identifier (package name, class, interface, record, enum, method, field, parameter, local variable, constant, and log message) must be in grammatically correct, clean English.
* **No Abbreviations:** Avoid cryptic abbreviations (e.g. use `islandConfiguration` instead of `islCfg`; `playerLocation` instead of `pLoc`).
* **Only Exception:** Turkish is reserved strictly for communicating with the product owner in conversational chat.

---

## 2. Type Naming Conventions

* **Classes:** UpperCamelCase (PascalCase). Must be concrete noun phrases representing a single concept:
  - Good: `IslandRepository`, `CreateIslandCommand`, `IslandLevelCalculator`.
  - Bad: `IslandManager` (too vague / god-class smell), `DoIsland` (verb), `IslandClass`.
* **Interfaces:** UpperCamelCase. Describe contracts or roles:
  - Use clear nouns or adjectives indicating capability: `IslandStore`, `DamageListener`, `Calculable`.
  - **No Hungarian Notation:** Never prefix interfaces with `I` (e.g. `IIsland` is strictly forbidden; use `Island` or `IslandService`).
* **Records (Value Objects & DTOs):** UpperCamelCase. Concise nouns:
  - Good: `IslandId`, `GridCoordinate`, `IslandBoundary`, `MemberSummary`.
* **Enums:** UpperCamelCase for enum type, `UPPER_SNAKE_CASE` for values:
  - Example: `IslandRole { OWNER, MODERATOR, MEMBER, VISITOR }`.
* **Wiring Classes:** Named as `<Context>Wiring`:
  - Examples: `IslandWiring`, `UpgradeWiring`, `ProtectionWiring`.

---

## 3. Method Design & Conventions

### 3.1 Method Size & Complexity
* **Length Limit:** A method should not exceed **25 to 30 lines**. If a method grows longer, it must be decomposed into well-named private helper methods.
* **Single Responsibility:** Each method must do exactly one thing.
* **Parameter Budget:** Methods should accept at most **3 to 4 parameters**. If more arguments are required, group them into a cohesive record / command object (e.g. `CreateIslandRequest`).

### 3.2 Command-Query Separation (CQS)
* **Queries (Read-Only):**
  - Must never mutate state or produce observable side-effects.
  - Must return a value.
  - Naming: `find*()`, `get*()`, `is*()`, `has*()`, `can*()`, `calculate*()`.
  - Examples: `Optional<Island> findById(IslandId id)`, `boolean hasMember(UUID playerId)`.
* **Commands (Mutations):**
  - Mutate state or trigger actions.
  - Typically return `void`, an event, or a `Result<T>` indicating success/failure.
  - Naming: Strong active verbs (`create()`, `delete()`, `upgrade()`, `transferOwnership()`, `removeMember()`).

### 3.3 Boolean Naming
* Booleans must read like questions and start with a modal verb:
  - `isActive`, `hasExpired`, `canBuild`, `shouldNotify`, `isBanned`.
  - Never use negative booleans as flags (e.g. use `isEnabled` instead of `isNotDisabled`).

---

## 4. Variable Naming

* **Local Variables & Parameters:** lowerCamelCase. Descriptive names that convey business intent:
  - Good: `targetMemberId`, `remainingSeconds`, `newBoundary`.
  - Bad: `t`, `val`, `data`, `obj`, `temp`.
* **Constants:** `UPPER_SNAKE_CASE` for compile-time constants (`public static final`).
* **Fields:** lowerCamelCase without prefixes or suffixes (no `m_`, `s_`, or `_`).
