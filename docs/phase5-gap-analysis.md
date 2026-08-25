# 第五阶段承接差距分析

> 更新时间：2026-08-25
> 分支：`feat/phase-5-open-ecosystem-growth-os`

## 审计结论

当前仓库承接了前三阶段和 Phase 4 首批浏览器领域契约：内容工作流、消费者交易预览、CRM/归因/增长建议，以及 SaaS 套餐/权益/点数账本。仓库仍没有服务端 API、数据库迁移、真实支付、生产 Worker、OAuth、连接器运行时或真实试点数据，因此第五阶段只能先在 Sandbox/Mock 和内部租户开发。

## 第四阶段承接检查

| 能力 | 状态 | 依据/缺口 |
| --- | --- | --- |
| 套餐、权益、用量、点数 | Mock 满足 | `src/domain/billing.js` 有版本/幂等/余额重建契约；无服务端对账 |
| SaaS 与消费者账务隔离 | 契约满足 | `saas_order` 与 `commerce order` 分离；无数据库/财务权限约束 |
| 商家开通、实施、工单、客户成功、续费 | 未满足 | Phase 4 仅交付套餐页面和文档，暂无后端链路 |
| 多租户越权、备份恢复、灰度、回滚、监控 | 未满足 | 当前是浏览器 Mock，无生产环境演练 |
| 真实试点和供应商成本 | 未满足 | 无真实商家、模型账单或容量数据，必须标记内部模拟 |
| 生产 Secret/共享超级账号 | 部分满足 | 仓库未发现真实密钥；认证仍是浏览器 Mock |

## 第五阶段能力状态

- **Sandbox/Mock（本次首批）**：组织树与策略继承、ABAC 访问判断、OAuth Scope/Token 生命周期、声明式模板安全检查、模板版本安装、Benchmark 最小群组阈值、Growth OS L0–L4 Tool Policy。
- **已真实接通**：无。
- **等待权限**：企业 OIDC/SAML/SCIM、OAuth 生产应用、Webhook 域名、连接器凭证、真实 Benchmark 数据、试点连锁客户。
- **不可用**：正式 Open API、真实 Webhook 投递、任意第三方连接器执行、模板收费市场、跨租户明细 Benchmark、L4 Agent 自主执行。

## 已冻结的关键边界

1. 同一法人且统一管理的连锁可在一个 Tenant 内使用组织树；独立法人/加盟商默认独立 Tenant，通过 `FederationAgreement` 授权汇总。
2. OAuth 默认 Authorization Code + PKCE 或服务到服务 Client Credentials；最小 Scope，Token 短期、可撤销、可审计。
3. 连接器和模板首版只允许平台审核后的声明式/托管能力，不执行用户上传任意二进制代码。
4. Benchmark 只输出满足最小群组阈值的匿名聚合，禁止跨租户原始数据查询和可重识别筛选。
5. Growth OS 只开放 L0–L2；L2 必须有人工批准，高风险动作始终阻断，L3 仅未来低风险灰度，L4 关闭。

## 外部阻塞

- 生产组织/用户目录、SSO、开放平台域名和开发者账号未提供。
- 没有数据库、事件总线、OAuth Token 存储、Webhook Worker、密钥管理和连接器运行环境。
- 没有真实连锁试点、行业 Benchmark 授权或供应商容量/成本数据。
