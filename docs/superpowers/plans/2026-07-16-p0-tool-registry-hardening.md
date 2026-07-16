# P0 Tool Registry & Execution Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close P0 gaps so model-visible tools, parsable tools, and executable tools are the same set; re-sanitize plans before execution; cap provider responses; and fail turns correctly when a step fails.

**Architecture:** Introduce a single `ToolRegistry` as the source of truth for tool name, risk, availability, and argument requirements. `ApprovalPolicy` and `PlanParser` consume the registry. Add `PlanSanitizer` for load/execute-time revalidation. Harden `OpenAiCompatibleClient` with response size limits. Keep ViewModel orchestration for now (Orchestrator refactor is Stage 2).

**Tech Stack:** Kotlin, JUnit4 unit tests, Android app module under `android-app/`.

---

## File map

| File | Responsibility |
| --- | --- |
| Create `android-app/app/src/main/java/com/agentpad/app/tool/ToolRegistry.kt` | Tool availability, descriptors, default catalog |
| Create `android-app/app/src/main/java/com/agentpad/app/policy/PlanSanitizer.kt` | Re-normalize risk, reject non-available tools, validate required args |
| Modify `android-app/app/src/main/java/com/agentpad/app/policy/ApprovalPolicy.kt` | Delegate risk/known tools to registry |
| Modify `android-app/app/src/main/java/com/agentpad/app/provider/PlanParser.kt` | Parse only AVAILABLE tools |
| Modify `android-app/app/src/main/java/com/agentpad/app/tool/AndroidToolExecutor.kt` | Expose available tools from registry |
| Modify `android-app/app/src/main/java/com/agentpad/app/provider/OpenAiCompatibleClient.kt` | Response size cap + cancel-friendly disconnect |
| Modify `android-app/app/src/main/java/com/agentpad/app/ui/AgentPadViewModel.kt` | Sanitize before save/execute; step failure → FAILED; VERIFY checks |
| Modify existing unit tests + add new ones | Registry consistency, sanitizer, parser planned tools, provider size |

### Available tools after change (v0.2.x)

- `inspect_task` — READ_ONLY
- `read_document_metadata` — READ_ONLY
- `read_document` — READ_ONLY
- `upload_document_for_summary` — ACTION_APPROVAL
- `open_url` — TASK_APPROVAL (requires `url`)
- `launch_app` — TASK_APPROVAL (requires `package`)
- `share_preview` — TASK_APPROVAL (requires `text`)

### Planned (not parsable / not executable)

- `write_document`, `delete_document`, `send_text`, `capture_screen`, `accessibility_input`, `install_package`, `run_command`

### Forbidden (never parsable)

- `payment`, `read_password`, `read_otp`, `bypass_lock_screen`, `silent_install`

---

### Task 1: ToolRegistry + consistency tests

**Files:**
- Create: `android-app/app/src/main/java/com/agentpad/app/tool/ToolRegistry.kt`
- Create: `android-app/app/src/test/java/com/agentpad/app/tool/ToolRegistryTest.kt`
- Modify: `android-app/app/src/main/java/com/agentpad/app/policy/ApprovalPolicy.kt`
- Modify: `android-app/app/src/test/java/com/agentpad/app/policy/ApprovalPolicyTest.kt` (keep behavior)

- [ ] **Step 1: Write failing registry tests**
- [ ] **Step 2: Implement ToolRegistry**
- [ ] **Step 3: Wire ApprovalPolicy to registry**
- [ ] **Step 4: Run unit tests**

### Task 2: PlanParser only accepts AVAILABLE tools

**Files:**
- Modify: `PlanParser.kt`, `PlanParserTest.kt`

- [ ] **Step 1: Add tests rejecting planned tools**
- [ ] **Step 2: Parser uses `registry.availableTools()` / `isPlannable`**
- [ ] **Step 3: Run PlanParserTest**

### Task 3: PlanSanitizer

**Files:**
- Create: `PlanSanitizer.kt` + `PlanSanitizerTest.kt`
- Modify: `AgentPadViewModel.kt` (savePlan path + execute path)

- [ ] **Step 1: Failing sanitizer tests**
- [ ] **Step 2: Implement sanitizer**
- [ ] **Step 3: Call on createPlan result and before execute**
- [ ] **Step 4: Run tests**

### Task 4: Provider response size limit

**Files:**
- Modify: `OpenAiCompatibleClient.kt`
- Create: `OpenAiCompatibleClientTest.kt` (pure validation helpers if network hard to unit-test; test endpoint + size reader via package-visible helper)

- [ ] **Step 1: Extract/limit body read; test cap**
- [ ] **Step 2: Implement**
- [ ] **Step 3: Run tests**

### Task 5: Execution failure + VERIFY semantics

**Files:**
- Modify: `AgentPadViewModel.kt`
- Optionally light unit extraction if needed

- [ ] **Step 1: Ensure failed tool result fails the turn (already mostly true)**
- [ ] **Step 2: VERIFY checks all steps succeeded and no empty final result when summary tool used**
- [ ] **Step 3: Run unit + compile**

### Task 6: Wire executor availableTools + full regression

- [ ] **Step 1: AndroidToolExecutor.availableTools from registry**
- [ ] **Step 2: `./gradlew testDebugUnitTest`**
- [ ] **Step 3: Update docs if tool lists changed**

---

## Out of scope (Stage 2+)

- Full Orchestrator / ViewModel split
- Accessibility / Runtime tools
- Streaming
- Multi-document
