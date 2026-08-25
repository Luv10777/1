# Connector Manifest 与审核

连接器首版是声明式 Manifest。平台托管认证引用、Scope、能力和健康状态，不允许未经审核的第三方二进制或安装脚本进入生产。

## Manifest 约束

- 必须声明唯一 ID、版本、能力白名单和所需 Scope。
- 首批能力为 `READ`、`WRITE`、`WEBHOOK`；越权能力必须新增 ADR 与安全评审。
- Manifest 不得包含 `entrypoint`、`binaryUrl` 或 `installScript`，也不得执行任意代码。

## 生命周期

`DRAFT → REVIEW_REQUIRED → APPROVED → PAUSED / REVOKED`。批准必须记录审核人；撤销必须记录原因，并立即阻止新同步任务和回调。

`src/domain/open-platform.js` 提供上述状态转换的 Sandbox 契约。真实 Connector Runtime、凭据轮换、checkpoint、外部限流和供应商红队测试仍属于后续里程碑。
