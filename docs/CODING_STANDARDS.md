# Coding Standards & Quality Mandates

## 1. Runtime & Language Platform

* **Target Java Version:** **Java 25+** (bytecode release 25).
* **Toolchain:** Java 25 via Adoptium toolchain.
* **Server Target:** **Paper 26.2+** and **Folia**.
* **Language Features Embraced:**
  - Modern Java `record` declarations for value objects, DTOs, events, and configuration bindings.
  - Pattern matching for `switch` and `instanceof`.
  - Sealed interfaces and classes for domain hierarchies.
  - Sequenced collections (`getFirst()`, `getLast()`).
  - Standard collections factories (`List.of()`, `Set.of()`, `Map.of()`).

---

## 2. Null Safety & JSpecify Annotations

Every Java package in the project must be annotated with JSpecify `@NullMarked` in `package-info.java`.

* **Default Semantics:** All types, method parameters, return types, and fields are **strictly non-null by default**.
* **Nullable Values:** Any type that can legitimately be null must be explicitly annotated with `@Nullable` (from `org.jspecify.annotations.Nullable`).
* **NullAway Checker:** Enforced at compile-time with severity `ERROR` (`-Werror`). A single unhandled nullable dereference fails the build.
* **Collections:** Never return or accept a null collection. Use empty collections (`List.of()`, `Set.of()`, `Collections.emptyMap()`).
* **Optionals:** Use `Optional<T>` only as a method return type to indicate the absence of a value. Never use `Optional` as a method parameter, field, or inside collection generic types.

---

## 3. Static Analysis & Compilation Flags

The build process enforces zero tolerance for warnings and bad practices.

* **Compiler Flags:**
  ```kotlin
  options.release = 25
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf(
      "-Xlint:all",
      "-Xlint:-processing",
      "-Xlint:-serial",
      "-Xlint:-dangling-doc-comments",
      "-Werror",
      "-parameters"
  ))
  ```
* **Error Prone:** Runs during every build. Common bugs (misused collections, reference equality bugs, dead code, thread safety violations) are compile-time errors.
* **No Suppressions Without Explanation:** `@SuppressWarnings` is forbidden unless accompanied by a mandatory comment explaining the technical justification.

---

## 4. Code Formatting & Cleanliness (Spotless)

All code formatting is automated and validated via Spotless:

* **Java Formatter:** Palantir Java Format (version `2.93.0+`).
* **Import Order:**
  ```
  java
  javax
  org.bukkit
  io.papermc
  net.kyori
  <all-other-imports>
  ```
* **Unused Imports:** Removed automatically on build.
* **Line Endings & Whitespace:** Unix LF line endings, trailing whitespace trimmed, EOF newline enforced.

---

## 5. Object Design & Immutability

1. **Immutability by Default:**
   - All fields must be `private final` unless a specific state machine strictly requires mutability.
   - Collections exposed by entities or DTOs must be unmodifiable (e.g. `Collections.unmodifiableList(list)` or `List.copyOf(list)`).
2. **Value Objects as Records:**
   - Any data holder that does not have identity must be modeled as a Java `record`.
3. **No Public Fields:**
   - Public fields are strictly forbidden, even for constants (use accessor methods or records, except for static final enum-like constants where idiomatic).
4. **Defensive Copies:**
   - Always make defensive copies when receiving or returning mutable data structures.
