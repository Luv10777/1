# 第二阶段可执行任务清单

> 勾选表示本地契约或 Mock 演示已完成，不表示生产接入完成。真实供应商、凭证和服务端能力必须通过独立验收后再标记为生产完成。

## M0：架构落位

- [x] 第一阶段基线、功能分支和远端审计
- [x] Campaign / Batch / Item / Step / FactSnapshot / CostLedger 契约
- [x] 工作流状态机与 Provider Contract
- [x] `docs/phase2-architecture.md`

## M1：工作流底座

- [x] Mock Orchestrator：解析、确认、批量启动、单项失败隔离
- [x] 幂等键、额度预占、成本台账和事件记录
- [x] Provider 错误分类、异步任务形态和任务取消契约
- [ ] Core API 持久化实现：租户、工作流、任务、成本和审计
- [ ] Redis/RabbitMQ Worker、恢复、重试退避和死信处理

## M2：一句话创作编译器

- [x] Intent JSON Schema 与严格校验
- [x] 商家事实快照、来源 ID 和缺失事实阻断
- [x] Creative Plan、PromptArtifact、Prompt QAReport
- [x] `/creative` 工作台、计划预览、成本确认和批量启动
- [ ] 服务端事实目录、版本化快照和权限校验

## M3：媒体底座

- [x] 租户隔离的 Mock Object Storage
- [x] 图片比例、商品参考素材、缺失资产和文字安全区 QA
- [x] 确定性文字叠加规范
- [ ] 真实对象存储、签名上传、缩略图和清理策略
- [ ] 可恢复媒体 Worker 与真实图片 Provider

## M4：视频工作流

- [x] 视频 brief、脚本、分镜、角色/场景锁定
- [x] 视频时长和角色一致性 QA
- [x] Mock 视频异步 Provider Contract
- [ ] Seedance/ToAPIs 真实适配器和任务轮询
- [ ] FFmpeg 合成、字幕、TTS、数字人授权和资产版本管理

## M5：审核与平台连接器

- [x] 高风险内容分类、严重风险阻断和评论回复草稿
- [x] 连接器 Token 生命周期、能力状态和默认 `EXPORT_ONLY`
- [x] 回调签名元数据守卫契约
- [ ] 审核中心前端、人工审批、批量处理和修复建议
- [ ] 真实 OAuth、Webhook 签名/重放防护和发布回执
- [ ] 发布日历、评论同步、评价中心和平台数据回流

## M6：生产化基础

- [ ] Core API 服务、数据库迁移和服务端 RBAC
- [ ] 多租户强隔离、审计日志、密钥管理和告警
- [ ] 端到端契约测试、压测、恢复演练和灰度发布
- [ ] 运营后台、成本结算、退款和数据分析
