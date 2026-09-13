# Superpowers Skills System & Engineering Runbook

Following the organizational setup used across UXPLIMA systems, the project integrates the **Superpowers Skills Suite** (`.agents/skills/`). The assistant is strictly bound to invoke and follow these skills before any creative, planning, implementation, or debugging task.

---

## 1. The 14 Superpowers Skills & Their Roles

| Skill | Invocation Trigger | Core Procedure & Tools |
| :--- | :--- | :--- |
| **`using-superpowers`** | Start of any task / dialogue | Meta-skill enforcing that skills are checked and invoked *before* any action or response. |
| **`brainstorming`** | Before any creative / design work | Explores requirements, asks clarifying questions one at a time, evaluates 2-3 trade-offs, presents design, and saves specs to `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`. |
| **`writing-plans`** | After design approval, before coding | Decomposes designs into bite-sized, reviewable tasks with exact file targets, code snippets, and automated verification commands in `docs/superpowers/plans/YYYY-MM-DD-<topic>.md`. |
| **`executing-plans`** | During plan execution | Executes plans systematically, maintaining a living task checklist and stopping at designated human review checkpoints. |
| **`test-driven-development`** | When writing any domain logic | Strict TDD cycle: 1) Write failing test, 2) Verify failure, 3) Write minimal code to pass, 4) Verify pass, 5) Refactor. |
| **`systematic-debugging`** | When encountering any bug / failure | 4-phase root-cause analysis. Never guess, never "try random fixes". Reproduce with a minimal test first. |
| **`verification-before-completion`** | Before claiming any task is done | Rigorous proof gate: compile checks (`-Werror`), unit tests, MockBukkit tests, ArchUnit drift tests. |
| **`subagent-driven-development`** | Complex multi-faceted tasks | Dispatches focused subagents (`self` or `research`) with clear contracts, tracking progress without busy loops. |
| **`dispatching-parallel-agents`** | Concurrent research or validation | Orchestrates multiple read-only or worker agents simultaneously. |
| **`requesting-code-review`** | Before merging/committing milestone | Prepares structured review diffs, checklists, and automated test reports. |
| **`receiving-code-review`** | When human partner gives feedback | Analyzes feedback systematically, verifies implications, and applies fixes without architectural regressions. |
| **`using-git-worktrees`** | Branch isolation | Manages isolated development worktrees when tackling experimental spikes. |
| **`finishing-a-development-branch`** | Task completion | Prepares clean atomic Conventional Commits, verifies all checks pass, and updates living docs. |
| **`writing-skills`** | When automating a new procedure | Authoring new specialized runbooks in `.agents/skills/`. |

---

## 2. Directory Structure & Living Artifacts

All design specifications and execution plans produced by the Superpowers system are committed to version control:

```
docs/superpowers/
├── specs/
│   └── YYYY-MM-DD-<feature>-design.md   # Architectural designs & trade-off decisions
└── plans/
    └── YYYY-MM-DD-<feature>-plan.md     # Step-by-step implementation plans with verification
```

---

## 3. Mandatory Antigravity CLI Tool Mappings

Skills speak in generalized terms ("create a todo", "dispatch a subagent", "read a file"). In this environment, they map to:

* **Dispatching Subagents:** Use `invoke_subagent` with `TypeName: "self"` (full read/write capability) or `TypeName: "research"` (read-only exploration).
* **Task Tracking / Checklists:** Use `docs/PROJECT_BOARD.md` and `project-board.html` along with task checklists inside plan documents.
* **Verification Tools:** Use `run_command` with `./gradlew.bat check`, `./gradlew.bat test`, and `verifyNoSkippedTests`.
