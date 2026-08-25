# 第二阶段进度记录

> 更新时间：2026-08-25
>
> 当前分支：`feat/phase-2-ai-creative-compiler` · 最新提交：`f43dddd`

## 总体状态

第二阶段浏览器端契约和首批工作流演示已完成，状态为“可演示、可测试、不可直接生产”。M0、M1、M2、M3、M4、M5 首批均已落地；Core API、持久化、Worker 和真实供应商接入仍未开始。

## 里程碑进度

| 里程碑 | 状态 | 已交付 | 生产缺口 |
| --- | --- | --- | --- |
| M0 架构落位 | 已完成 | 领域对象、状态机、Provider 和租户字段 | 服务端契约落地与迁移 |
| M1 工作流底座 | 已完成（Mock） | 编排、批量执行、失败隔离、事件、幂等、成本 | Core API、队列、恢复、消费幂等 |
| M2 创作编译器 | 已完成（首批） | Intent、事实快照、计划、Prompt、QA、成本确认 | 服务端校验、事实来源系统 |
| M3 媒体底座 | 已完成（首批） | Mock 对象存储、比例/商品参考/文字 QA | 真实存储、签名上传、媒体处理 |
| M4 视频工作流 | 已完成（首批） | brief、脚本、分镜、角色锁定、时长 QA、Mock Provider | 真实视频生成、FFmpeg、数字人授权 |
| M5 审核与连接器 | 已完成（首批） | 风险分级、回复草稿、Token 生命周期、回调守卫 | 审核中心、OAuth、Webhook 后端、真实发布 |
| M6 生产化基础 | 未开始 | — | Core API、数据库、Worker、RBAC、审计 |

## 已完成能力

- 工作流状态机覆盖 `DRAFT → PLANNED → CONFIRMED → QUEUED → RUNNING → QA → NEEDS_REVIEW / APPROVED → PUBLISHED`，并保留失败、取消和重试路径。
- Provider 通过内部能力别名解耦，统一返回请求、任务、成本、错误和资产审计字段。
- Mock Orchestrator 支持计划解释、确认、额度预占、批量启动、单项失败隔离、事件记录和成本台账。
- 编译器执行严格 Intent 校验，只允许已确认的商家事实进入 Prompt；关键事实缺失时会阻断计划。
- 媒体 QA 检查输出比例、商品参考素材、缺失资产和文字安全区，并提供确定性文字叠加规范。
- 视频契约固定时长、角色、场景和分镜，支持一致性 QA 与异步 Mock 任务状态查询。
- 审核契约对高风险和严重风险内容阻断自动通过，连接器默认使用 `EXPORT_ONLY` 或 `USER_CONFIRM`。

## 验证记录

最近一次验证结果：

```text
18 个 Node 测试通过
npm run lint -- --quiet 通过
npm run build 通过
/creative 返回 HTTP 200
```

测试覆盖文件：`creative.test.js`、`compiler.test.js`、`media.test.js`、`video.test.js`、`review.test.js`、`connectors.test.js`。

## Mock 边界

当前没有真实 FluAPI、ToAPIs、Seedance、Image2.0、TTS 或数字人凭证，也没有生产数据库、Redis、RabbitMQ、对象存储、后端 RBAC 和服务端回调处理。生成结果、资产 URL、连接器和视频任务仅用于本地演示，不能宣称为生产发布能力。

## 下一步

1. 建立 Core API：租户、商家、Campaign、Batch、Item、Step、事实快照和成本台账的持久化模型与迁移。
2. 建立可恢复 Worker：队列消费、任务状态恢复、重试退避、Provider 任务轮询、消费幂等和死信处理。
3. 接入真实对象存储和服务端签名上传，补充 Webhook 签名、时间窗、nonce 和重放防护。
4. 在真实凭证和合规授权到位后，分阶段接入图片、视频、TTS/数字人和平台 OAuth。
5. 完成审核中心、发布日历、评论同步、数据回流、运营分析、成本结算和后端审计日志。
