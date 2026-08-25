# CRM 身份与授权边界

Phase 3 首批提供 `CustomerProfile`、`CustomerIdentity`、`CustomerConsent` 和不可变 `CustomerEvent` 契约。客户档案与渠道身份分离，身份合并必须携带确定性证据；不能只凭姓名或相似手机号合并。

营销授权状态为 `GRANTED / WITHDRAWN / UNKNOWN`。撤回后，服务端自动化 Worker 必须停止对应渠道触达并留下审计事件。当前仓库尚无生产客户数据库、微信登录或消息发送能力，新增契约仅用于 API/Worker 承接。

