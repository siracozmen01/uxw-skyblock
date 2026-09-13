# Project Governance & Development Workflow Rules

This document establishes the binding workflow, governance, and quality policies for collaborating on the UXPLIMA Skyblock project.

---

## 1. Developer-AI Collaboration & Explicit Approval Protocol

* **Explicit Approval Required:** The AI assistant must **never** create, modify, or delete files, execute destructive commands, or alter architectural directions without explicit user confirmation (e.g. "onayladım", "yap", "başla").
* **No Unverified Assumptions:** When faced with ambiguity, trade-offs, or multiple technical avenues, the assistant must outline the alternatives with rationale and wait for the user's decision.
* **Radical Transparency:** Every proposed action, file edit, and command must be stated clearly with explicit context. No hidden changes.

---

## 2. Definition of Done (DoD)

A feature, task, or bug fix is strictly considered **DONE** only when all of the following criteria are satisfied:
1. **Design Conformance:** Code strictly conforms to Hexagonal Architecture, DDD bounded contexts, and `uxm-lib` module mapping.
2. **Automated Test Coverage:**
   - Pure domain logic is guarded by JUnit 5 and jqwik property-based tests.
   - Adapters and event listeners are verified by MockBukkit v26.2.
   - No tests are skipped (`verifyNoSkippedTests` passes).
3. **Static Analysis & Null Safety:**
   - Palantir Java Format enforced by Spotless.
   - Zero compiler warnings (`-Werror`).
   - JSpecify `@NullMarked` on every package; NullAway & Error Prone pass with zero warnings.
4. **Architectural Guard Validation:**
   - ArchUnit tests pass with zero drift (no `BukkitScheduler`, no legacy chat colors, no raw SQL concatenation, layer boundaries preserved).
5. **Living Documentation & Board Synchronized:**
   - All relevant documentation in `docs/` is updated in the same session.
   - The **Project Tracking Board (`project-board.html` and `docs/PROJECT_BOARD.md`)** is updated with the latest progress.

---

## 3. Living Documentation Policy

* **Zero Documentation Drift:** Documentation must never become obsolete or detached from reality.
* Any modification to configurations, domain models, permissions, or architectural layers mandates an immediate update to the corresponding markdown files in `docs/` within the same commit/turn.
* Documentation is the single source of truth (SSOT).

---

## 4. Git & Version Control Standards

* **Conventional Commits:** All commit messages must follow the Conventional Commits specification in English:
  - `feat: <description>` (new feature)
  - `fix: <description>` (bug fix)
  - `refactor: <description>` (code restructuring without behavioral change)
  - `docs: <description>` (documentation updates)
  - `test: <description>` (adding or correcting tests)
  - `chore: <description>` (build tasks, dependency bumps)
* **Atomic Commits:** Each commit must encapsulate a single logical unit of work. Do not bundle unrelated changes into monolithic commits.
* **Repository Cleanliness:** `.gitignore` is strictly enforced. Build directories, IDE files, OS artifacts, and local `references/` are never staged or committed.

---

## 5. Gradle & Build Infrastructure Standards

* **Build System:** Gradle with Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`).
* **Version Catalog:** All library versions and plugin coordinates are managed exclusively via `gradle/libs.versions.toml`.
* **Convention Plugins:** Build configurations are standardized in `buildSrc/src/main/kotlin/`.
* **Composite Builds:** Development seamlessly references local `references/uxm-lib` when present, falling back to published Maven/JitPack artifacts.

---

## 6. Mandatory Project Tracking Board Updates

* The project includes a visual Trello-style **Project Board (`project-board.html`)** and a version-controlled **`docs/PROJECT_BOARD.md`**.
* **Mandatory Update Rule:** At every phase, milestone, or task state transition (Backlog -> To Do -> In Progress -> In Review -> Done), the assistant is strictly mandated to update the board status, metrics, and completion timestamps.

---

## 7. Mandatory Superpowers Skills Protocol

* The assistant must strictly follow the **Superpowers Skills System** (`.agents/skills/`) as detailed in `docs/SUPERPOWERS_WORKFLOW.md`.
* **Pre-Action Invocation:** Before any creative design, planning, coding, or debugging task, the assistant MUST check and invoke the corresponding skill (`brainstorming`, `writing-plans`, `executing-plans`, `test-driven-development`, `systematic-debugging`, `verification-before-completion`).
* All designs must be documented in `docs/superpowers/specs/` and all plans in `docs/superpowers/plans/`.
* No code may be written without completing the `brainstorming` design gate and human partner approval.
