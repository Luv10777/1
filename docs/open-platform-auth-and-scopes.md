# 开放平台授权与 Scope

首选 Authorization Code + PKCE；服务到服务使用 Client Credentials。Token 短期、可轮换、可撤销，长期 Secret 不进入前端、URL 或日志。Scope 按资源和动作最小拆分，例如 `store.read`、`campaign.read`、`workflow.run`、`analytics.read`。

Sandbox 首批契约会拒绝未申请 Scope，并在撤销后立即拒绝请求。支付、退款、核销、合同、发票、密钥和删除数据不向普通第三方应用开放。真实 OAuth、开发者审核、回调域名和生产凭证尚未接通。
