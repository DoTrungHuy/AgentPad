# Stage 2 Orchestrator Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move planning approval and execution orchestration out of `AgentPadViewModel` into unit-testable domain services, keep UI as a thin state projector, and add interrupted-turn re-approval execution.

**Architecture:** Extract pure-ish services under `agent/` (or `domain/runtime/`): `ApprovalService`, `ExecutionEngine`, `TurnLifecycle`. ViewModel keeps Android URI/document IO and Compose state, but delegates approve/missing/consume/execute/verify to services. No Room schema change. No UI visual redesign.

**Tech Stack:** Kotlin, coroutines, JUnit4, existing Room repository interfaces via callbacks/lambdas for audit/status.

---

## File map

| File | Responsibility |
| --- | --- |
| Create `agent/ApprovalService.kt` | Token create/validate/consume/missingApprovals |
| Create `agent/ExecutionEngine.kt` | Sanitize plan, run steps via `ToolRunner`, audit hooks, verify |
| Create `agent/TurnLifecycle.kt` | running-turn detection, leave guards |
| Create tests under `src/test/.../agent/` | Pure unit tests with fakes |
| Modify `AgentPadViewModel.kt` | Delegate to services; add re-execute interrupted path |
| Modify `AgentPadApp.kt` only if needed for INTERRUPTED CTA text (minimal) |

### Out of scope
- ZCode UI redesign
- Streaming provider
- Accessibility / Runtime tools
- Full DocumentService Android isolation (keep read in ViewModel, inject as lambda)

---

### Task 1: ApprovalService
### Task 2: ExecutionEngine + ToolRunner fake tests
### Task 3: TurnLifecycle
### Task 4: Wire ViewModel
### Task 5: INTERRUPTED re-approve & execute UX (logic + plan panel button if missing)
### Task 6: Full unit test regression via `scripts/build-android.ps1 -Test`
