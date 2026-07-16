# G0 Governed Tool-Using Agent — Detailed Implementation Plan

> **For agentic workers:** Execute task-by-task with TDD. User authorized: expand plan then implement without waiting for plan review.

**Goal:** G0 product — multi API-key profiles, guided conversation, automatic tool loop, Photo Picker images.

**Architecture:** Local authority preserved. New: `ProviderProfileRepository`, `ConversationGuide`, `AgentLoop`. ViewModel orchestrates UI + system pickers only.

**Tech Stack:** Kotlin, Compose, DataStore, Keystore, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-16-governed-general-agent-design.md`

---

## Execution status

- [x] Plan detailed
- [x] A Provider profiles (SecureSecretStore, ProviderProfileStore, templates, settings UI, migration)
- [x] B Guide (ConversationGuide + chips + createPlan gate)
- [x] C AgentLoop (sequential tool loop after plan; auto READ_ONLY; pause for approval)
- [x] D Photo picker (PickVisualMedia + image mime support)
- [x] E Tests + APK (`BUILD SUCCESSFUL`, `AgentPad-debug-g0.apk`)

---

## Epic A — Multi provider / API Key

### A1 SecureSecretStore (profile-scoped)

**Create** `security/SecureSecretStore.kt`:
- `save(profileId, secret)`, `read(profileId)`, `delete(profileId)`, `has(profileId)`
- SharedPreferences map: `secret_<profileId>` ciphertext + iv; single Keystore AES key `agentpad_secrets_v2`
- Migrate: if legacy `SecureApiKeyStore` has key and no profiles, copy to default profile id

**Test** `SecureSecretStore` logic via pure alias isolation if android-dependent skip instrumented; unit-test migration mapping pure function.

### A2 ProviderProfile model + store

**Create** `domain/ProviderProfile.kt`:
```kotlin
data class ProviderProfile(
  val id: String,
  val displayName: String,
  val templateId: String,
  val endpoint: String,
  val model: String
)
data class ProviderProfileState(
  val profiles: List<ProviderProfile>,
  val activeProfileId: String?
)
```

**Create** `provider/ProviderTemplates.kt`: deepseek, openai, custom defaults.

**Create** `data/ProviderProfileStore.kt` (DataStore JSON):
- `profilesFlow`, `activeProfile()`, `upsert`, `delete`, `setActive`
- migrate from SettingsStore provider fields + legacy key

### A3 Wire app + settings UI (minimal)

- Application exposes profileStore + secretStore
- ViewModel: load active profile for API calls; settings CRUD simplified
- Top bar shows active model name
- If no active key configured → force settings/onboarding message

---

## Epic B — ConversationGuide

**Create** `agent/ConversationGuide.kt` + tests:
- assess(goal, hasDoc, hasImage, apiReady) → Clarify | NeedAttachment | NeedImage | NeedApi | Ready
- ViewModel submitGoal checks guide before loop/plan
- UI: show guide text + chips (总结文件/选择图片/打开网页)

---

## Epic C — AgentLoop

**Create** `agent/AgentLoop.kt`:
- Input: goal, history, attachments, runMode, callbacks
- Loop max 8 steps; each step: model proposes ONE tool OR finish OR ask_user
- Reuse PlanParser style single-action JSON or full plan for first step
- Approval via existing ApprovalService; pause on missing
- Observations list fed back to next model call

**Pragmatic G0:**  
`AgentLoop` first calls existing `createPlan`; then executes actions one-by-one with observation messages; after all auto tools, if remaining need approval pause; after tool results, optional second model call "revise" only if budget remains — **minimum**: execute sanitized plan sequentially with live events (already almost there) + after completion offer continue.  

**Better G0 minimum that feels like loop:**  
After each tool success, append observation; if more actions remain and next is READ_ONLY, continue; if needs approval, pause. That's sequential plan execution with events — upgrade createPlan path rather than full ReAct if time-boxed.

**Fuller loop:** single-step planner JSON:
```json
{"type":"tool","tool":"...","arguments":{...},"title":"..."}
{"type":"clarify","message":"..."}
{"type":"finish","summary":"..."}
```

Implement fuller single-step loop when A+B done.

---

## Epic D — Photo Picker

- MainActivity: ActivityResultContracts.PickVisualMedia
- ViewModel: selectImage(uri) like document with image mime
- describeDocument allows image/*
- Guide need image
- analyze_image later optional; G0 attach only + metadata

---

## Epic E

- testDebugUnitTest
- assembleDebug
- copy APK

---

## Concrete first code commit sequence

1. ProviderTemplates + ProviderProfile + ProviderProfileStore + SecureSecretStore + tests  
2. Migrate Application/ViewModel/Settings  
3. ConversationGuide + wire submit  
4. AgentLoop single-step or enhanced sequential execute with events  
5. Photo picker  
6. Full test run  
