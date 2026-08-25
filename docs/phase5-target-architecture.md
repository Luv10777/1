# 第五阶段目标架构骨架

```text
Enterprise Core（组织/策略/预算/审批）
  ├─ Open API Gateway（应用/OAuth/Scope/配额/版本）
  ├─ Connector Runtime（审核 Manifest/凭证/游标/健康）
  ├─ Template Registry（声明式包/版本/安装/安全）
  ├─ Event & Metric Platform（Schema/质量/血缘/重算）
  ├─ Privacy-safe Benchmark（阈值/匿名/退出）
  └─ Growth OS（Planner → Verifier → Policy → Approval → Executor → Stop）
```

第一版保持模块化单体 + 异步 Worker；本地首批只提供领域契约。所有组织、开放平台、连接器、模板、指标和 Agent 状态必须由服务端持久化、审计和幂等控制。

## 组织与策略

`Tenant → OrganizationNode` 支持总部、事业部、区域、加盟商和门店。Brand、MerchantLegalEntity、Store 与组织节点分开。EffectivePolicySnapshot 记录来源节点、版本、继承模式和覆盖理由；`LOCKED` 不可被下级覆盖，`OVERRIDABLE` 仅在允许字段内覆盖，`LOCAL_ONLY` 只在当前节点生效。

## 开放平台

开发者组织 → 应用 → Sandbox/Production 环境 → Credential → Grant/Scope。Open API 与内部 API 分网关；写请求要求 `Idempotency-Key`，列表使用游标，错误包含 `code/message/request_id/retryable/docs_url`。Webhook 至少一次投递，使用事件 ID 幂等、HMAC 签名、时间窗、重试和死信。

## 受控 Growth OS

```text
事实/指标 → Insight → Plan Draft → Simulation → Verifier/Policy
         → Approval → Tool Execution → Budget/Stop → Review
```

Planner 不直接执行；Tool Registry 只暴露白名单；高风险动作包括支付、退款、凭证轮换、删除数据、发布和批量消息，必须阻断或人工审批。L4 永久关闭，L3 只允许未来低风险灰度。
