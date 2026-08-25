# 用量计量与点数账本

每个计费动作生成唯一 `UsageEvent`，至少包括 `eventId`、`idempotencyKey`、租户、能力码、数量、单位、工作流引用、供应商用量、成本快照和状态。

点数账本使用不可变流水：`PURCHASE`、`GRANT`、`PROMOTION`、`RESERVE`、`CAPTURE`、`RELEASE`、`ADJUSTMENT`、`EXPIRE`、`REFUND_REVERSAL`。禁止直接覆盖余额；余额必须可由流水重建。

当前 `billing.js` 已验证事件去重、预占去重、部分结算和余额重建语义。实际数据库、队列恢复、每日 Provider 对账和差异补偿尚未接通。

