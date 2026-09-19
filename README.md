# 一方志 · AI 商家增长平台

**嘉兴市一方志科技有限公司 · WUYAO NEXUS**

面向本地生活商家的 AI 内容增长工作台。

## 仓库结构

| 目录 | 说明 |
|------|------|
| `src/` | 前端 Vue 3 + Vite |
| `backend/growth-api/` | **后端主干**，Java 21 + Spring Boot 3.5.16 |
| `docs/` | 设计文档，`adr/` 记录架构决策 |
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

前端 <http://localhost:4173>，`/api` 已代理到后端 8080，无需额外配置。

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
