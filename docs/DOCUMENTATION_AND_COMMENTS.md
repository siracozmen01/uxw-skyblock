# Documentation & Commenting Standards

## 1. Core Philosophy: "Why, Not What"

* Code must be self-documenting through expressive naming and clear structure.
* **Comments should explain the "Why" (rationale, architectural constraints, thread-safety invariants, or non-obvious domain rules), NEVER the "What" (which is obvious from the syntax).**
* Bad:
  ```java
  // Check if player is owner
  if (island.isOwner(playerId)) { ... }
  ```
* Good:
  ```java
  // Ownership transfer requires explicit dual confirmation to prevent accidental loss during leadership handoffs.
  ```

---

## 2. Javadoc Requirements

### 2.1 Public APIs, Ports, and Domain Interfaces
* Every public class, interface, record, and enum must have a concise Javadoc summary describing its purpose and contract.
* Every public method on a public interface or service must have a Javadoc describing:
  - What contract it satisfies.
  - `@param` descriptions for any parameter whose purpose is not completely self-evident from its type and name.
  - `@return` description indicating what is returned, especially under edge cases (e.g. empty `Optional`).
  - `@throws` description explaining any domain exceptions that callers must handle.

### 2.2 Package Documentation
* Every package must contain a `package-info.java` file declaring:
  - The `@NullMarked` annotation.
  - A brief Javadoc comment outlining what bounded context or technical layer the package belongs to.

---

## 3. Strict Commenting Rules & Hygiene

1. **No Dead Code:**
   - Never leave blocks of commented-out code in the repository. Git history preserves old iterations. If code is unused, delete it immediately.
2. **No Unresolved TODOs:**
   - Never commit `// TODO`, `// FIXME`, or `// HACK` markers to the main codebase. If a feature is scheduled for a future release, track it in the project issue tracker or planning markdown, not as clutter in source files.
3. **No Dangling Comments:**
   - Do not write Javadoc blocks (`/** ... */`) that are not attached to an actual type or member declaration. Section markers must use regular single-line comments (`// --- Section Name ---`).
4. **All Comments in English:**
   - Every single comment must be written in clear, professional English.
