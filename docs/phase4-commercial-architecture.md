# 第四阶段商业化架构骨架

## 目标

把平台从“可演示的 AI 工作台”推进到可核对的商家商业化主链路：

```text
Lead → 申请/审核 → 报价/合同 → SaaS 收款 → Provisioning
     → 实施/培训 → 首次价值 → 用量/点数 → 工单/客户成功 → 续费
```

第一版继续采用模块化单体 + 异步 Worker，先稳定账务、权益和审计边界，再拆分部署。

## 模块边界

```text
IAM & Tenant → Provisioning → Merchant Workspace
Plan Catalog → Entitlement Engine → AI / Content / Commerce abilities
Usage Metering → Credit Ledger → FinOps & Margin
Sales CRM → Implementation → Support / CSM → Renewal
SaaS Billing → Payment / Invoice adapters
```

### SaaS Billing

负责套餐版本、价格快照、订阅、报价、SaaS 订单、SaaS 收款、退款、应收和发票申请。不能读取或修改消费者交易资金。

### Entitlement Engine

根据订阅、附加包、经审批临时授权、Feature Flag、角色权限和资源限额计算 `allowed / limit / remaining / resetAt / source / reasonCode`。前端只展示结果，服务端 API、Worker 和队列提交必须重复校验。

### Usage / Credit Ledger

计量事件以 `idempotencyKey` 去重；信用账本使用不可变流水表达发放、购买、预占、结算、释放、到期和补偿，余额可从流水重建。供应商成本使用独立价格快照。

## 设计决策

- **实际操作者**：销售、财务、实施、客服和客户成功人员；他们需要知道商家能卖什么、用了多少、是否收款、哪里阻塞、谁负责下一步。
- **主要动作**：核对套餐权益、确认用量和点数、查看账单状态、推进开通、处理异常。
- **感受**：像一张清晰的经营账桌，稳重、可追溯、少动画；财务数字比装饰更重要。
- **领域词汇**：权益快照、点数批次、用量脉冲、开通 Saga、首个价值、续费窗口、毛利水位。
- **色彩世界**：星夜靛蓝、纸张灰、铜色提醒、青绿色健康、琥珀色待处理；颜色承担状态，不作为装饰。
- **签名结构**：每个套餐都以“权益快照 → 本周期水位 → 下一动作”三段式展示，避免营销式价格卡遮蔽限制。
- **拒绝默认**：不把消费者订单当 SaaS 订单、不用可变套餐覆盖历史购买、不用一个 `isEnabled` 代替权益/权限/灰度三层控制。

## 首个 Mock 增量

`src/domain/billing.js` 提供版本化 `PlanVersion`、订阅生命周期、权益合并、UsageEvent、CreditLedger 和独立 SaaS Order 契约。它是 Core API 的输入边界，不是生产收款或收入确认实现。

