# 梧曜星枢 · AI 商家增长平台

梧曜星枢是面向商家和门店的 AI 内容增长工作台。当前仓库已完成第一阶段前端基线，以及第二阶段 M0–M5 首批的浏览器端领域契约、Mock 编排器和一句话创作工作区。

当前实现可以本地演示从一句话需求到计划、Prompt、QA、成本确认和批量任务启动的闭环，但仍不是生产后端。真实模型、队列、数据库、对象存储、服务端鉴权和平台发布能力需要后续 Core API 与 Worker 阶段接入。

## 当前状态

- 分支：`feat/phase-2-ai-creative-compiler`
- 最新提交：`f43dddd feat(ops): add video review and connector contracts`
- GitHub：<https://github.com/Luv10777/1>
- 本地创作工作区：<http://localhost:4173/creative>
- 验证结果：18 个测试通过，lint 通过，生产构建通过

详细状态见 [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md)、[`docs/project-status.md`](docs/project-status.md) 和 [`docs/phase2-progress.md`](docs/phase2-progress.md)。

## 本地运行

```bash
npm install
npm run dev
```

默认开发地址为 <http://localhost:4173/>。生产构建和预览：

```bash
npm run build
npm run preview
```

提交前建议运行：

```bash
npm test
npm run lint -- --quiet
npm run build
```

## 演示登录

- 手机号：任意 11 位、以 `1` 开头的手机号
- 验证码：任意 6 位数字
- 认证模式：`VITE_AUTH_MODE=mock`

## 已完成能力

第一阶段包含 Vue 3 + Vite + Vue Router 工程、深色“星枢控制台”视觉系统、Mock 登录态、路由守卫、运营总览、商家/门店切换、22 个稳定业务路由、403/404、权限前置模型和响应式后台布局。

第二阶段当前包含：

- Campaign / Batch / Item / Step 数据契约和工作流状态机
- Mock Workflow Orchestrator、批量执行、单项失败隔离、事件记录、幂等键、额度预占和成本台账
- Intent JSON Schema 校验、商家事实快照、缺失事实阻断、Creative Plan、Prompt Artifact 和 Prompt QA
- `/creative` 一句话创作工作区、计划预览、成本确认、Prompt 展开、QA 摘要和批量启动
- 租户隔离的 Mock Object Storage、图片比例/商品参考/文字安全区 QA
- 视频 brief、脚本与分镜、角色/场景锁定、时长一致性 QA 和 Mock 视频 Provider
- 高风险内容审核阻断、评论回复草稿、连接器 Token 生命周期、能力状态和回调签名契约

## 生产边界

当前 Provider、对象存储、视频生成和连接器均为 Mock 或契约级实现。业务代码只使用 `TEXT_PLANNER`、`IMAGE_PRIMARY`、`VIDEO_PRIMARY` 等内部能力别名，不绑定真实 FluAPI、ToAPIs 或 Seedance 模型名。

尚未完成的生产能力包括 Core API、数据库迁移、Redis/RabbitMQ/Worker、任务恢复与消费幂等、真实模型和对象存储接入、服务端 RBAC 与多租户强隔离、Webhook 重放防护、FFmpeg/数字人链路、审核中心前端、真实 OAuth、发布日历、评论同步、数据回流和运营后台。

真实 API Key、Cookie、OAuth 凭证和商家生产数据不得写入前端、Git 或 `VITE_` 环境变量。

## 文档

- [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md)：项目长期记忆和交接摘要
- [`docs/project-status.md`](docs/project-status.md)：当前状态、指标、风险和缓解措施
- [`docs/phase2-progress.md`](docs/phase2-progress.md)：第二阶段里程碑进度
- [`docs/phase2-task-list.md`](docs/phase2-task-list.md)：可执行任务清单
- [`docs/phase2-architecture.md`](docs/phase2-architecture.md)：领域契约与架构边界
- [`DESIGN.md`](DESIGN.md)：视觉与组件 tokens
- [`docs/project-audit.md`](docs/project-audit.md)：项目审计与边界
- [`docs/phase-1-plan.md`](docs/phase-1-plan.md)：第一阶段交付记录
- [`docs/vercel-deployment.md`](docs/vercel-deployment.md)：Vercel 部署交接
