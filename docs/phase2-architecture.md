# 梧曜星枢第二阶段架构落位（M0/M1）

## 目标

第一阶段保留为 Vue/Vite 产品壳；第二阶段开始把“内容生成”从页面占位升级为可追踪的工作流域模型。当前仓库仍是前端单体，因此先把 Core API 未来需要遵循的领域契约落在 `src/domain/`，用 Mock Provider 运行端到端链路。

## 领域边界

```text
Campaign（一次商家意图）
  └─ Batch（一次确认后的批量执行）
      └─ Item（单张图片/单条视频/单个内容单元）
          └─ Step（解析、编译、生成、QA、审核等步骤）
```

所有对象都携带 `tenantId`，Campaign 额外携带 `merchantId`、`storeId`、`brandId`、`creatorId`。后端接入时，租户归属必须从服务端会话/令牌推导，不能信任浏览器传入值。

## 状态机

`DRAFT → PLANNED → CONFIRMED → QUEUED → RUNNING → QA → NEEDS_REVIEW/APPROVED → PUBLISHED`。

失败仅回到 `QUEUED` 重试，不会重建已经成功的 Item；取消是终态。当前 Mock Orchestrator 严格校验状态迁移，并记录事件。

## Provider Contract

业务只使用 `TEXT_PLANNER`、`IMAGE_PRIMARY` 等内部能力别名，不依赖供应商模型名。适配器的统一结果至少包括 provider、modelAlias、requestId、taskId、状态、usage、预估/实际成本、错误分类、输出资产与审计引用。

当前实现：

- `createMockTextProvider`：模拟意图解析和结构化计划。
- `createMockImageProvider`：模拟异步图像任务创建、状态查询和取消。
- 未来接入 FluAPI/ToAPIs 时，只替换 Provider，不改 Orchestrator 或页面数据结构。

## 幂等与成本

- 创建请求使用 `createIdempotencyKey(scope, key)`。
- 额度预占以 `credit-reservation:{batchId}` 为幂等键，重复启动不会重复预占。
- 成功 Item 单独产生成本台账；失败 Item 可单独重试。

## 当前边界

这是 M0/M1 的浏览器端可运行契约，不等同于生产后端。真实数据库迁移、队列、对象存储、Webhook 签名、服务端 RBAC 和真实供应商密钥必须在 Core API/Worker 中实现，不能由前端 Mock 伪装完成。
