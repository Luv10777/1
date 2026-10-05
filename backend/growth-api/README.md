# growth-api · 后端主干

Java 21 + Spring Boot 3.5.16 的模块化单体。业务模块共用认证、租户隔离、任务调度和对象存储。
本文更新于 2026-09-14，说明当前接口边界和两人协作方式。

2026-09-22 新增 AI 图片创作模块：营销海报、产品套图、结构化规划、逐张生成任务、
480P–4K 画质、作品记录与版本修改。New API / OpenAI 协议配置、可选桥接协议与运行边界见
[图片工作流接入文档](../../docs/image-workflow.md)。真实模型需在服务端单独配置。

视频工作流使用 OnlyRouter 的 Chat Completions 协议配置和 OpenAI 兼容视频接口，任务链为
`VIDEO_SUBMIT → VIDEO_POLL → VIDEO_IMPORT → VIDEO_QA`。供应商查询接口在 `completed`
后通过 `/v1/videos/{id}/content` 返回 302，worker 取得临时结果地址，下载到对象存储后
再向前端签发内部预览地址。
视频模型能力和参数约束由后端 `/api/video/capabilities` 提供；Seedance 供应商通过
`VIDEO_PROVIDER_BASE_URL`、`VIDEO_PROVIDER_API_KEY`、`VIDEO_PROVIDER_SUBMIT_PATH` 和
`VIDEO_PROVIDER_POLL_PATH` 和 `VIDEO_PROVIDER_CONTENT_PATH` 配置，两个路径中都使用
`{jobId}` 占位符。前端使用稳定模型别名，网关将其映射为供应商模型 ID；默认 ID 可通过
`VIDEO_MODEL_SEEDANCE_2_5`、`VIDEO_MODEL_SEEDANCE_2_0`、`VIDEO_MODEL_SEEDANCE_2_0_MINI`
和 `VIDEO_MODEL_SEEDANCE_2_0_FAST` 覆盖。

## 安装与启动

需要 Java 21、Maven 3.9 和已启动的 Docker。以下命令在 `backend/growth-api` 目录运行，
避免加载错误目录中的 `.env`。

Windows PowerShell：

```powershell
./scripts/init-local.ps1
docker compose up -d
docker compose wait minio-init
mvn spring-boot:run
```

`init-local.ps1` 会生成随机 JWT 密钥，已有 `.env` 时保留原配置。
首次运行需等待 PostgreSQL 健康检查通过；可用 `docker compose ps` 检查。

Linux/macOS：

```bash
cp .env.example .env
printf '\nJWT_SECRET=%s\n' "$(openssl rand -base64 48)" >> .env
docker compose up -d
docker compose wait minio-init
mvn spring-boot:run
```

`.env` 使用 Java properties 语法，值不要加引号。应用通过
`spring.config.import` 加载它，操作系统环境变量优先级更高。
示例中的数据库和 MinIO 密码仅用于本地；生产使用独立凭证和最小权限账户。

`JWT_SECRET` 必须是至少 32 字节的随机字符串。空值、短密钥和旧的公开默认密钥均阻止启动。
更换密钥会使已有令牌失效。控制台短信发送器只用于本地开发；阿里云短信配置步骤见
[验证码短信配置](../../docs/aliyun-sms.md)。真实收信需完成签名、模板审核并配置服务端凭证。

如果本地已有服务占用默认端口，在 `.env` 设置 `POSTGRES_PORT`、`REDIS_PORT`、
`MINIO_PORT`、`MINIO_CONSOLE_PORT`，同时把 `DB_URL` 和 `MINIO_ENDPOINT` 改为对应端口。

## 使用

默认只启动 API，地址为 [本地 API](http://localhost:8080)。
需要后台任务时，再启动一个 worker；两个进程使用同一数据库和配置。

```bash
mvn package
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=false
```

另一个终端：

```bash
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=true --server.port=8090
```

不同队列可使用不同 worker 进程：

```bash
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=true --growth.worker.queues=VIDEO_PROVIDER --server.port=8091
```

## 配置与任务语义

| 配置 | 默认值 | 含义 |
|---|---|---|
| `growth.worker.enabled` | `false` | API 默认不执行后台任务 |
| `growth.worker.batch-size` | `5` | 每轮每队列最多处理数量；每次只领取一条 |
| `growth.worker.parallelism` | `1` | 单个 Worker 进程同时执行的任务数；每个执行槽独立续租 |
| `growth.worker.lease` | `30m` | 执行租约时长 |
| `growth.worker.heartbeat-interval` | `10000` ms | 独立线程续租间隔 |
| `growth.worker.poll-interval` | `2000` ms | 一轮执行结束到下一轮的间隔 |

图片创作的资源保护配置：

| 配置 | 默认值 | 含义 |
|---|---:|---|
| `IMAGE_TENANT_MAX_CONCURRENT` | `20` | 未单独配置配额时，单个租户允许的图片任务并发数 |
| `IMAGE_GLOBAL_MAX_CONCURRENT` | `200` | 所有租户共享的图片任务并发上限；计数由 Redis 原子维护 |
| `IMAGE_API_RATE_LIMIT` | `100` | 所有 Worker 共享的图片模型速率上限（请求/秒，依赖 Redis） |
| `HTTP_MAX_TOTAL` | `200` | 图片模型 HTTP 客户端连接池总数 |
| `HTTP_MAX_PER_ROUTE` | `50` | 单个模型地址的最大连接数 |

图片许可的租户计数和全局计数通过 Redis Lua 脚本在一次操作中完成获取与释放，
Redis 不可用时采用拒绝创建的策略。每个新租户会由数据库触发器自动创建默认
`tenant_quotas` 记录；管理员可以按租户覆盖并发值，例如：

```sql
UPDATE tenant_quotas
SET concurrent_limit = 50
WHERE tenant_id = 1;
```

`tenant_quotas` 使用强制 RLS 隔离，应用只应通过租户上下文访问当前租户的记录。
新租户的 `concurrent_limit` 数据库默认值为 20；该字段有值时优先于
`IMAGE_TENANT_MAX_CONCURRENT`。修改默认值需要数据库迁移，调整已有租户可用上述 SQL。

视频工作流使用独立的 Redis 许可计数，不与图片任务共用计数：

| 配置 | 默认值 | 含义 |
|---|---:|---|
| `VIDEO_TENANT_MAX_CONCURRENT` | `2` | 单租户允许占用的视频工作流许可数 |
| `VIDEO_GLOBAL_MAX_CONCURRENT` | `20` | 所有租户共享的视频工作流许可数 |
| `VIDEO_CONCURRENCY_PERMIT_TTL_SECONDS` | `21600` | worker 崩溃后的许可自动回收时间 |
| `VIDEO_MAX_POLLS` | `360` | 单个视频最多轮询次数 |
| `VIDEO_MAX_DURATION_SECONDS` | `7200` | 单个视频从创建到终态的最长时间 |
| `VIDEO_MAX_PROVIDER_BYTES` | `1073741824` | 供应商视频导入大小上限；导入采用流式写入 |

许可只在工作流处于 `QUEUED` 到终态期间持有，成功、失败、取消和超时都会幂等释放。
许可控制的是可接受的活跃工作流数量；实际同时执行的任务仍由
`growth.worker.parallelism` 决定。默认 worker 为 1 个执行槽，生产可在确认 CPU、内存和供应商配额后提高，
但不能超过视频全局许可上限。

生产启动应使用 `--spring.profiles.active=prod`。该 profile 会拒绝本地对象存储、
开发数据库密码和默认 MinIO 凭证，并默认不公开 Prometheus；监控采集需通过内网或
受保护的管理入口开启 `MANAGEMENT_EXPOSURE_INCLUDE=health,prometheus`。

租约必须大于两个心跳间隔。调度器为执行、续租、回收分别保留线程容量。
`FOR UPDATE SKIP LOCKED` 保护领取事务；它不提供外部业务“恰好执行一次”的保证。

任务按“至少一次”执行设计：崩溃或租约失效后可能重试。
`attempts` 是执行轮次，续租和完成/失败回写必须带领取时的轮次；旧轮次无权修改任务状态。
过期回收遵守退避和 `max_attempts`，达到上限后进入 `FAILED`。

供应商调用、扣费和业务写入仍需幂等。使用 `task.id` 派生稳定的业务幂等键，
不要使用每次变化的 `attempts`。任务状态保护无法撤销已发生的外部副作用。

`TaskService.submit` 与业务操作共用事务；相同租户和幂等键并发提交会返回同一任务。
不同业务动作不要复用同一个幂等键。不要在长时间外部调用期间持有业务数据库事务。

## 接口与模块约定

| 约定 | 入口 |
|---|---|
| 统一响应 `{code, message, data}`，成功 code=200 | `common/web/ApiResponse` |
| 分页 `{items, page, size, total, totalPages}`，page 从零开始 | `common/web/PageResult` |
| 错误码按模块分号段 | `common/web/ErrorCode` |
| 认证只走 `Authorization: Bearer <jwt>` | `common/security/JwtAuthFilter` |
| 租户只从 JWT 获取，业务接口不接受 tenantId | `common/tenant/TenantContext` |
| 异步任务实现 TaskHandler，文件预签名直传 | `common/task`、`common/storage` |
| 模型调用只认能力别名 | `common/gateway/ModelAlias` |

错误码：1000–1999 通用，2000–2999 IAM，3000–3999 素材，4000–4999 内容，
5000–5999 客服，6000–6999 发布，7000–7999 分析，8000–8999 GEO，9000–9999 计费。

### 认证

验证码只接受最新一条，成功消费后不能再次使用，也不会重新启用旧验证码。
连续失败五次后拒绝继续校验；计数独立提交，不随登录事务回滚。
发送限流按手机号串行检查。刷新令牌通过行锁保证同一个令牌只有一个轮换请求成功。

`POST /api/auth/password-login` 接受 `{account, password}`，仅支持管理员已创建的用户名账号，
用户名区分大小写，不自动注册。密码使用 BCrypt 哈希，账号可以暂不绑定手机号。
连续输错五次后锁定 15 分钟，失败计数独立提交；登录成功后沿用现有 JWT 和刷新令牌流程。
V5 只添加账号字段，不包含任何默认账号或密码；实际凭证必须单独配置到服务器数据库。

### 素材参考模块

`asset/` 展示预签名上传、确认、分页和任务提交。新模块可参考其分层，
但必须定义自己完整的业务契约，不能直接复制占位行为。

上传流程：调用 `POST /api/assets/upload-url`，前端向返回地址 PUT 文件，
再调用 `POST /api/assets/{id}/confirm`。图片确认成功后返回 `READY`；视频会先返回
`VERIFYING`，待媒体探测任务通过后才变成 `READY`。

`sizeBytes` 可省略；提供时必须非负并与对象存储返回的大小一致。
落库大小取自对象存储。`sha256` 字段仅兼容旧请求，可信哈希由服务端读取对象后计算并保存。
图片确认阶段会使用 `ImageIO` 实际解码、校验像素上限并保存宽高；探测任务会再次
验证对象仍存在，结果返回 `objectVerified=true, probed=true`。视频确认后进入
`VERIFYING`，由 `ffprobe` 解析视频轨道、时长、宽高和容器格式，只有通过校验才会变成
`READY`。参考视频默认限制为 500 MB、300 秒、4096×4096；可通过
`VIDEO_REFERENCE_MAX_BYTES`、`VIDEO_REFERENCE_MAX_DURATION_SECONDS`、
`VIDEO_REFERENCE_MAX_WIDTH` 和 `VIDEO_REFERENCE_MAX_HEIGHT` 调整。

素材分页限制 `size` 在 1–100 之间。重复确认已就绪素材返回已有记录，不重复创建任务。

### 租户表与迁移

业务表必须包含 `tenant_id`、启用并强制 RLS、配置 tenant isolation policy；
完整示例见 `src/main/resources/db/migration/V3__asset.sql`。
IAM 和任务表属于系统级表，访问时必须由服务层约束身份和作用域。

应用数据库账户使用受 RLS 约束的 `growth_app`，不要用迁移管理员账户运行应用。
迁移管理员与应用账户使用独立配置。生产预先创建应用角色和独立密码；
历史 V1 脚本中的本地角色密码不能作为生产密码。

## 协作与验证

每个功能使用独立分支和小批量 PR。一个业务模块由一个人负责 Controller、DTO、
Service、Repository、迁移及测试；公共层指定主要维护人，接口修改由双方一起审查。

模块间通过公开的 Service/DTO 调用，不直接依赖对方 Repository 或 Entity。
新增模块前，在 PR 中明确接口、状态变化、错误码、任务类型和依赖关系。
前后端先对齐这些契约，再分别实现。

创建 Flyway 脚本前双方登记下一个版本号，并先同步主干，避免重复版本。
已合并的迁移不修改。当前 V1–V4 保持不变，后续变更新增迁移。

后端检查：

```bash
mvn verify
```

测试需要 Docker，会自动创建并清理独立 PostgreSQL 16 和 MinIO。
覆盖认证并发、任务幂等与事务回滚、租约及重试上限、长任务续租、
真实对象上传确认、参数校验和跨租户读写。GitHub Actions 使用 Java 21 执行同一检查。
Docker 不可用时测试失败，不会静默跳过。

根目录的前端检查仍是 `npm test`、`npm run lint`、`npm run typecheck` 和
`npm run build`。CI 配置提交后还需在 GitHub 将后端检查设为分支保护必需项。

## 能力边界与许可

`EchoProviderAdapter` 仍用于旧的开发调用。图片工作流明确禁止回退到 Echo；
通过可配置的 HTTP 适配器接模型，默认支持 OpenAI Images 生图与参考图编辑，尺寸限制见接入文档。
计费扣款、完整媒体元数据解析与其他业务模块尚未实现。

嘉兴市一方志科技有限公司版权所有。
