# AgentPad 审核报告与改进计划书

- 日期：2026-07-16
- 审核范围：`android-app` 主应用（v0.2.1-alpha.1 / 分支 `v0.2.1-workspace`）
- 对照文档：`README.md`、`docs/ARCHITECTURE.md`、`docs/SECURITY.md`、`docs/ROADMAP.md`
- 审核方法：源码审阅 + 架构/安全模型对照 + 测试与 CI 覆盖分析
- 目标：识别当前 Agent 在安全、能力、可靠性、可维护性上的问题，并给出可执行的分阶段改进计划

---

## 1. 总体结论

AgentPad 的**安全立意正确且部分实现扎实**：

- 本地权威审批、风险只能升级不能降级
- 计划严格解析与未知工具拒绝
- API Key Keystore 加密、备份关闭、诊断脱敏
- 线程/回合/中断恢复/审批令牌不落盘等设计与文档基本一致

但作为“可执行 Agent 工作台”，当前版本仍更接近：

> **受控的“计划生成 + 有限 Intent 执行”原型**

而不是完整的 Agent 运行时。核心问题可归纳为五类：

| 类别 | 严重度 | 一句话 |
| --- | --- | --- |
| 工具白名单与执行器脱节 | **P0 安全/正确性** | 策略层允许计划尚未实现甚至高危的工具名 |
| 缺少真正的 Agent 闭环 | **P0 产品能力** | 单次规划后串行执行，无观察→重规划→验证 |
| 编排逻辑集中在 ViewModel | **P1 架构** | 审批/执行/网络/文件全塞进 UI 层，难测难扩 |
| 运行时护栏未落地 | **P1 安全/稳定** | 输出上限、取消、重试、限流、步骤超时多为文档承诺 |
| 测试与真机门禁不足 | **P1 质量** | 关键执行路径几乎无自动化覆盖 |

**不建议在未完成 P0 加固前推进 Accessibility / Runtime 扩展。**

---

## 2. 架构现状（简图）

```text
UI (AgentPadApp / ViewModel)
  ├─ Provider (OpenAiCompatibleClient + PlanParser)
  ├─ Policy  (ApprovalPolicy + ApprovalTokenPolicy)
  ├─ Tool    (AndroidToolExecutor)  // 仅 3 个 Intent 真正执行
  ├─ Data    (Repository + Room v2)
  └─ Security/Diagnostics
```

文档中的流水线：

```text
目标 → 计划 → 校验 → 风险升级 → 审批 → 令牌 → 执行 → 验证 → 审计
```

代码实际状态：

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| 计划生成 | 已实现 | 非流式、可取消协程但 HTTP 未必中断 |
| PlanParser 校验 | 已实现且较好 | 未知/禁止/超步数拒绝 |
| 风险升级 | 已实现 | `normalize` 只升不降 |
| 审批令牌 | 已实现 | 内存态、TTL、单次使用、参数摘要绑定 |
| 工具执行 | **部分** | 仅 `open_url` / `launch_app` / `share_preview` + 文件元数据/上传总结 |
| 结果验证 | **几乎空壳** | `VERIFYING` 只是状态翻转 |
| 审计 | 部分 | 有事件摘要，缺逐步证据结构化与导出策略 |

---

## 3. 问题清单（按优先级）

### P0 — 必须先修（安全边界 / 正确性）

#### P0-1 策略白名单与可执行工具集合不一致

**现象**

- `ApprovalPolicy.knownTools()` 含：
  - 已实现（或部分实现）：`inspect_task`、`read_document*`、`open_url`、`launch_app`、`share_preview`、`upload_document_for_summary`
  - **未实现但仍可进入计划**：`write_document`、`delete_document`、`send_text`、`capture_screen`、`accessibility_input`、`install_package`、`run_command`
- `AndroidToolExecutor.availableTools` 只有 7 项；真正 Intent 执行 3 项
- `PlanParser` 以 `knownTools()` 为准，因此模型可合法生成“计划通过、执行必败”的步骤

**风险**

1. 用户审批后执行失败 → 信任与可用性受损  
2. 高危工具名（`install_package`、`run_command`、`accessibility_input`）已在策略中以 `ACTION_APPROVAL` 出现，未来一旦有人“顺手实现”而缺参数护栏，会直接扩大攻击面  
3. 文档承诺“未知工具拒绝”，但产品层“未实现工具”被伪装成已知工具

**期望**

- 单一工具注册表：`schema + risk + availability + executor + argument validator`
- 未实现工具不得出现在模型可见 schema，也不得通过解析

---

#### P0-2 计划解码与执行时未强制重做本地策略归一

**现象**

- `PlanCodec.decode` 直接信任库中的 `risk` 字符串
- 执行循环虽再次 `normalize`，但对“工具是否仍可用 / 参数是否合法”没有统一校验器

**风险**

本地 DB 被调试/备份篡改、迁移错误、或未来导出导入时，可能加载出与当前策略不一致的计划。

**期望**

执行前 `PlanSanitizer`：

1. 重算 risk  
2. 校验 tool ∈ available  
3. 校验 arguments schema  
4. 重新生成 argumentDigest 供审批比对  

---

#### P0-3 网络与输出护栏不足

**现象**

- `HttpURLConnection` 全量读入内存，无响应体上限
- 文档写“限制步骤、请求次数、时长和输出大小”，代码仅有：
  - 步骤上限 8（解析层）
  - connect/read timeout
  - 文档读取 1MB / 发给模型 120k 字符
- 取消回合不保证中断底层 HTTP
- 无重试分类、无限流/429 友好提示

**风险**

大响应 OOM、挂起占用、错误体验差、密钥相关错误信息虽有脱敏但仍可能过宽。

---

#### P0-4 `VERIFYING` 无真实验证语义

**现象**

执行完最后一步后直接 `updateStatus(VERIFYING)` 再 `COMPLETED`，没有：

- 对工具结果的成功条件检查
- 对“是否达成目标”的本地规则/二次模型校验
- 失败可恢复分支

**风险**

产品把“跑完步骤”等同于“任务完成”，与 Agent 工作台定位不符，也掩盖了部分失败。

---

#### P0-5 工具能力名存实亡，容易误导用户与模型

| 工具 | 现状问题 |
| --- | --- |
| `read_document` | 只读入并返回字符数，内容不进入后续推理 |
| `read_document_metadata` | 可用，但与附件元数据重复 |
| `inspect_task` | 空操作成功 |
| `upload_document_for_summary` | 会上传原文（需 ACTION 审批），但是单次、无分块、无红线字段检测 |
| `open_url` | 仅校验 `https` + host 非空，无额外主机确认 UI |
| `launch_app` | 任意包名，依赖计划文本展示 |
| 未实现工具 | 仍可被规划 |

---

### P1 — 重要（架构、可靠性、产品完整度）

#### P1-1 ViewModel 上帝对象

`AgentPadViewModel` ~760 行，承担：

- 线程导航与删除
- 提供商配置与 Key 保存
- 上下文压缩
- 计划创建
- 审批令牌生命周期
- 工具执行与文件 IO
- 诊断上下文

**后果**：单测困难、并发状态难推理、后续 Accessibility/Runtime 无法干净接入。

**方向**：拆为

```text
ThreadController
PlanningService
ApprovalService
ExecutionEngine
DocumentAccessService
ProviderConfigService
```

UI ViewModel 只做状态投影与用户意图转发。

---

#### P1-2 没有 Agent 闭环（ReAct / plan-execute-observe）

当前是：

```text
一次 createPlan → 用户审批 → for each action execute → done
```

缺失：

- 工具结果回写给模型
- 失败后局部重规划
- 用户追问与运行中状态机的严格互斥之外的“可恢复继续”
- stopCondition 的可执行解释

这是“聊天式规划器”与“Agent”的本质差距。

---

#### P1-3 审批与执行的时序/体验问题

- 令牌在 `executePlan` 开头整体 `consume`，中途失败不能对剩余步骤保留授权（安全偏严，体验偏硬）
- 无逐步执行进度事件（UI 只能靠 status/busy）
- `TASK_APPROVAL` 与 `ACTION_APPROVAL` 混合计划的展示逻辑可用，但缺“为什么需要批”的结构化理由

---

#### P1-4 附件与 URI 权限脆弱

- 仅支持单个 `selectedDocument`
- `takePersistableUriPermission` 失败被吞掉
- 删除线程时才尝试 release
- 无附件列表管理、过期权限检测、重新授权流程

---

#### P1-5 线程状态语义偏粗

- 一回合 `COMPLETED` 就把 thread 标完成；追问会再拉回 `ACTIVE`，但侧栏状态可能误导
- `INTERRUPTED` 后没有“基于原计划重新审批执行”的一等公民流程（只能新追问或手动再走）

---

#### P1-6 Prompt / 上下文注入防护仍偏“约定”

已有：

- 系统提示声明历史不可信
- 附件只传元数据
- 压缩需用户确认

仍缺：

- 历史消息角色隔离（例如统一把历史包在 `untrusted` 围栏中，而不是直接复用 `system` role）
- 对 `CONTEXT_SUMMARY` 的二次安全过滤
- 工具参数中的指令型文本清洗策略

---

#### P1-7 UI 单体与可访问性

- `AgentPadApp.kt` >1000 行
- 能力页静态，配置成功后仍显示“需要配置”
- 缺字体缩放/减少动画等路线图项的系统性验收
- 无障碍后续功能未到，但当前 UI 也缺基础 contentDescription 完整性审计

---

#### P1-8 测试与门禁缺口

已有较好单测：

- PlanParser / ApprovalPolicy / ApprovalToken / ContextPolicy / PlanCodec / 部分 redaction
- Instrumentation：迁移、repository supersede/interrupt、极简 UI

明显缺失：

- `OpenAiCompatibleClient`（endpoint 校验、错误脱敏、超时）
- `AndroidToolExecutor`（URL/包名/分享失败路径）
- **执行引擎集成测试**（审批缺失拒绝、令牌过期、中途失败、FORBIDDEN）
- ViewModel 或更好：domain service 的协程测试
- 工具注册表一致性测试（P0 配套）
- 模拟器测试已挪到手工 diagnostics，CI 不保证运行时行为

---

### P2 — 改进项（体验、可观测、后续扩展准备）

1. 流式规划输出与可中断生成  
2. 结构化审计导出（按线程、按回合）  
3. 速率限制与费用/token 粗估提示  
4. 计划 diff：追问后旧计划 SUPERSEDED 可视化对比  
5. 深色主题对比度与截图默认策略的用户教育  
6. 网络层替换为 OkHttp + 拦截器（日志脱敏、size limit、cancel）  
7. 证书固定（可选，需评估中国大陆服务商证书轮换）  
8. 为 v0.3 Accessibility 预留 `CapabilityGate`，但默认编译关闭  

---

## 4. 已做得好的部分（避免误改）

改进时**不要破坏**这些正确约束：

1. 模型输出不是权限来源；本地 `normalize` 只升不降  
2. 审批令牌不写 Room；重启/新回合失效  
3. 禁止支付/密码/OTP/锁屏/静默安装（策略层已标 FORBIDDEN）  
4. API Key 不进 Room/日志/诊断消息正文  
5. 备份默认关闭；诊断主动导出  
6. targetSdk 36，不靠降 SDK 换能力  
7. Room v2 线程模型与 migration 方向正确  

后续任何 Runtime/无障碍能力都必须**叠加**在上述边界之上，而不是绕过。

---

## 5. 改进策略选择

### 方案 A — 最小加固（1–2 周）

只修 P0：工具注册表统一、未实现工具下线、执行前再校验、网络响应上限、VERIFY 最小实现。  
**优点**：风险下降快；**缺点**：Agent 能力仍弱。

### 方案 B — 加固 + 编排重构（推荐，3–5 周）

在 A 基础上抽出 `ExecutionEngine` / `ToolRegistry`，补齐测试门禁，完成可观察的逐步执行与中断恢复 UX。  
**优点**：为 v0.2.x hardening 与 v0.3 打底；**缺点**：需要克制范围，避免顺手做无障碍。

### 方案 C — 直接上 Agent 闭环 + 多工具（6–10 周）

B + observe/replan 循环 + 文档工具实质化 + 流式。  
**优点**：产品形态接近真正 Agent；**缺点**：若 P0 未先做完，复杂度会放大缺陷。

**推荐：方案 B，并明确冻结 v0.3 能力入口。**

---

## 6. 目标架构（改进后）

```text
                    ┌──────────────────────────┐
                    │      UI / ViewModel      │
                    │  仅状态投影与用户意图    │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │     AgentOrchestrator    │
                    │ plan / approve / run /   │
                    │ cancel / recover         │
                    └──────┬─────────┬─────────┘
           ┌───────────────┘         └───────────────┐
           ▼                                         ▼
 ┌──────────────────┐                     ┌────────────────────┐
 │  PlanningService │                     │  ExecutionEngine   │
 │  prompt+parse    │                     │  step loop+verify  │
 └────────┬─────────┘                     └─────────┬──────────┘
          │                                           │
          ▼                                           ▼
 ┌──────────────────┐                     ┌────────────────────┐
 │ ProviderGateway  │                     │   ToolRegistry     │
 │ timeout/limit/   │                     │ descriptor+validator│
 │ cancel/redact    │                     │ + executor         │
 └──────────────────┘                     └─────────┬──────────┘
                                                    │
                                          ┌─────────▼──────────┐
                                          │ ApprovalService    │
                                          │ token+policy       │
                                          └────────────────────┘
```

### 6.1 单一工具注册表示例字段

```kotlin
data class ToolDescriptor(
    val name: String,
    val risk: RiskLevel,
    val availability: ToolAvailability, // AVAILABLE | PLANNED | FORBIDDEN
    val argumentSchema: ArgumentSchema,
    val summary: String,
    val executor: ToolExecutor? // null if not AVAILABLE
)
```

规则：

- `PlanParser` / 模型 system prompt **只暴露 AVAILABLE**
- `FORBIDDEN` 永不进入 prompt
- `PLANNED` 仅出现在能力页，不进入计划 schema

---

## 7. 分阶段实施计划

### 阶段 0：基线冻结（0.5 天）

**交付**

- [ ] 从本审核文档建立 issue 列表（P0/P1）
- [ ] 冻结：不合并 Accessibility / Runtime / 新高危工具
- [ ] 记录当前测试命令与通过标准

**验收**

- CI 现有 `testDebugUnitTest lintDebug assembleDebug` 仍绿

---

### 阶段 1：P0 安全与正确性加固（约 1 周）

#### 1.1 统一 ToolRegistry

**任务**

1. 新建 `tool/ToolRegistry.kt`（或 `policy/ToolCatalog`）
2. 迁移 `ApprovalPolicy.riskByTool` 与 `AndroidToolExecutor.availableTools` 到同一来源
3. 将未实现工具标为 `PLANNED` 或直接移除出 known schema
4. 将 `install_package` / `run_command` / `accessibility_input` 在 v0.2.x **默认不可计划**（建议风险记为 FORBIDDEN 或 PLANNED 且 parser 拒绝）

**测试**

- `ToolRegistryConsistencyTest`：  
  `available ⊆ known`  
  `parserAccepts == available`  
  `forbidden ∩ available == ∅`

#### 1.2 执行前 PlanSanitizer

**任务**

1. 对 decode 后的 plan 重算 risk / 校验 tool / 参数
2. `executePlan` 与 `savePlan` 都走 sanitizer
3. 参数 schema：`open_url.url`、`launch_app.package`、`share_preview.text` 必填与格式

**测试**

- 篡改 risk 降级 → 执行时仍升回  
- 未知/计划外工具 → 拒绝  
- 缺参 → 拒绝

#### 1.3 Provider 护栏

**任务**

1. 响应体最大字节数（建议默认 2–4MB，可配置上限）
2. 取消时 `disconnect()`  
3. 统一错误分类：`AUTH` / `RATE_LIMIT` / `TIMEOUT` / `INVALID_PLAN` / `NETWORK`
4. 错误展示与日志统一脱敏

**测试**

- 超大 body 截断/失败  
- endpoint 非 https 非 loopback 失败  
- api key 不出现在 exception message

#### 1.4 最小 VERIFY 语义

**任务**

1. 每步结果写入结构化 `StepResult`（内存 + 审计）
2. 任一步 `success=false` → 回合 `FAILED`，不进 COMPLETED
3. 全部成功 → `VERIFYING` 检查：步骤数、禁止工具未执行、结果非空策略
4. 可选：本地规则验证（例如 `open_url` 必须有 evidence host）

**验收标准**

- 不再出现“执行器返回失败仍可能被上层含糊完成”的路径
- 未实现工具无法进入可审批计划

---

### 阶段 2：编排层重构（约 1.5–2 周）

#### 2.1 抽出领域服务

| 新组件 | 职责 | 从何处搬出 |
| --- | --- | --- |
| `AgentOrchestrator` | 回合状态机 | ViewModel |
| `PlanningService` | 压缩判断 + createPlan | ViewModel + Client |
| `ApprovalService` | token 创建/校验/消费 | ViewModel + TokenPolicy |
| `ExecutionEngine` | 逐步执行、审计、校验 | ViewModel.executeActions |
| `DocumentService` | URI 权限、读写、类型限制 | ViewModel |

**约束**

- 不改用户可见主流程文案大爆炸
- Room schema 尽量不变；若变，给 v2→v3 migration
- UI 仍 Compose 三栏布局

#### 2.2 回合状态机显式化

```text
DRAFT
  → PLANNING
  → AWAITING_APPROVAL
  → RUNNING
  → VERIFYING
  → COMPLETED | FAILED | CANCELLED
中断：PLANNING|RUNNING|VERIFYING → INTERRUPTED
追问：pending → SUPERSEDED
```

**补充**

- `INTERRUPTED + plan 仍在` → UI 提供“重新审批并执行”而非只能新建回合
- 运行中禁止追问（已有）保持

#### 2.3 逐步事件流

- `ExecutionEvent.StepStarted/Finished/Failed`
- UI 订阅显示进度
- 审计写入 `TOOL_STARTED` / `TOOL_SUCCEEDED` / `TOOL_FAILED`

**验收**

- ViewModel < ~300 行（指示性目标）
- ExecutionEngine 单测覆盖主路径
- 手工：计划→审批→执行→失败中断→重启恢复

---

### 阶段 3：Agent 能力补强（v0.2.x hardening，约 1.5–2 周）

按路线图，但**只做已实现工具的实质化**，不引入跨应用操控。

#### 3.1 文档工具做实

1. `read_document`：本地抽取预览（前 N 字）进入**回合工作记忆**（不默认进长期消息原文）
2. `upload_document_for_summary`：分块摘要、显示将上传字节数、二次确认文案
3. 附件多文件（可先上限 3）与权限失效重选

#### 3.2 观察回写（轻量闭环，不做无限 ReAct）

推荐 **单次执行后可选“根据结果继续规划”**：

```text
execute all steps → show results → user taps “基于结果继续” → new turn with structured observations
```

避免 v0.2.x 直接上全自动多轮 tool loop（成本与注入面更大）。

#### 3.3 运行时限制落地

| 限制 | 建议默认 |
| --- | --- |
| max steps / turn | 8 |
| max provider calls / turn | 3（plan + optional compress + optional summary） |
| max wall time / turn | 3–5 min |
| max response bytes | 2–4 MB |
| max share text | 10k（已有） |
| max document | 1 MB（已有，可保留） |

#### 3.4 UX hardening

- 能力页反映真实配置状态
- 中断恢复横幅
- 提供商错误可读性（401/429/超时）
- 字体缩放与横屏抽测清单

---

### 阶段 4：质量门禁与发布纪律（并行，约 1 周累计）

#### 4.1 自动化测试金字塔

| 层级 | 新增重点 |
| --- | --- |
| Unit | Registry、Sanitizer、ExecutionEngine、ProviderGateway |
| Instrumentation | 审批拒绝执行、删除线程 URI、迁移 |
| Manual device matrix | 小米平板 / 联想小新 / ARM64 手机：安装、重启、升级、中断恢复 |

#### 4.2 CI

- 保持 unit + lint 必过
- 增加“工具注册表一致性”为阻断项
- instrumentation 可继续 manual/scheduled，但 P0 相关必须 unit 可测

#### 4.3 发布

- 版本建议：`0.2.2-alpha` = 阶段1+2 完成；`0.2.3-alpha` = 阶段3
- 不在加固完成前宣称“完整 Agent”

---

### 阶段 5：明确不做 / 延后（防范围失控）

在阶段 1–3 完成前，冻结：

- AccessibilityService 自动操作  
- Shizuku / root  
- Shell / Python / Git Runtime  
- 支付、密码、验证码相关任何“智能填写”  
- 静默安装  
- 云端同步作为权限源  

v0.3 启动条件（建议写进 ROADMAP）：

1. ToolRegistry 单一真相源  
2. ExecutionEngine 可单测  
3. 中断恢复 UX 完成  
4. P0 测试门禁全绿  
5. 至少 2 类真机通过 hardening 清单  

---

## 8. 详细任务分解（可直接建 issue）

### Epic A — ToolRegistry & Policy（P0）

1. A1 设计 `ToolDescriptor` 与 ArgumentSchema  
2. A2 迁移现有工具定义  
3. A3 下线/标记未实现工具  
4. A4 PlanParser 改为只接受 AVAILABLE  
5. A5 系统提示动态生成工具列表  
6. A6 一致性单测  

### Epic B — Execution safety（P0）

1. B1 PlanSanitizer  
2. B2 执行前审批再确认（防 TOCTOU：校验 token 与当前 plan digest）  
3. B3 逐步审计事件  
4. B4 VERIFY 最小规则  
5. B5 失败路径与 CANCEL 路径测试  

### Epic C — Provider hardening（P0/P1）

1. C1 响应大小限制  
2. C2 取消与超时  
3. C3 错误分类与脱敏  
4. C4（可选）OkHttp 迁移  

### Epic D — Orchestrator refactor（P1）

1. D1 接口草图与依赖方向（domain 不依赖 Compose）  
2. D2 搬迁 createPlan/executePlan  
3. D3 ViewModel 瘦身  
4. D4 INTERRUPTED 恢复流程  

### Epic E — Product hardening（P1）

1. E1 文档工具工作记忆  
2. E2 附件多选/重授权  
3. E3 能力页真实状态  
4. E4 限制配置与 UI 提示  
5. E5 “基于结果继续”轻量闭环  

### Epic F — QA gate（P1）

1. F1 单测补齐  
2. F2 真机清单  
3. F3 发布说明模板更新（已知限制写清楚）  

---

## 9. 建议排期（一人全职约当）

| 周 | 内容 | 可发布增量 |
| --- | --- | --- |
| W1 | Epic A + B 主体 + C1/C2 | 内部 debug：不可规划未实现工具 |
| W2 | Epic B 收尾 + D1/D2 + 测试门禁 | `0.2.2-alpha` 候选 |
| W3 | D3/D4 + E 部分 + F | 恢复 UX 可用 |
| W4 | E 收尾 + 真机验收 + 文档 | `0.2.2/0.2.3` 预发布 |

若仅能投入兼职：先做 **W1 的 P0**，否则不要扩功能。

---

## 10. 验收标准（Definition of Done）

### 安全

- [ ] 模型可见工具 = 可执行工具 = 可解析工具  
- [ ] 任意 FORBIDDEN / PLANNED 工具无法进入 `AWAITING_APPROVAL`  
- [ ] 执行前强制本地 risk 重算与参数 schema 校验  
- [ ] 审批令牌绑定 planId + actionId + argumentDigest + TTL + 单次  
- [ ] 诊断/错误路径无 API Key、无文件原文、无完整模型输出  

### Agent 正确性

- [ ] 任一步失败 → 回合 FAILED，且 UI/审计一致  
- [ ] VERIFY 不再是空状态  
- [ ] 取消能停止编排；网络请求尽力中断  
- [ ] 进程被杀后活跃回合变为 INTERRUPTED，且可重新审批执行  

### 工程

- [ ] 新增核心逻辑具备单元测试  
- [ ] CI 阻断注册表不一致  
- [ ] ViewModel 不再直接实现工具细节  

### 产品诚实性

- [ ] README/能力页清楚区分“已可用 / 计划中”  
- [ ] 不把 Intent 启动成功率宣传为通用手机操控 Agent  

---

## 11. 风险与依赖

| 风险 | 缓解 |
| --- | --- |
| 重构时回归审批漏洞 | 先加一致性/令牌测试再搬代码 |
| 下线未实现工具导致“模型更常失败” | 同步收紧 prompt 与 few-shot/示例（若有） |
| 一人维护 UI 大文件 | 阶段2 强制拆分，PR 小步 |
| 过早做 v0.3 | 用阶段5 门禁卡死 |
| 中国大陆网络不稳定 | 错误分类 + 超时提示 + 重试策略，不靠放宽 HTTPS |

---

## 12. 立即行动建议（本周可做）

1. **立刻**：把 `install_package`、`run_command`、`accessibility_input`、`send_text`、`capture_screen`、`write_document`、`delete_document` 从可解析工具集移除或标 PLANNED 并拒绝解析。  
2. **立刻**：加 `ToolRegistryConsistencyTest`。  
3. **本周**：`PlanSanitizer` + 响应体上限 + 逐步失败即 FAILED。  
4. **下周**：抽 `ExecutionEngine`，补集成单测。  
5. **再往后**：文档工具做实与中断恢复 UX。  

---

## 13. 附录：关键代码锚点

| 主题 | 位置 |
| --- | --- |
| 风险表/白名单 | `policy/ApprovalPolicy.kt` |
| 令牌 | `policy/ApprovalTokenPolicy.kt` |
| 计划解析 | `provider/PlanParser.kt` |
| 模型调用 | `provider/OpenAiCompatibleClient.kt` |
| 执行与审批编排 | `ui/AgentPadViewModel.kt` |
| Intent 工具 | `tool/AndroidToolExecutor.kt` |
| 持久化 | `data/AgentPadRepository.kt`, `data/local/AppDatabase.kt` |
| 计划编解码 | `data/PlanCodec.kt` |
| 密钥 | `security/SecureApiKeyStore.kt` |
| 诊断 | `diagnostics/CrashReporter.kt` |
| UI | `ui/AgentPadApp.kt` |

---

## 14. 结论

AgentPad 已经具备**正确的安全哲学和可用的线程工作区骨架**，但当前 Agent 的主要问题不是“缺一个聊天功能”，而是：

1. **权限边界的实现表面比真实可执行面更大**（P0）  
2. **编排与验证尚未产品化**（P0/P1）  
3. **架构把 Agent 内核放在 ViewModel，阻碍安全进化**（P1）  
4. **测试门禁尚未锁住安全不变量**（P1）  

按本计划完成阶段 1–2 后，项目才适合继续宣称并扩展为更强的 Device Agent；在此之前，应以 **Native Core hardening** 为唯一主线。
