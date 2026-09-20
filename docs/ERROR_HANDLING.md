# Error Handling & Resilience Standards

## 1. Core Principles

1. **No Silent Failures:** Never catch an exception and swallow it silently. Every caught exception must either be logged with context or translated into a domain result/typed error.
2. **Never Expose Raw Stack Traces to Players:** Technical stack traces belong strictly in the server logs at `SEVERE` or `WARNING` level with descriptive diagnostic context. Players must receive localized, human-friendly error messages.
3. **Fail-Fast Configuration & Startup:** If required files, database connections, or configurations are missing or malformed on enable, the plugin must fail fast with a crystal-clear diagnostic log instead of running in a degraded or undefined state.

---

## 2. Domain Errors vs. Infrastructure Failures

We distinguish strictly between two categories of errors:

### 2.1 Domain Validation Errors (Expected Business Failures)
* **Definition:** Scenarios where an operation cannot be fulfilled due to business rules (e.g. player is already in a team, island level is insufficient, not enough currency to upgrade).
* **Pattern:** Model expected outcomes using **Sealed Results** or explicit outcome enums instead of throwing expensive exceptions:
  ```java
  public sealed interface IslandCreationResult {
      record Success(Island island) implements IslandCreationResult {}
      record PlayerAlreadyHasIsland(IslandId existingIslandId) implements IslandCreationResult {}
      record TemplateNotFound(String templateKey) implements IslandCreationResult {}
      record WorldAllocationFailed(String reason) implements IslandCreationResult {}
  }
  ```
* **Benefits:**
  - Zero JVM stack-trace generation overhead on routine user mistakes.
  - Forces calling adapters to handle all possible domain outcomes exhaustively via compiler-checked pattern matching `switch`.
* **Expected Configuration Absence (Feature Inactive):**
  - When an optional module (e.g. Bank, Missions, Alliances) is disabled via configuration, this is an expected runtime state.
  - It MUST NEVER throw runtime exceptions (e.g. `FeatureUnavailableException` is prohibited).
  - Service queries return `Optional.empty()`; mutating actions return `IslandResult.FeatureUnavailable(featureKey)`.

### 2.2 Infrastructure & System Exceptions (Unexpected Failures)
* **Definition:** Database outages, file I/O failures, corrupted data, or network interruptions.
* **Pattern:** Catch at the adapter boundary, wrap with descriptive context, log with complete diagnostic metadata (island ID, player UUID, timestamp), and notify the player gracefully.

---

## 3. Logging & Diagnostics

* Use standard logger (`getLogger()` or SLF4J via `uxm-lib`).
* **Log Levels:**
  - `INFO`: Significant lifecycle events (startup, migration applied, module enabled).
  - `WARNING`: Recoverable anomalies or external plugin integration issues (e.g. soft-dependency missing, corrupted single island config fallback).
  - `SEVERE`: Critical failures requiring immediate operator attention (database offline, migration failed, chunk lock timeout).
* Always include IDs in logs (e.g. `[IslandService] Failed to load island [id=abc, owner=xyz]`).
