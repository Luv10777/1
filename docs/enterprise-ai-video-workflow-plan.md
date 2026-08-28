# 梧曜 AI 企业级商用视频工作流改造方案

> 文档版本：v1.0
>
> 编写日期：2026-08-26
>
> 适用对象：Claude Code、后端、前端、基础设施和测试协作者
>
> 目标：将当前“页面原型 + 领域契约 + Mock/半成品后端”改造为可以被真实商家使用、可审计、可恢复、可计费、可扩展的 AI 视频生产系统。

## 1. 给 Claude Code 的执行指令

请把本文档视为本项目的视频工作流实施合同。开始编码前，先完成仓库现状审计和数据库/API 契约统一；不要通过继续增加 Mock、硬编码状态或示例 URL 来掩盖未实现能力。

每个阶段必须同时交付：

- 可编译、可启动的代码；
- 数据库迁移或实体变更；
- 单元测试、集成测试和失败路径测试；
- API 契约示例和运行说明；
- 日志、指标、错误码和恢复策略；
- 更新本文件中的完成状态。

以下规则不可违反：

- 真实 Provider、对象存储、队列和数据库之间必须有可追踪的任务 ID 关联。
- `COMPLETED` 只能代表真实结果已落库并可访问，不能代表“请求已提交”或“返回了 Mock”。
- 所有模型调用必须经过服务端 AI Gateway，前端不得接触 API Key。
- 所有生成结果必须进入 Asset/Work 体系，不能只保存供应商临时 URL。
- 任何自动发布、扣费或高风险内容通过都必须有明确的授权和审计记录。
- 不得把供应商名称、模型名称、密钥或 URL 散落在业务工作流代码中。

## 2. 当前基线与事实

当前分支已经有 Vue 3 前端、Spring Boot 后端目录、Python Worker、PostgreSQL/Redis/RabbitMQ/MinIO 配置，以及一套浏览器端领域 Mock。前端 `npm run build` 和 38 个 Node 测试通过，但这只能证明前端原型和浏览器契约可构建，不能证明真实视频生成可用。

现状审计结论如下：

| 区域 | 当前事实 | 影响 |
|---|---|---|
| 前端视频页 | 只能提交项目名称和文字描述 | 没有参考图片/视频输入 |
| 前端详情页 | `loadProject()` 使用硬编码测试项目 | 刷新后无法恢复真实项目 |
| 素材上传 | 后端有预签名 URL 雏形，视频页未接入 | 素材无法进入工作流 |
| 工作流执行器 | 各步骤主要是返回下一步名称，多个 TODO | 没有真实模型调用 |
| WorkflowTaskService | 可创建任务的代码存在，但未被步骤执行器使用 | 任务表不会形成完整链路 |
| AI Gateway | 有文本、图片、视频端点和适配器雏形 | 与工作流未连接，协议仍需核对 |
| Python Worker | 消费者存在，创意/图片/视频/合成都返回 Mock | 无法生成真实成片 |
| 人工审核 | 恢复后仍停留在当前审核步骤 | 存在审核死循环风险 |
| 数据库 | SQL、JPA 实体和运行库字段不一致 | 后端启动和运行不稳定 |
| Java 构建 | `mvnw test -DskipTests` 当前失败 | 新后端不能从源码交付 |
| 运行服务 | 8080 当前进程返回新工作流路由的静态资源错误 | 运行的是旧编译产物 |
| Provider 凭证 | API Key 曾被直接提交到 Java 和 SQL | 必须立即撤销、轮换并清理历史 |

关键代码位置：

- 前端视频项目：[src/views/VideoProjectsView.vue](../src/views/VideoProjectsView.vue)
- 前端项目详情：[src/views/VideoProjectDetailView.vue](../src/views/VideoProjectDetailView.vue)
- 工作流执行器：[backend/vimax-api/src/main/java/com/wuyao/vimax/service/workflow/WorkflowStepExecutor.java](../backend/vimax-api/src/main/java/com/wuyao/vimax/service/workflow/WorkflowStepExecutor.java)
- 工作流引擎：[backend/vimax-api/src/main/java/com/wuyao/vimax/service/workflow/WorkflowEngineService.java](../backend/vimax-api/src/main/java/com/wuyao/vimax/service/workflow/WorkflowEngineService.java)
- AI Gateway：[backend/vimax-api/src/main/java/com/wuyao/vimax/service/gateway/AIGatewayService.java](../backend/vimax-api/src/main/java/com/wuyao/vimax/service/gateway/AIGatewayService.java)
- Python Worker：[backend/ai-worker/app/services/workflow_executor.py](../backend/ai-worker/app/services/workflow_executor.py)

## 3. 商用目标定义

商家应能在一个视频项目中完成以下操作：

1. 输入自然语言需求，例如“为新品下午茶制作一条 9:16、5 秒、适合抖音的短视频”。
2. 上传一张或多张商品参考图，可选上传参考视频，并看到上传、校验和授权状态。
3. 服务端文本模型将自然语言转换为结构化、可审核的 VideoBrief。
4. 服务端使用 VideoBrief、已授权的商品事实和参考素材生成首帧图。
5. 商家确认首帧图后，服务端将首帧图和视频提示词提交给视频 Provider。
6. 前端可查看排队、生成、质检、审核和完成状态，刷新页面后状态不丢失。
7. 视频生成完成后，系统在供应商临时 URL 过期前下载到 MinIO，创建最终 Asset 和 Work 记录。
8. 商家可以预览、下载、重新生成、提交审核和导出；任何失败都能看到安全的错误信息并按策略重试。

“商用可用”必须同时满足功能、可靠性、安全、成本和审计要求，不能只以 Provider 返回了任务 ID 作为完成标准。

## 4. 目标架构

采用“同步创建 + 异步执行 + 持久化状态机”的架构：

```text
Vue 商家工作台
    │ HTTPS / JWT
    ▼
Video Workflow API
    │ 创建项目、签名上传、提交工作流、查询状态、审核
    ├── PostgreSQL：项目、工作流、步骤、任务、资产、成本、审计
    ├── Object Storage：参考素材、首帧图、视频、缩略图
    ├── Outbox → RabbitMQ：可靠投递工作流任务
    ├── AI Gateway：能力别名、限流、重试、供应商路由、密钥隔离
    └── Workflow Worker：执行文本、图片、视频、下载、质检、合成
             │
             ├── FluAPI：文本理解、图片/首帧生成
             └── ToAPIs：Seedance 图生视频
```

核心原则：

- API 服务负责鉴权、参数校验、持久化、状态变更和任务编排，不在 HTTP 请求线程内递归执行整条工作流。
- Worker 负责耗时任务，每一个步骤都有幂等键、租约、超时、重试和死信处理。
- Provider Adapter 只负责协议转换，不负责业务状态机。
- 所有外部结果先下载/转存，再把内部 Asset ID 交给业务层。
- 前端只消费内部 API，不直接调用 FluAPI/ToAPIs。

## 5. 统一内部领域模型

### 5.1 VideoProject

建议统一字段：

```json
{
  "id": "vp_01...",
  "tenantId": "tenant_01...",
  "merchantId": "merchant_01...",
  "name": "新品下午茶短视频",
  "userInput": "为新品下午茶制作一条抖音短视频",
  "referenceAssetIds": ["asset_01..."],
  "referenceVideoAssetIds": ["asset_02..."],
  "targetPlatform": "DOUYIN",
  "aspectRatio": "9:16",
  "durationSeconds": 5,
  "qualityMode": "STANDARD",
  "status": "DRAFT",
  "createdBy": "user_01..."
}
```

不要同时使用 `name/projectName`、`brief/userInput`、`runId/runCode`、`status/state` 等同义字段。数据库、Java DTO、前端 API 类型和事件消息必须只保留一套名称。

### 5.2 VideoBrief

文本模型不能直接返回任意自然语言给下游。必须要求结构化 JSON，并使用 JSON Schema 校验：

```json
{
  "version": "video-brief.v1",
  "goal": "展示新品并促成到店",
  "productFacts": ["仅使用已确认商品事实"],
  "audience": "年轻情侣",
  "tone": "温暖、高级、真实",
  "scenes": [
    {
      "index": 1,
      "description": "桌面上的完整下午茶产品",
      "camera": "slow_push_in",
      "motion": "轻微蒸汽和自然光变化",
      "durationSeconds": 5,
      "firstFrameRequired": true
    }
  ],
  "promptForImage": "...",
  "promptForVideo": "...",
  "negativePrompt": "不改变产品外观，不增加未授权品牌元素",
  "aspectRatio": "9:16",
  "durationSeconds": 5,
  "needsHumanReview": true
}
```

模型输出必须经过：Schema 校验 → 商家事实校验 → 敏感内容校验 → 预算和时长校验 → 审核门禁。

### 5.3 WorkflowRun、WorkflowStep、GenerationTask

三者职责必须分离：

- `WorkflowRun`：一次完整的视频生成运行。
- `WorkflowStep`：运行中的业务步骤，如 `INTERPRET_REQUIREMENT`、`GENERATE_FIRST_FRAME`。
- `GenerationTask`：一次可重试、可计费的模型或媒体处理任务。

每个 GenerationTask 至少包含：

```text
id, tenant_id, workflow_run_id, step_id, idempotency_key,
task_type, capability_alias, input_hash, status,
provider_request_id, provider_job_id, result_asset_id,
attempt, max_attempts, next_retry_at, timeout_at,
estimated_cost, actual_cost, error_code, error_message,
created_at, started_at, completed_at
```

内部能力别名建议固定为：

- `TEXT_VIDEO_BRIEF`
- `IMAGE_FIRST_FRAME`
- `VIDEO_IMAGE_TO_VIDEO`
- `MEDIA_DOWNLOAD`
- `VIDEO_COMPOSE`
- `VIDEO_TECHNICAL_QA`

业务层只能使用能力别名，不能直接写 `gpt-image-2`、`seedance-2` 或供应商 URL。

## 6. 目标工作流状态机

推荐最小可商用流程：

```text
DRAFT
  → INPUT_VALIDATED
  → ASSETS_READY
  → REQUIREMENT_INTERPRETED
  → BRIEF_REVIEW
  → FIRST_FRAME_GENERATING
  → FIRST_FRAME_READY
  → FIRST_FRAME_REVIEW
  → VIDEO_GENERATING
  → VIDEO_READY
  → VIDEO_QA
  → FINAL_REVIEW
  → COMPLETED
```

失败和恢复状态：

```text
任何可重试步骤 → RETRY_SCHEDULED → RUNNING
不可重试错误   → FAILED
人工驳回       → NEEDS_REVISION
用户主动停止   → CANCELLED
多次失败       → DEAD_LETTERED
```

状态转移必须由后端白名单函数控制，禁止 Controller 直接修改状态字符串。人工审核通过后必须明确写入下一个业务步骤，不能继续执行当前审核步骤，否则会重复暂停。

## 7. 端到端业务链路

### 7.1 创建项目和上传素材

后端提供：

```text
POST /api/v1/video-projects
POST /api/v1/assets/upload-url
PUT  <presigned-url>
POST /api/v1/assets/{assetId}/complete
POST /api/v1/video-projects/{projectId}/references
```

上传完成接口必须校验：文件大小、MIME、扩展名、图片尺寸、视频时长、病毒扫描结果和对象是否真实存在。参考图和参考视频必须明确区分 `REFERENCE_IMAGE`、`REFERENCE_VIDEO`，并记录授权范围。

### 7.2 文本理解

提交工作流：

```text
POST /api/v1/video-projects/{projectId}/runs
```

API 只创建 WorkflowRun 和 Outbox 事件并立即返回 `runId`。Worker 消费 `INTERPRET_REQUIREMENT` 后：

1. 读取项目文字、已确认商家事实和授权素材元数据；
2. 生成结构化 VideoBrief；
3. 做 Schema、事实、安全和预算校验；
4. 持久化 PromptArtifact 和 Brief；
5. 将状态置为 `BRIEF_REVIEW` 或按低风险策略进入下一步。

### 7.3 首帧图生成

商家确认 Brief 后，创建 `IMAGE_FIRST_FRAME` GenerationTask。任务输入必须包含：

- 结构化 Brief 版本；
- 首帧 Prompt 和 negative prompt；
- 商品参考图的内部 Asset ID 和供应商可访问的临时签名 URL；
- 目标比例、尺寸和质量；
- 幂等键和输入哈希。

图片返回后必须：

1. 解析真实响应；
2. 立即下载到 MinIO；
3. 创建 `GENERATED_FIRST_FRAME` Asset；
4. 记录 ProviderJob 和成本；
5. 运行图片比例、商品一致性和安全区域 QA；
6. 将内部 `firstFrameAssetId` 写回工作流上下文。

### 7.4 图生视频

商家确认首帧图后，创建 `VIDEO_IMAGE_TO_VIDEO` GenerationTask。输入必须包含：

- `image_url`：由内部 Asset 生成的短时签名 URL；
- 视频 Prompt；
- duration；
- aspect ratio；
- model capability alias；
- callback 或轮询策略；
- 项目、运行、步骤和幂等信息。

视频 Provider 任务完成后必须在临时 URL 过期前下载并落库，生成：

- 最终视频 Asset；
- 缩略图 Asset；
- 视频元数据；
- Provider 用量和成本；
- 技术 QA 报告；
- Work 作品记录。

### 7.5 前端状态展示

前端使用：

```text
GET /api/v1/video-projects/{projectId}
GET /api/v1/workflow-runs/{runId}
GET /api/v1/workflow-runs/{runId}/steps
GET /api/v1/generation-tasks/{taskId}
```

第一版可以使用 2–5 秒轮询；后续可增加 SSE/WebSocket。页面刷新必须通过 API 恢复状态，不得使用硬编码测试对象。

## 8. Provider 中转站接入要求

协议来源：

- `C:\Users\Administrator\Desktop\FluAPI 文档`
- `C:\Users\Administrator\Desktop\ToAPIs`
- 仓库内整理文档：[docs/provider-api-documentation.md](./provider-api-documentation.md)

截图、仓库文档和供应商实际返回如有冲突，以供应商当前协议和脱敏后的集成测试结果为准；不要直接相信旧 Adapter 中的 URL 或响应结构。

### 8.1 FluAPI Adapter

需要单独实现并测试：

- 文本理解请求；
- 图片首帧请求；
- 同步响应和异步响应两种模式；
- `data[].url`、任务 ID、错误对象等响应分支；
- 401、403、429、5xx、超时和空响应；
- 供应商临时 URL 下载和转存。

请求和响应使用 Jackson DTO，不要使用 `String.format()` 拼 JSON。请求日志必须脱敏，不能记录 Prompt 中的手机号、地址、密钥或完整素材 URL。

### 8.2 ToAPIs Adapter

需要单独实现并测试：

- Seedance 图生视频提交；
- 任务查询或回调；
- `pending/queued/processing/succeeded/failed` 到内部状态的映射；
- 视频 URL、缩略图、时长、宽高、文件大小解析；
- 3–10 秒时长和 9:16/16:9/1:1 比例校验；
- 临时 URL 过期前下载；
- 供应商错误码到内部错误码的映射。

不要把接口路径、模型版本、时长和比例写死在 Adapter 中；这些应来自 ProviderConfig 和能力别名解析结果。

### 8.3 能力路由和密钥

AI Gateway 负责：

- 根据 capability alias 选择供应商和模型；
- 租户级并发、分钟级和日级限流；
- 重试和熔断；
- 估算成本、实际成本和额度结算；
- ProviderJob 持久化；
- 密钥从环境变量/Secret Manager 读取；
- 轮换和版本管理。

严禁把真实 Key 提交到 Java、SQL、前端、日志或测试快照。当前仓库曾经提交过真实 Key，必须先撤销并轮换，再清理 Git 历史。

## 9. 数据库和基础设施整改

### 9.1 只保留一套数据库契约

当前 `wuyao_vimax`、`wuyao_nexus`、SQL 迁移和 JPA 实体存在不一致。必须完成以下动作：

- 选定唯一数据库名和连接配置；
- 迁移脚本按版本执行并记录 schema version；
- 删除或合并重复的 `assets` 建表脚本；
- 创建并统一 `provider_configs` 或改用已存在的 Provider 配置表；
- 让 `video_projects`、`workflow_runs`、`generation_tasks`、`provider_jobs` 与实体逐列一致；
- 生产环境继续使用 `ddl-auto=validate`，禁止自动改表；
- 为每次迁移增加空库初始化和已有库升级测试。

### 9.2 队列和 Worker

必须启用并验证：

- Outbox Publisher；
- RabbitMQ durable exchange/queue；
- Worker 消费确认、幂等和重试；
- 死信队列和人工重放；
- Worker 崩溃后的任务租约恢复；
- Provider 轮询调度器；
- 结果下载器和 MinIO 存储；
- 任务状态变更事件。

工作流不能依赖 Java 服务中的递归调用。每一步完成后发布下一步事件，或者由可靠的调度器领取下一步。

## 10. 可靠性、成本和安全要求

### 10.1 幂等与恢复

同一 `idempotency_key` 的请求只能产生一个业务任务和一个 Provider 任务。Worker 必须支持：

- 网络超时后查询原任务，而不是盲目重新计费；
- 进程重启后恢复 `RUNNING` 但租约已过期的任务；
- Provider 返回未知状态时保留原始响应并进入人工处理；
- 下载失败的独立重试，不重复生成视频；
- 用户取消与供应商取消的状态映射。

### 10.2 成本和额度

每个文本、图片、视频和媒体处理任务都要记录：

- 估算成本；
- 实际成本；
- 供应商、模型和版本；
- 输入/输出用量；
- 租户、项目、运行和任务引用；
- 计费状态。

提交视频前先预占额度，失败或取消按规则释放，完成后按实际用量结算。前端展示的余额必须来源于服务端账本。

### 10.3 多租户和权限

所有项目、Asset、WorkflowRun、ProviderJob、Work 查询必须带 tenant/merchant 权限过滤。参考素材必须检查：

- 是否属于当前租户；
- 是否已授权给当前商家/项目；
- 是否过期或被撤销；
- 是否允许用于 AI 生成和商业发布。

### 10.4 内容和素材安全

需要增加：

- MIME 和文件内容双重校验；
- 病毒/恶意文件扫描；
- EXIF 和隐私信息处理；
- 敏感内容审核；
- 商标、人物、版权和授权提示；
- 签名 URL 最小权限和短时有效期；
- Provider 回调签名、时间窗和重放防护。

## 11. 测试与验收标准

### 11.1 必须通过的自动化测试

- Java 编译、单元测试和 Spring Context 测试；
- 空数据库执行全部迁移；
- 已有数据库升级迁移；
- Controller 参数和权限测试；
- 工作流状态机合法/非法转移测试；
- 人工审核通过、驳回和重复提交测试；
- Outbox 至 RabbitMQ 投递测试；
- Worker 幂等、重试、租约恢复和死信测试；
- Provider Adapter 使用脱敏 WireMock/MockWebServer 的协议测试；
- 图片/视频结果下载和 MinIO 转存测试；
- 费用预占、结算、释放和重复请求测试；
- 前端上传、轮询、刷新恢复和错误展示测试。

### 11.2 端到端验收场景

场景 A：只有文字。创建项目后能生成结构化 Brief，并在审核后生成首帧和视频。

场景 B：文字 + 商品参考图。首帧和视频必须能访问到商品参考 Asset，且能验证商品一致性。

场景 C：文字 + 参考图 + 参考视频。参考视频只作为授权输入，不能被错误地当成最终成片。

场景 D：Provider 超时。任务进入可重试状态，不重复扣费，不重复创建 Provider 任务。

场景 E：Worker 重启。任务恢复后可以继续轮询并完成，不丢失工作流上下文。

场景 F：视频 URL 即将过期。下载器在有效期内转存；下载失败有告警和可重试任务。

场景 G：跨租户访问。任何项目、素材、任务和结果都必须返回 403/404，不能泄露存在性。

### 11.3 “完成”的硬性定义

只有同时满足以下条件，才允许把能力标记为 `PRODUCTION_READY`：

- 后端源码可编译，应用可从干净环境启动；
- 数据库迁移可重复执行且 schema 与实体一致；
- 文字、首帧图片和视频三种真实 Provider 流程均有脱敏集成测试；
- 结果已进入 MinIO 和 Work/Asset 记录；
- 前端可刷新恢复真实状态；
- 失败、重试、取消、重复提交、限流和余额不足均有覆盖；
- 关键操作有审计日志和指标；
- API Key 已从 Git 历史和日志中清理；
- 完成一次真实小规模试点并核对成本账单。

## 12. 分阶段实施计划

### Phase 0：安全止血和契约冻结

目标：停止错误的生产声明，保护凭证，冻结统一命名。

交付：

- 撤销并轮换已暴露的 Provider Key；
- 清理 Git 历史和日志中的敏感值；
- 确定唯一数据库和 Provider 配置方案；
- 修复 Java 当前编译错误；
- 删除重复依赖和明显的死代码；
- 输出 API/数据库契约基线。

### Phase 1：基础设施可运行

目标：后端、数据库、队列、MinIO、Worker 能从干净环境启动。

交付：

- 迁移脚本和 schema 校验；
- Docker Compose 或部署清单启用 API 和 Worker；
- Outbox、RabbitMQ、死信和任务恢复；
- 健康检查、就绪检查和基础指标；
- Java Context、迁移和队列集成测试。

### Phase 2：项目、素材和权限

目标：商家可以安全创建项目、上传和绑定参考图/参考视频。

交付：

- 项目 CRUD 和真实详情查询；
- 预签名上传、完成确认、媒体校验和转码元数据；
- 参考素材绑定、授权和撤销；
- 前端文件上传、进度、预览和错误处理；
- 跨租户和版权授权测试。

### Phase 3：文本理解和首帧

目标：真实完成“文字/素材 → VideoBrief → 首帧图”。

交付：

- 文本 JSON Schema 和 Prompt 版本化；
- FluAPI 文本和图片 Adapter 按截图协议实测；
- 首帧生成任务、轮询、转存、QA 和人工审核；
- ProviderJob、成本和审计闭环；
- 前端 Brief 和首帧审核界面。

### Phase 4：图生视频和成片入库

目标：真实完成“首帧图 → 视频 → 成品 Asset/Work”。

交付：

- ToAPIs Adapter 按截图协议实测；
- 时长、比例、模型别名和回调/轮询；
- 视频下载、缩略图、元数据和 MinIO 转存；
- 视频技术 QA、最终审核和作品库；
- 失败重试、取消和 URL 过期处理。

### Phase 5：企业级运营能力

目标：支持商用试点和持续运营。

交付：

- 租户配额、成本账单和供应商用量报表；
- OpenTelemetry/Prometheus 指标、告警和链路追踪；
- 管理后台、任务重放和死信处理；
- 压测、故障演练、备份恢复和安全扫描；
- 灰度发布和真实商家试点。

## 13. Claude Code 每次提交的验收模板

每个实现阶段完成后，请在提交说明中包含：

```text
目标：
变更文件：
数据库迁移：
新增 API：
Provider 协议依据：
失败/重试策略：
安全影响：
测试命令及结果：
已知限制：
是否仍存在 Mock：
```

如果某个步骤暂时只能 Mock，必须：

- 使用明确的 `MOCK_ONLY` 配置和状态；
- 在 API 响应中标明演示模式；
- 不写入生产 ProviderJob 或真实成本账本；
- 不把 `MOCK_ONLY` 结果标为 `PRODUCTION_READY`；
- 在本文件的“已知限制”中记录退出条件。

## 14. 最终验收结论模板

完成后，必须能用下面的真实链路演示并留存证据：

```text
商家输入文字
  + 上传参考图/参考视频
  → 创建视频项目
  → 文本模型返回并校验 VideoBrief
  → 人工确认 Brief
  → 图片模型生成首帧并转存 MinIO
  → 人工确认首帧
  → ToAPIs 图生视频任务
  → 轮询/回调完成
  → 下载视频和缩略图到 MinIO
  → 技术 QA 和人工终审
  → 创建 Work/Asset
  → 前端预览、下载和审计记录
```

证据至少包括：项目 ID、WorkflowRun ID、每个 Step ID、GenerationTask ID、ProviderJob ID、内部 Asset ID、Provider 请求 ID、成本记录、QA 报告、审计日志和前端最终预览地址。

在上述证据完整之前，项目只能描述为“企业级视频工作流开发中”，不能描述为“已经具备商用 AI 视频生产能力”。
