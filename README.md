# 梧曜星枢 · AI 商家增长平台

**嘉兴市梧曜科技有限公司 · WUYAO NEXUS**

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

```bash
# 1. 依赖服务
docker compose -f backend/growth-api/docker-compose.yml up -d

# 2. 后端（验证码打在后端控制台，不真发短信）
cd backend/growth-api && mvn spring-boot:run

# 3. 前端
npm install && npm run dev
```

前端 <http://localhost:4173>，`/api` 已代理到后端 8080，无需额外配置。

## 提交前

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
6. **保持深空科技感视觉风格**
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

嘉兴市梧曜科技有限公司版权所有
