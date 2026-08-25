# 梧曜星枢 · AI 商家增长平台

第一阶段前端工作台，基于 Vue 3、Vite 和 Vue Router。当前默认使用 Mock 认证，展示登录、控制台、稳定业务路由、403/404、权限前置模型和响应式后台布局。

## 本地运行

```bash
npm install
npm run dev
```

生产构建：

```bash
npm run build
```

## 演示登录

- 手机号：任意 11 位、以 `1` 开头的手机号
- 验证码：任意 6 位数字
- 认证模式：`VITE_AUTH_MODE=mock`

## 项目边界

第一阶段不接入真实 FluAPI、ToAPIs、数据库、Redis、RabbitMQ、媒体 Worker 或生产商家数据。真实密钥不得写入前端、Git 或 `VITE_` 变量。

## 文档

- `DESIGN.md`：视觉与组件 tokens
- `docs/project-audit.md`：项目审计与边界
- `docs/phase-1-plan.md`：P1-01～P1-09 交付记录
- `docs/vercel-deployment.md`：Vercel 部署交接
