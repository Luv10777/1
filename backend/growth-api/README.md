# growth-api · 后端主干

Java 21 + Spring Boot 3.5.16 的模块化单体。业务模块共用认证、租户隔离、任务调度和对象存储。
本文更新于 2026-09-14，说明当前接口边界和两人协作方式。

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

Linux/macOS（脚本保留已有配置，自动生成随机 JWT 密钥）：

```bash
bash scripts/init-local.sh
docker compose up -d
docker compose wait minio-init
bash scripts/mvn-local.sh spring-boot:run
```

`.env` 使用 Java properties 语法，值不要加引号。应用通过
`spring.config.import` 加载它，操作系统环境变量优先级更高。
示例中的数据库和 MinIO 密码仅用于本地；生产使用独立凭证和最小权限账户。

`JWT_SECRET` 必须是至少 32 字节的随机字符串。空值、短密钥和旧的公开默认密钥均阻止启动。
更换密钥会使已有令牌失效。控制台短信发送器只用于本地开发；阿里云短信配置步骤见
[验证码短信配置](../../docs/aliyun-sms.md)。真实收信需完成签名、模板审核并配置服务端凭证。

如果本地已有服务占用默认端口，在 `.env` 设置 `POSTGRES_PORT`、`REDIS_PORT`、
`MINIO_PORT`、`MINIO_CONSOLE_PORT`，同时把 `DB_URL` 和 `MINIO_ENDPOINT` 改为对应端口。

声音样本可通过 `VOICE_SAMPLE_STORAGE=cos` 使用腾讯云 COS，配置凭证及浏览器跨域规则见
[COS 声音样本配置](../../docs/tencent-cos-voice-storage.md)。旧 MinIO 文件仍按原 key 读取。

## 使用

使用本地 `.env.example` 初始化后，API 地址为 [本地 API](http://localhost:18080)，
PostgreSQL / Redis / MinIO / MinIO 控制台端口分别为 `35432 / 36379 / 39000 / 39001`。
这些端口与 `deploy/docker-compose.dev.yml` 的旧开发环境分开。
`mvn-local.sh` 自动选择 Homebrew 的 Java 21，无需修改全局 shell 配置。
判断新版是否启动时，请同时检查 `18080/actuator/health` 和对应数据库的 Flyway 记录，
不要把其他容器的 `8080` 健康检查当作本次代码的验证结果。
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
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=true --growth.worker.queues=VIDEO --server.port=8091
```

## 配置与任务语义

| 配置 | 默认值 | 含义 |
|---|---|---|
| `growth.worker.enabled` | `false` | API 默认不执行后台任务 |
| `growth.worker.batch-size` | `5` | 每轮每队列最多处理数量；每次只领取一条 |
| `growth.worker.lease` | `30m` | 执行租约时长 |
| `growth.worker.heartbeat-interval` | `10000` ms | 独立线程续租间隔 |
| `growth.worker.poll-interval` | `2000` ms | 一轮执行结束到下一轮的间隔 |

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
再调用 `POST /api/assets/{id}/confirm`。确认接口只在对象存在时返回 `READY`。

`sizeBytes` 可省略；提供时必须非负并与对象存储返回的大小一致。
落库大小取自对象存储。`sha256` 字段仅兼容旧请求，格式校验后也不会作为可信哈希保存。
服务端尚未计算 SHA-256；宽高和时长解析也未实现。探测任务只验证文件存在，
结果明确返回 `objectVerified=true, probed=false`。

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

macOS 可用 `bash scripts/mvn-local.sh verify`。迁移状态查询：

```bash
docker compose exec postgres psql -U growth_owner -d wuyao_growth \
  -c 'select version, description, success from flyway_schema_history order by installed_rank;'
```

### 门店、商品、知识库与直播配置

门店通过 `/api/stores` 管理；创建门店后当前用户成为该门店成员。
商品从 `/api/stores/{storeId}/products` 创建、分页查询，
通过 `/api/products/{id}` 读取、修改和软删除，图片绑定素材 ID，商品 FAQ 使用 `/faqs`。
知识集从 `/api/stores/{storeId}/knowledge-sets` 创建，问答保存后显式发布才进入门店知识上下文。

直播配置从 `/api/stores/{storeId}/live-sessions` 创建，
场次操作使用 `/api/live-sessions/{id}` 下的 `start / pause / resume / end / qa`。
新增问答的 `persistMode` 默认 `SESSION`；`PRODUCT_FAQ` 的 `targetId` 是本场已选商品 ID，
`STORE_KNOWLEDGE` 的 `targetId` 是当前门店 FAQ 知识集 ID（保存为草稿，需在知识库发布）。
本场副本始终保留，开播按本场、商品、门店顺序生成快照，暂停恢复不会重读资产库。

`parse-link` 目前仅设置房间标识；真实平台连接、弹幕接入、语义匹配与 AI 回答尚未接入。

场次规则：一个门店同时只有一场进行中（`LIVE` 或 `PAUSED`，由唯一索引保证）；开始需要至少一件商品和一个可用的主播音色，
直播间标识不是必填；`DELETE /api/live-sessions/{id}` 只能删除未开始的场次；`POST /api/live-sessions/{id}/duplicate`
按已有场次的配置、商品和本场问答新建一场；场次列表只返回摘要，不带知识快照。

播报与自动讲解：`POST /api/live-sessions/{id}/speech` 只登记一条播报并提交任务，返回的是受理状态而不是音频；
`/auto-script`、`/auto-script/start`、`/auto-script/stop` 控制自动讲解，`/speech-items` 返回最近的播报及其进度。
弹幕回复的任务在单独的队列 `LIVE_REPLY` 上：处理 `LIVE` 的 worker 进程会自动多起几个循环专门处理它，和讲解并行，
`growth.worker.queues` 里不用写。同时处理几条弹幕由 `growth.live.reply-lane.concurrency` 决定（默认 3）。
设 `growth.live.reply-lane.enabled=false` 可关掉（那就得另有进程处理 `LIVE_REPLY`）。

话术生成（`LIVE_SCRIPT_GENERATE`）和语音合成（`LIVE_SPEECH_SYNTHESIZE`）都在队列 `LIVE` 上由 worker 执行，
**没有 worker 时不会有任何声音**。文案模型通过 `TEXT_WRITER_URL / TEXT_WRITER_API_KEY / TEXT_WRITER_MODEL` 配置
（OpenAI 兼容的 chat completions），缺失时自动讲解明确报错。细节见 [直播音频说明](../../docs/live-browser-audio-integration.md)。
测试使用独立 Testcontainers 数据库，包括全链路保存、跨租户/门店拒绝、快照冻结和重复商品编辑。

测试需要 Docker，会自动创建并清理独立 PostgreSQL 16 和 MinIO。
覆盖认证并发、任务幂等与事务回滚、租约及重试上限、长任务续租、
真实对象上传确认、参数校验和跨租户读写。GitHub Actions 使用 Java 21 执行同一检查。
Docker 不可用时测试失败，不会静默跳过。

根目录的前端检查仍是 `npm test`、`npm run lint`、`npm run typecheck` 和
`npm run build`。CI 配置提交后还需在 GitHub 将后端检查设为分支保护必需项。

## 能力边界与许可

`EchoProviderAdapter` 仍是占位适配器。真实供应商、计费、完整媒体元数据解析，
以及其他业务模块不属于本次公共基础修复的交付范围。

嘉兴市一方志科技有限公司版权所有。
