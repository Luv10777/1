# 一方志 · AI 商家增长平台

**嘉兴市一方志科技有限公司 · WUYAO NEXUS**

面向本地生活商家的 AI 内容增长工作台。

## 仓库结构

| 目录 | 说明 |
|------|------|
| `src/` | 前端 Vue 3 + Vite |
| `admin/` | 独立管理员前端，单独登录、构建和部署 |
| `backend/growth-api/` | **后端主干**，Java 21 + Spring Boot 3.5.16 |
| `docs/` | 设计文档，`adr/` 记录架构决策 |
| `deploy/` | 测试服务器部署配置 |
| `legacy/` | 已退休的旧实现，只为查阅保留，不参与构建 |

协作约定见 [CLAUDE.md](CLAUDE.md)，后端的八条共享约定见
[backend/growth-api/README.md](backend/growth-api/README.md)。

## 本地运行

```powershell
# 后端：生成本地配置与随机 JWT 密钥，初始化依赖后启动
cd backend/growth-api
./scripts/init-local.ps1
docker compose up -d
docker compose wait minio-init
mvn spring-boot:run
```

另一个终端从仓库根目录启动前端：

```bash
npm install
npm run dev
```

Java 21、Maven 和 Docker 为后端前置依赖。Linux/macOS 启动方法、独立 worker 命令
与两人按模块协作规则见后端 README。后端默认不执行任务，需要另起 worker。

前端 <http://localhost:4173>，`/api` 默认代理到后端 `18080`。如使用其他端口，
在根目录 `.env` 配置 `VITE_DEV_API_TARGET` 后重启 Vite。

管理员后台从仓库根目录单独启动：

```bash
npm run dev:admin
```

本地入口为 `http://127.0.0.1:4177/login`，只在本机监听。信息管理、商家管理和用户实名审核
属于这个独立应用，商家平台不提供后台菜单或管理路由。后台使用独立会话存储；验证码和密码
均通过真实认证接口校验，后端 `PLATFORM_OPERATOR_USER_IDS` 配置的账号才可访问管理数据。
缺少管理员权限或无法确认权限时不加载管理页面。本人提交实名资料的 `/identity` 仍属于用户端。

`npm run build:admin` 输出独立的 `dist-admin/`，`npm run preview:admin` 本地预览构建结果。
测试服务器可用 `docker compose --env-file deploy/.env -f deploy/docker-compose.yml --profile admin up -d --build admin`
启动独立后台服务，默认仅映射 `127.0.0.1:18081`。外部访问时给它配置独立 HTTPS 域名反代，
或通过 SSH 隧道连接；不要将后台静态文件混入商家站点的 `dist/`。

商品库、FAQ 知识库、直播配置已使用真实 API。首次登录后从顶部「＋」创建门店；
门店选择按账号保存，商品、知识集和直播草稿按门店隔离。
接入范围与联调步骤见 [商品与直播配置联调](docs/products-knowledge-live-integration.md)。

## 部署到测试服务器

一台机器跑全套（PostgreSQL / Redis / MinIO / api / worker / Nginx），
从买服务器到能访问的完整步骤见 [deploy/README.md](deploy/README.md)。

```bash
cp deploy/.env.example deploy/.env   # 填口令和公网 IP
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

## 提交前

后端改动在 `backend/growth-api` 执行 `mvn verify`，需要 Docker。

```bash
npm test && npm run lint && npm run typecheck && npm run build
```

## 当前进度

已跑通并验证的能力、以及明确未接通的部分，见 [PROJECT_MEMORY.md](PROJECT_MEMORY.md)。
该文件只记录经过实际运行验证的事实。

## 开发原则

1. **不允许静态假数据和虚假状态**
2. **未配置的能力必须明确标识"未配置"或"演示模式"**
3. **API Key和密钥只能在服务端**
4. **所有AI任务必须进入任务中心**
5. **所有生成结果必须进入作品库**
6. **遵循一方志宣纸、墨青与朱砂视觉规范**
7. **提交前必须通过lint、typecheck、test、build**

## 技术栈

- **前端**：Vue 3、Vite、Vue Router
- **后端**：Java 21、Spring Boot 3.5.16、Spring Security、Flyway
- **数据库**：PostgreSQL（行级安全做租户隔离，pgvector 备用于知识检索）
- **缓存**：Redis
- **异步调度**：PostgreSQL 任务表 + `FOR UPDATE SKIP LOCKED`（不使用消息队列）
- **对象存储**：S3 协议，开发用 MinIO，生产用腾讯云 COS / 火山 TOS
- **AI 供应商**：经能力网关接入，业务代码只认能力别名

## 许可

嘉兴市一方志科技有限公司版权所有
