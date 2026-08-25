# Webhook 投递契约

Webhook 采用租户隔离、事件白名单和可追踪投递记录。当前实现为 Sandbox 内存契约，不会向外部系统发送请求。

## 订阅与安全

- 订阅必须绑定 `tenantId`、事件类型和 HTTPS 端点；仅允许本地开发使用 `localhost` HTTP。
- 端点凭据只保存为 `secretRef`，生产环境必须由密钥托管服务解析，日志不得输出 Secret。
- 每次投递使用 `subscriptionId:eventId` 幂等键，消费者必须按幂等方式处理重复事件。

## 重试与停用

- 失败投递按指数退避进入 `RETRYING`，达到上限后进入 `DEAD_LETTER`。
- 订阅可暂停或停用；暂停不删除历史投递，停用后不得创建新投递。
- 生产 Worker 还需要 HMAC 签名、时间戳防重放、死信重放审批和端到端监控。
