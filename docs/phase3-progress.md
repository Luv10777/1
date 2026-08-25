# 第三阶段进度记录

> 更新时间：2026-08-25
> 分支：`feat/phase-3-growth-closed-loop`
> 当前提交：`3e60113`

## 当前状态

Phase 3 已完成 M0 审计和首个可运行的浏览器增量：统一消费者入口预览、租户隔离的商品/服务/预约/库存/草稿订单/Handoff 契约，以及 CRM、事件归因和需审批的增长建议契约。状态为“可演示、可测试、不可生产收款或发布”。

## 已验证

```text
23 个 Node 测试通过
npm run lint -- --quiet 通过
npm run build 通过
/consumer 返回 HTTP 200
```

## 本次交付

- `/consumer`：统一多商家消费者入口浏览器预览，包含品牌上下文、AI 推荐卡、商品/服务、预约时段、接力单和待支付订单草稿。
- `src/domain/commerce.js`：Offering、Cart、Booking、Inventory Reservation、Order 状态机和一次性租户归属 Handoff。
- `src/domain/customer.js`：客户档案、渠道身份、授权和不可变客户事件。
- `src/domain/attribution.js`：内容到订单事件 Schema、首次/最后非直接触点和辅助触点结果。
- `src/domain/growth.js`：有证据 Insight、草稿 Growth Plan 和审批状态。

## 尚未接通

真实 Core API、数据库迁移、微信登录、小程序工程、AI Handoff 权限、微信支付主体/服务商路由、支付回调/退款/核销、队列 Worker、CRM 数据库、营销消息、对象存储、Webhook 安全、生产监控和 GitHub 远程分支验证均未完成。

## 下一步

1. 在 M1 建立服务端 Consumer BFF 和数据库迁移，替换浏览器内存引擎。
2. 在 M2/M3 接入真实库存、支付、退款、核销和对账适配器；未获商户权限时继续使用 Mock 并保留能力状态。
3. 在 M4/M5 接入微信 AI Skill/Handoff、客户身份、授权、客服和自动化 Worker。
4. 在 M6/M7 接入事件总线、可重算归因、增长建议回写、压测、灾备、灰度和正式发布验收。

