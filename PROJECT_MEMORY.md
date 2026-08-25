# 梧曜星枢项目记忆

> 更新时间：2026-08-25
>
> 用途：为后续协作者和 AI 代理提供可核对的项目上下文。本文档只记录已经在本地仓库或验证命令中确认的事实，不把 Mock 能力描述成生产能力。

## 当前同步快照（2026-08-25）

- 本地目录：C:\Users\Administrator\梧曜AI；GitHub：github.com/Luv10777/1。
- 当前分支：feat/phase-5-open-ecosystem-growth-os。
- 最新提交：0d9f846 feat(phase5): add open platform sandbox contracts。
- Phase 5 M0/M1：组织树、策略继承、RBAC+ABAC、OAuth Scope/Token、声明式模板、隐私安全 Benchmark、受控 Growth OS 和 /ecosystem Sandbox 页面。
- Phase 5 M3/M4：API 版本/弃用回落、配额、Webhook 幂等投递/重试/死信、Connector Manifest 审核与停用契约。
- 验证基线：34/34 Node 测试通过；npm run lint -- --quiet 通过；npm run build 通过；npm run typecheck 因未安装 vue-tsc 暂不可用。
- 开发服务器保持运行：localhost:4173；/ecosystem、/billing、/consumer 均返回 HTTP 200；当前监听进程 PID 26856。
- 明确未接通：生产 Core API、数据库、OAuth Gateway、Webhook Worker、Connector Runtime、真实 Benchmark 数据、Agent Executor、内容溯源、Cell/灾备和外部生产调用。

## 仓库与运行状态

- 本地目录：`C:\Users\Administrator\梧曜AI`
- GitHub 仓库：<https://github.com/Luv10777/1>
- 当前分支：feat/phase-5-open-ecosystem-growth-os。
- 最新提交：0d9f846 feat(phase5): add open platform sandbox contracts。
- `main` 未直接修改；第二阶段使用独立功能分支协作
- 开发地址：<http://localhost:4173/>
- 创作工作区：<http://localhost:4173/creative>

## 已完成模块

### 第一阶段前端基线

- Vue 3、Vite、Vue Router 工程与深色“星枢控制台”视觉系统
- 手机号验证码 Mock 登录、登录态、退出登录和路由守卫
- 运营总览 Dashboard、商家/门店切换展示
- 22 个稳定业务路由、403/404、SPA fallback
- tenant / merchant / store / user / role / permission 前置模型
- Vercel 配置、设计文档、项目审计和阶段计划

### 第二阶段 M0–M2

- Campaign / Batch / Item / Step / FactSnapshot / CostLedger 数据契约
- `DRAFT → PLANNED → CONFIRMED → QUEUED → RUNNING → QA → NEEDS_REVIEW / APPROVED → PUBLISHED` 状态机，以及失败、取消和重试路径
- Mock Workflow Orchestrator：计划解释、确认、批量执行、单项失败隔离、事件记录、幂等键、额度预占和成本台账
- Provider 统一契约、Mock 文本 Provider、Mock 图片异步 Provider
- Intent JSON Schema 严格校验、商家事实快照、缺失关键事实阻断
- Creative Plan、Prompt Artifact、Prompt QA、成本预估和确认后批量启动
- `/creative` 一句话创作工作区，支持 Prompt 展开和 QA 摘要

### 第二阶段 M3–M5 首批

- Mock Object Storage、租户隔离资产存储、图片比例 QA、商品参考素材一致性 QA
- 确定性文字叠加规范，避免模型直接渲染关键文字
- 视频 brief、脚本、分镜、角色/场景锁定、视频时长一致性 QA
- Mock 视频异步 Provider
- 高风险内容审核阻断、评论回复草稿
- 平台连接器 Token 生命周期、能力状态、默认 `EXPORT_ONLY` 和回调签名保护契约

## 验证基线

最近一次验证已确认：

```text
34 个测试全部通过
npm run lint -- --quiet 通过
npm run build 通过
/ecosystem、/billing、/consumer、/creative 均返回 HTTP 200
```

测试文件：`src/domain/creative.test.js`、`compiler.test.js`、`media.test.js`、`video.test.js`、`review.test.js`、`connectors.test.js`。

## 关键技术决策

- 业务工作流只使用内部能力别名：`TEXT_PLANNER`、`TEXT_WRITER`、`IMAGE_PRIMARY`、`VIDEO_PRIMARY` 等。
- 业务代码不绑定真实 FluAPI、ToAPIs 或 Seedance 模型名称；未来接入只替换 Provider 适配器。
- 当前所有 Provider、对象存储、视频任务和连接器都是 Mock 或契约级实现。
- 未授权平台默认 `EXPORT_ONLY` 或 `USER_CONFIRM`，不伪造官方登录、Cookie、OAuth 或自动发布能力。
- 不在前端、Git 或 `VITE_` 环境变量中保存真实 API Key、Cookie、OAuth 凭证或商家生产数据。
- 每个阶段完成后运行测试、lint、构建，再使用 Conventional Commits 推送功能分支。

## 尚未完成的生产能力

Core API、数据库持久化和迁移、Redis/RabbitMQ/Worker、任务恢复与消费幂等、真实 FluAPI/ToAPIs/Seedance/Image2.0 接入、真实对象存储和签名上传、服务端 RBAC 与多租户强隔离、Webhook 签名/重放防护、真实视频生成与 FFmpeg 合成、数字人授权/TTS/Avatar 链路、审核中心前端、平台 OAuth、发布日历和真实发布、评论同步、数据回流、运营分析、成本结算、退款、运营后台和后端审计日志均未完成。

## 下一步交接顺序

1. 先建立 Core API 和数据库模型，服务端重新校验租户、事实、状态迁移和成本。
2. 再建立可恢复 Worker，处理队列消费、Provider 轮询、重试、幂等和死信。
3. 接入真实对象存储、签名上传和回调安全，再接入真实图片/视频/TTS Provider。
4. 完成审核中心、平台 OAuth、发布日历、评论同步和数据回流。
5. 进行端到端、压测、恢复、灰度和安全验收后，才可宣称生产可用。

## 协作约定

- 协作者从功能分支提交小而可审阅的 Conventional Commit，不直接覆盖他人修改。
- 新能力必须同步更新测试、阶段文档和本文件的“已完成/未完成”边界。
- 任何真实凭证只能进入受控的服务端密钥管理，不得提交到仓库。
