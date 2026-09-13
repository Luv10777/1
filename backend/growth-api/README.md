# growth-api · 后端主干

梧曜星枢 AI 商家增长平台的后端骨架。`common/` 和 `iam/` 已完成并验证通过，
`asset/` 是给各模块照抄的参考实现。

> 旧的 `server/`（com.wuyao.nexus）和 `backend/vimax-api`（com.wuyao.vimax）都不再演进，
> 保留只为查阅。新功能一律写在本项目里。

---

## 快速开始

```bash
docker compose up -d          # postgres + redis + minio
cp .env.example .env          # 填 JWT_SECRET
mvn spring-boot:run
```

两个进程，同一个 jar：

```bash
# api 进程
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=false

# worker 进程
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=true --server.port=8090
```

它们不互相调接口，只通过 `tasks` 表协作。

---

## 八条约定（改之前先商量，这是并行开发的地基）

| # | 约定 | 在哪 |
|---|------|------|
| 1 | 统一响应 `{code, message, data}`，成功 code=200 | `common/web/ApiResponse` |
| 2 | 统一分页 `{items, page, size, total, totalPages}`，入参 page 从 0 开始 | `common/web/PageResult` |
| 3 | 错误码按模块分号段 | `common/web/ErrorCode` |
| 4 | 认证只走 `Authorization: Bearer <jwt>` | `common/security/JwtAuthFilter` |
| 5 | **租户只从 JWT 取，接口一律不接受前端传 tenantId** | `common/tenant/TenantContext` |
| 6 | 异步活儿一律实现 `TaskHandler`，不要各写各的调度 | `common/task/TaskHandler` |
| 7 | 文件预签名直传，不经过后端 | `common/storage/ObjectStorage` |
| 8 | 业务代码只认能力别名，不出现供应商名字 | `common/gateway/ModelAlias` |

错误码号段：
```
1000-1999 通用    2000-2999 iam      3000-3999 asset     4000-4999 content
5000-5999 cs      6000-6999 publish  7000-7999 analytics 8000-8999 geo
9000-9999 billing
```

---

## 加一个模块

以 `asset/` 为模板，四步：

**1. 建表**（`src/main/resources/db/migration/V?__你的模块.sql`）

抄 `V3__asset.sql`。业务表三件事一件不能少：

```sql
tenant_id BIGINT NOT NULL REFERENCES tenants(id),   -- 1
...
ALTER TABLE 你的表 ENABLE ROW LEVEL SECURITY;        -- 2
ALTER TABLE 你的表 FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON 你的表             -- 3
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
```

**2. 建包** `com.wuyao.growth.你的模块`，里面自己分 entity / repository / service / controller。
只在自己的包里动，两个人不会冲突。

**3. Controller** 抄 `AssetController`：方法签名里没有 tenantId，不写 try-catch，返回 ApiResponse。

**4. 异步任务**（可选）实现 `TaskHandler`，用 `taskService.submit(...)` 提交。

---

## 两个机制值得先看懂

### 租户隔离靠数据库，不靠人记得

`AssetRepository` 里**没有一个方法带 tenantId 参数**，连 `findById` 都没有租户判断。
但拿别家的 id 去查，返回的是"不存在"——因为 `TenantConnectionProvider`
在每次取连接时把租户号写进了会话变量，PostgreSQL 的行级安全策略据此过滤。

**实测**：商家 A 拿商家 B 的素材 id 调 confirm 接口 → `{"code":3001,"message":"素材不存在"}`，
B 的数据一个字节没变。

意味着某个 Repository 方法忘了写租户条件，也不会漏数据。

### 任务分发靠行锁，不需要消息队列

`TaskRepository.claimBatch` 那句 `FOR UPDATE SKIP LOCKED` 是整套调度的核心：
别的 worker 锁住的行直接跳过，不排队。所以 worker 起几个都行，同一行只会被一个进程拿到。

因此 `TaskWorker` 用 `@Scheduled` 是安全的，**不需要 ShedLock 之类的分布式锁**。

顺带解决：延迟任务（`run_after`）、退避重试（`attempts` + 指数退避）、
崩溃回收（`lease_expires_at` 过期自动放回）、队列隔离（`queue` 字段）。

队列隔离第一天就要用起来——视频任务 20 分钟、图片任务 30 秒，混在一个队列里视频会把图片堵死：

```bash
java -jar app.jar --growth.worker.enabled=true --growth.worker.queues=IMAGE
java -jar app.jar --growth.worker.enabled=true --growth.worker.queues=VIDEO
```

---

## 已验证 / 未验证

跑起来实测通过的：

- Flyway 4 个迁移全部执行
- 未登录访问受保护接口 → 1401
- 手机号格式校验 → 1400
- 验证码限流（1 分钟 1 条）→ 2001
- 错误验证码 → 2003
- 登录即注册，自动建租户
- refresh 轮换，旧 token 立刻失效 → 2005
- **跨租户越权被数据库挡住 → 3001，且对方数据未被修改**
- 任务提交 → worker 抢占 → 执行 → 回写 SUCCEEDED，幂等键生效

**未验证**：MinIO 预签名（本地没起 MinIO，只验证了失败时返回 1503 而不是裸 500）。
`EchoProviderAdapter` 是占位实现，不发任何网络请求。

## 已知待办

- `UserDetailsServiceAutoConfiguration` 未排除，启动日志会打印一条无用的 generated password
- `AiGateway` 目前取第一个可用适配器，选型/降级/计量策略待实现
- `AssetProbeHandler` 只打日志，没真去读文件头解析宽高时长
- 尚无单元测试
