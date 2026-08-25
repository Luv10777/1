# ADR-0002：开放 API 授权与版本策略

## 状态

已接受（Sandbox 契约，生产 OAuth 待联调）。

## 决策

商家授权第三方使用 Authorization Code + PKCE；服务到服务使用 Client Credentials。Scope 最小拆分，Token 短期、可撤销和轮换。开放 API 使用 `/openapi/v1` 独立版本，写请求支持幂等键，列表使用游标，破坏性变更通过新主版本或兼容期迁移。

## 影响

需要服务端 OAuth Token Family、Grant、Scope、配额、审计、Webhook 和弃用公告；支付、退款、核销、合同、发票、密钥和删除数据不开放给普通应用。当前仅验证 Scope 和撤销函数。
