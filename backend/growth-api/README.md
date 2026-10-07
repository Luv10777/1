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

视频参考素材的预签名 URL 默认有效 6 小时，可用 `VIDEO_REFERENCE_PRESIGN_TTL_SECONDS=21600`
配置。实际 TTL 为 `max(配置值, VIDEO_MAX_DURATION_SECONDS + 安全余量)`；安全余量为
`max(VIDEO_REFERENCE_URL_SAFETY_SECONDS, VIDEO_PROVIDER_TIMEOUT_SECONDS + 25)`，默认 300 秒，
覆盖连接池等待、连接和响应超时。超过 SigV4 的 7 天上限会阻止启动。工作流时限包含停机和
重试等待，超过时限仍按超时结束，签名有效期不会让任务无限重试。

V18 在 HTTP 前用独立事务保存 `provider_submit_started_at` 和 `provider_submit_body`。
标记为空时可刷新素材 URL；非空表示请求可能已发出，包括响应丢失或进程崩溃的情况，
重试必须发送同一原始 JSON 字节。已过期、进入安全余量窗口或无法解析有效期的 URL
会在本地停止提交，并提示先核对供应商任务记录。V17 历史请求保守标记为可能已发出，
其原始发送字节无法从 JSONB 还原，需要先核对供应商，确认没有任务后再重新创建视频。
升级时先停止旧视频 worker，再执行迁移并启动新版本，避免旧代码绕过提交标记继续发送请求。

视频工作流的取消状态统一为 `CANCELLED`，取消接口返回的 `status` 和 `stage` 均使用该值。
网关兼容供应商输入的 `CANCELED` / `CANCELLED`，统一归一化为 `CANCELLED`，并写入
`video_workflows.provider_status` 和 `video_provider_jobs.status`。供应商取消仍按原有逻辑
将本地 workflow 记为 `FAILED`；用户主动取消才记为 `CANCELLED`。
V22 仅订正上述两张表中历史的 `CANCELED` 值，覆盖 workflow 的 `status`、`stage`、
`provider_status` 和供应商任务的 `status`；逐租户设置上下文，保留 FORCE RLS 与原有时间戳。
升级前停止旧 API、视频 worker 和媒体 worker，执行 V22 后再启动新版本，避免旧进程重新
写入旧拼写。订单与订阅域继续使用 `CANCELED`；现有 `outcome=canceled` 指标标签保持原样。

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
| `IMAGE_API_TIMEOUT` | `10` 秒 | 等待图片模型速率许可的最长时间，在提交意图登记之前执行 |
| `HTTP_MAX_TOTAL` | `200` | 图片模型 HTTP 客户端连接池总数 |
| `HTTP_MAX_PER_ROUTE` | `50` | 单个模型地址的最大连接数 |

图片生成先等待 API 速率许可，再登记 `providerCode=SUBMITTING`，最后调用供应商。
等待超时会显示“图片模型请求过于频繁，请稍后重试”，不登记提交意图，走普通任务重试。
默认最多 3 次尝试，失败后的退避为 20、40 秒；若每次都等待满 10 秒，持续限流可在约
90 秒（另加前处理和调度时间）内耗尽预算。图片详情保留限流原因，预算耗尽后可直接
恢复未提交的任务，不需要核对供应商记录。已有提交意图的记录仍按原来的保守规则处理。

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
| `VIDEO_POLL_SECONDS` | `15` | `growth.video.poll-seconds`，轮询基础间隔；按轮次放大并限制在 5–60 秒 |
| `VIDEO_MAX_POLLS` | `360` | 单个视频最多轮询次数 |
| `VIDEO_MAX_DURATION_SECONDS` | `7200` | 单个视频从创建到终态的最长时间 |
| `VIDEO_MAX_PROVIDER_BYTES` | `1073741824` | 供应商视频导入大小上限；导入采用流式写入 |

提交后的首次查询立即入队，后续间隔为 `min(60, max(5, VIDEO_POLL_SECONDS) × max(1, 轮次))`。
配置键层级、读取方和环境变量的完整核对见[配置键核对报告](../../docs/video-poll-config-audit.md)。

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

### 视频导入失败与重试

下载层通过 `VideoImportException.Reason` 表达错误类别及 `retryable()`，handler 不匹配错误消息。
不可重试错误在第一次执行就调用带 task 领取权校验的 `markFailed`，提交工作流终态、清理未发布
产物并在提交后释放许可，再抛 `NonRetryableTaskException` 让 tasks 行立即失败。网络/存储异常
继续使用原退避；默认三次执行对应首次失败后等 20 秒、第二次失败后等 40 秒，最后一次失败仍
调用原有 `VIDEO_IMPORT_FAILED` 兜底。任务框架、表结构、公开方法签名没有改变。

| 导入错误 | 自动重试同一下载地址 | 分类理由 |
|---|---|---|
| 已发布对象、非本工作流对象键、已有 Asset 引用 | 否（`OUTPUT_PROTECTED`） | 禁止覆盖；拒绝发生在 PUT/删除前 |
| URL 为空或空白 | 否（`MISSING_URL`） | 缺少下载目标；不再把合法领取但缺少 URL 的执行当作 STALE 结束 |
| URL/重定向格式无效、非 HTTPS、包含凭据/片段、指向受保护网络 | 否（`INVALID_URL`） | 本地安全与格式约束，重复执行无法修复 |
| 初始地址或重定向主机 DNS 解析失败 | 是（`TRANSFER_FAILED`） | DNS 故障可能恢复；不视为安全校验失败 |
| 重定向超过 5 次、3xx 缺少/空白 Location | 否（`REDIRECT_LIMIT` / `REDIRECT_LOCATION_MISSING`） | 下载协议不完整或成环 |
| HTTP 408、429、5xx | 是（`HTTP_TRANSIENT`） | 超时、限流或上游故障，保留原有重试预算 |
| HTTP 404/410 | 否（`RESULT_URL_UNAVAILABLE`） | 同一地址不可用，允许人工恢复原供应商任务以重新获取地址，避免新付费提交 |
| 其它非 2xx、非重定向 HTTP 状态 | 否（`HTTP_REJECTED`） | 当前请求被拒绝或响应不符合下载协议 |
| 2xx 缺少 entity 或正常结束的空内容 | 否（`EMPTY_RESPONSE`） | 没有可导入的产物；声明了正 Content-Length 却提前 EOF 属于传输中断，按可重试处理 |
| 声明大小超限、未知长度流实际大小超限 | 否（`TOO_LARGE`） | 本地大小策略不因重试改变；流计数保留超限证据，避免 MinIO 包装异常后丢失分类 |
| 内容类型或 octet-stream 文件头校验失败 | 否（`INVALID_CONTENT`） | 不满足导入格式约束；`video/*` 的真实媒体有效性仍交给 ffprobe，本次不改变格式识别规则 |
| 连接/连接池/读取超时、连接重置、读/关闭流异常、提前 EOF、对象存储不可用 | 是（`TRANSFER_FAILED` 或原存储 BizException） | 无法证明产物不合格，故障可能恢复 |
| 其它未分类的运行时/持久化异常 | 是，有界重试 | 不靠消息推断永久失败，耗尽后仍结束工作流 |
| 候选对象删除失败 | 不替换原错误分类 | 警告并保留登记键，沿用 P1-6 的清理边界 |

`video_provider_jobs.result_expires_at` 在 V15 中存在，但当前网关没有解析到期时间，保存轮询
结果只写 `result_url`，未写到期字段。因此 404/410 只能描述为“地址不可用，可能已失效”，
不能确认过期。该路径工作流使用可人工恢复的 `VIDEO_IMPORT_FAILED`；其它永久导入错误使用
`VIDEO_IMPORT_REJECTED`，阻止直接人工重试。tasks 错误码保留 `VIDEO_IMPORT_<Reason>` 的具体原因。
人工恢复先申请许可、轮询原 `provider_job_id`，不自动创建新的付费供应商任务。

“相同已完成产物与协议响应在自动重试中稳定”是上述永久分类的策略假设；真实供应商/CDN
可能有最终一致性、临时 200 错误页等行为，本次没有获取实际错误响应来验证。分类控制流、
清理和许可用模拟响应及真实 PostgreSQL/Redis/MinIO 验证，不代表供应商行为已验证。

真实 MinIO 测试同时暴露未知长度 PUT 的 SDK 参数错误：`size=-1` 时必须指定有效分片大小。
存储实现仅将该参数改为 10 MiB，已知长度仍由 SDK 选择分片；ObjectStorage 的签名不变。
测试用小文件和缩小的上限验证未知长度导入、实际 SDK 包装后的超限分类，没有下载 1 GB 文件。

提交/轮询 handler 的普通 HTTP 路径已有不可重试判断；内容地址 `/videos/{id}/content`
此前将 HTTP 错误降为普通 BizException，现保留 ProviderHttpException，使轮询 handler 使用
相同的状态分类。配置缺失/不支持协议、成功响应缺少 ID/地址、异常 JSON 等其它网关异常
仍走普通有界重试，需另行细化协议契约，不能声称所有供应商错误已有永久分类。

QA 的探测 IllegalStateException（包括 ffprobe 可执行文件缺失）已由 completeQa 立即转为
`VIDEO_ASSET_INVALID`，并非自动重试三次。同一环境短时间重复启动不存在的程序无益，保留
该行为并用真实缺失可执行路径验证；修复环境后可显式恢复原供应商任务。QA 存储/数据库等
外逸异常现在增加耗尽兜底 `VIDEO_QA_FAILED`，避免 tasks 失败而工作流继续持有许可。
QA 的 `PASSED` 任务结果是既有处理回执，应以工作流终态及 errorCode 判断媒体校验结果。

### 视频成片参数核验

生成视频仍须通过 ffprobe 的视频轨道、有效宽高和时长检查。QA 使用探测出的实际值与工作流
持久化的请求参数比较，不信任供应商响应中的尺寸或时长。通过媒体检查但参数超出容差时，
成片仍为 `READY`，工作流仍为 `SUCCEEDED`，同时返回结构化 `qaWarnings` 并在视频工作台和
数字人页面持续提示差异。保留已付费生成的成片供用户判断是否接受；不会自动重新生成，
也没有新增退款或计费规则。重新生成可能再次计费。

| 比对项 | 容差与判定 | 设计理由 |
|---|---|---|
| 分辨率 | `480p/720p/1080p/4K` 分别按短边 `480/720/1080/2160` 像素比较；允许 `max(16 px, ceil(短边 × 2%))` 的双向偏差，边界包含 | 允许编码对齐带来的小幅尺寸变化；如竖屏 1088×1920 可满足 1080p，不把升降分辨率的明显偏差隐藏掉 |
| 方向与比例 | 明确比例先检查横屏 `width > height`、竖屏 `height > width`；方向相符后允许相对比例误差不超过 2%；`1:1` 直接按比例检查；`auto` 不约束方向和比例 | 横竖翻转不可用小幅尺寸容差豁免；`auto` 没有固定比例承诺 |
| 时长 | 将请求整数秒转为毫秒后比较，允许 `max(300 ms, 请求时长 × 2%)` 的双向偏差，边界包含 | 兼容帧和时间基舍入及小幅供应商偏差；5 秒接受 4.7–5.3 秒，30 秒接受 29.4–30.6 秒 |

这些值是当前产品策略，并非已验证的供应商 SLA。分辨率容差在 480p/720p 为 16 px、
1080p 为 22 px、4K 为 44 px；超过任一项即提示差异，多项差异同时返回。
接口 `VideoDtos.View` 追加 `actualWidth`、`actualHeight`（像素）、`actualDurationMs`（毫秒）
三个可空字段及 `qaWarnings: [{code, message}]`。尚未 QA 时实际值为空且警告为空数组；
历史成功记录缺少元数据时返回 `VIDEO_METADATA_UNAVAILABLE`，提示无法核验。
其它警告码为 `VIDEO_RESOLUTION_MISMATCH`、`VIDEO_ORIENTATION_MISMATCH`、
`VIDEO_RATIO_MISMATCH`、`VIDEO_DURATION_MISMATCH`。方向不符时不重复返回比例警告。
实际值保存在已有 Asset 字段，警告由实际值和原请求推导，无需新迁移。

前端保持原有 `src/utils/request.js` 请求方式，直接接收新增 JSON 字段；两个视频入口复用
预览组件展示实际参数、原请求和警告，并提醒再次计费。旧响应缺少新增字段时仍可预览。
参数差异不触发 P1-6 清理：作品发布保护同样适用于带警告的成片，后续取消或迟到失败不会删除。
ffprobe 失败仍走 `FAILED / VIDEO_ASSET_INVALID / INVALID Asset`，由未发布产物清理路径处理。

本次不增加帧率或编码格式的匹配要求：当前请求和模型能力契约没有这些目标值。
探测沿用现有编码宽高，不解释旋转矩阵或非方形像素（SAR），也不进行逐帧解码、内容/音轨质量检查；
特殊旋转/SAR 视频和浏览器解码兼容性需要另行验证。Mockito 探测结果与真实 PostgreSQL/Redis/MinIO
集成测试覆盖参数比较、持久化、接口序列化和发布保护，不等同于真实供应商或真实 ffprobe 的端到端验证。

### 视频观测

Actuator 的 `video` health 检查 `VIDEO_PROVIDER`、`MEDIA_CPU` 两个队列的 PENDING/RUNNING 数量、
视频供应商配置，以及当前阶段停留过久的工作流。pending **超过**阈值或卡死工作流数量
**超过**容忍值时返回 DOWN；数据库读取失败也返回 DOWN。它是运维信号，不会自动重试或释放许可。

| 配置（`growth.video.health.` 前缀） | 环境变量 | 默认值 |
|---|---|---|
| `provider-pending-threshold` | `VIDEO_HEALTH_PROVIDER_PENDING_THRESHOLD` | 100 |
| `media-pending-threshold` | `VIDEO_HEALTH_MEDIA_PENDING_THRESHOLD` | 20 |
| `stuck-after-seconds` | `VIDEO_HEALTH_STUCK_AFTER_SECONDS` | 跟随 `VIDEO_MAX_DURATION_SECONDS`，默认 7200 秒 |
| `stuck-workflows-threshold` | `VIDEO_HEALTH_STUCK_WORKFLOWS_THRESHOLD` | 0 |
| `snapshot-cache-seconds` | `VIDEO_HEALTH_SNAPSHOT_CACHE_SECONDS` | 15 秒；0 禁用缓存 |

卡死定义为 QUEUED/SUBMITTING/GENERATING/IMPORTING/QA 工作流的 `stage_started_at`
早于当前时间减去 `stuck-after-seconds`。普通轮询、自动退避不刷新阶段开始时间；进入新阶段或
人工恢复才重置。因此持续收到 RUNNING 响应也不会掩盖超时，历史创建时间不会误伤刚恢复的工作流。
V21 增加阶段时间和部分索引 `idx_video_workflow_stuck (tenant_id, status, stage_started_at)`，
旧 V15 的 `(status, created_at DESC)` 索引无法约束阶段时间谓词。历史阶段边界不可恢复，
V21 对活跃行保守地使用 `COALESCE(attempt_started_at, created_at)`，迁移后可能需要人工核对早期告警。

| Micrometer 指标名称 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `video.queue.pending` | Gauge | `queue=VIDEO_PROVIDER/MEDIA_CPU` | 待处理任务，包含尚未到执行时间的轮询 |
| `video.queue.running` | Gauge | `queue=VIDEO_PROVIDER/MEDIA_CPU` | 正在执行的任务 |
| `video.workflow.stuck` | Gauge | 无 | 超过阶段年龄阈值的活跃工作流 |
| `video.submit.duration` | Timer | `outcome` | 创建/恢复到提交阶段结束的墙钟耗时 |
| `video.poll.duration` | Timer | `outcome` | 整个轮询阶段的耗时，包含任务间等待和重试 |
| `video.import.duration` | Timer | `outcome` | 导入阶段耗时，包含排队和重试 |
| `video.qa.duration` | Timer | `outcome` | QA 阶段耗时，包含排队和重试 |
| `video.poll.rounds` | DistributionSummary | `outcome` | 每个结束的轮询阶段仅采样一次 `poll_round` |
| `video.concurrency.active` | Gauge | 无 | Redis 全局视频许可占用 |
| `video.concurrency.tenant.max.active` | Gauge | 无 | 所有租户中最大的 Redis 视频许可占用 |
| `video.concurrency.limit` | Gauge | `scope=global/tenant` | 对应 `VIDEO_GLOBAL_MAX_CONCURRENT` / `VIDEO_TENANT_MAX_CONCURRENT`，默认 20 / 2 |

`outcome` 固定为 `succeeded/failed/canceled/interrupted`；`interrupted` 在人工恢复中断阶段时记录。
Timer 和轮次分布在阶段推进或终态事务提交后记录，回滚、过期执行和正常 RUNNING 轮询不采样，
不会把 QA handler 的正常返回等同于媒体校验成功。Timer 是阶段端到端耗时，不是单次 HTTP/
ffprobe 调用延迟；`poll_round` 只计入接受的轮询结果，不包含调用失败或未被接受的过期结果。
Histogram 支持耗时/轮次分布；Prometheus 导出时名称转换为下划线，并带 `_seconds_*` 或 `_rounds_*` 后缀。

Gauge 按采集时拉取，不放在视频业务请求路径上。工作流统计在每个租户上下文中开启独立只读事务，
保留 FORCE RLS；跨租户卡死计数与最大租户许可共用短缓存，队列与全局许可直接读取。
数据库/Redis 无法读取时对应 Gauge 返回 NaN，避免把缺失数据当成零占用；失败快照也不会复用旧成功值。
跨租户汇总不是同一时刻的数据库/Redis原子快照；租户数很大时应评估采集成本并调整缓存时长。

阶段指标由执行阶段转换的进程记录，需同时采集 API、video worker 和 media worker。
队列/许可 Gauge 是全局视图，不应把多个进程的相同 Gauge 相加；阶段 Timer/轮次样本按实例汇总。
进程在数据库提交与指标写入之间崩溃可能丢失一个样本，指标不是付费调用的审计账本。

基础配置使用 `management.endpoint.health.show-details=when_authorized` 和 ACTUATOR 角色限制，
`prod` profile 进一步设为 `never`；当前 JWT filter 未赋予角色，普通业务 JWT 无法看到详情，
需要受保护的管理接入单独处理。
默认只公开 health。Prometheus 的开启仍按前面的受保护管理入口要求操作。
测试使用临时 RANDOM_PORT 服务、测试凭据和测试专属 `show-details=always`，实际通过 HTTP
访问 health 验证结构。测试专属 Tomcat NIO2 避免本机 Windows/JDK 的 NIO 回环连接异常，
没有改变生产的 HTTP 协议配置或详情公开策略。

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

尚未合入主干的迁移在这里登记。新建迁移前先同步主干、看这张表，取下一个空号并补一行；合入主干后把对应的行删掉。

| 版本 | 内容 | 分支 |
|---|---|---|
| V40 | 品牌库：`brands` 表，`stores.brand_id` | `feat/brand-backend` |
| V41 | 账号角色、店员的门店范围、声音样本按门店开放 | `feat/staff-accounts` |

下一个可用编号：V42。

后端检查：

```bash
mvn verify
```

macOS 可用 `bash scripts/mvn-local.sh verify`。迁移状态查询：

```bash
docker compose exec postgres psql -U growth_owner -d wuyao_growth \
  -c 'select version, description, success from flyway_schema_history order by installed_rank;'
```

### 品牌

层级是 商户（租户）→ 品牌 → 门店：品牌档案属于商户，门店通过 `stores.brand_id` 归到一个品牌名下，
商品、知识库、直播仍然属于门店。素材和生成的作品属于商户，不随品牌划分。

| 接口 | 说明 |
|---|---|
| `GET /api/brands` | 当前商户未删除的品牌，默认品牌在前；每项带 `storeCount`（营业中的门店数） |
| `POST /api/brands` | 新建。只有 `name` 必填 |
| `GET /api/brands/{id}`、`PUT /api/brands/{id}` | 读取、整份保存。`PUT` 必须带 `version`，没带上的字段视为清空 |
| `DELETE /api/brands/{id}` | 归档（软删除），归档后名称可以再用 |
| `POST /api/brands/{id}/default` | 设为默认品牌 |

规则：

- 商户的第一个品牌自动成为默认品牌，已有的门店一并归到它名下；每个商户至多一个默认品牌。
- 新建门店可以带 `brandId`；不带时归到默认品牌，商户还没有品牌时为空。
  修改门店时不带 `brandId` 表示不改变归属。门店只能挂到本商户未删除的品牌，否则返回 1400。
- 还有营业中门店的品牌不能删除；有其他品牌时，默认品牌要先把默认让出去才能删除。两种情况都返回 1409。
- Logo 和两个二维码保存的是素材 ID（`logoAssetId` 等），必须是本商户已上传完成的图片素材；
  预览地址由前端向 `/api/assets/{id}/download-url` 获取。
- 响应不输出值为 null 的字段，未填写的资料在 JSON 里不出现。

V40 建表并启用强制 RLS。`stores` 到 `brands` 是 `(tenant_id, brand_id)` 复合外键：
外键检查不受行级安全约束，由它保证门店挂不到其他商户的品牌上。

品牌模块通过 `brand/BrandStoreLinks` 了解门店归属，由门店模块实现；其他模块用
`BrandService.profileForStore(storeId)` 读取门店所属品牌的文字资料。直播的自动讲解和弹幕回复
已经这样接入：门店有品牌时，品牌名、口号、简介、定位、目标人群和表达风格作为【品牌资料】
随商品资料一起交给文本模型，生成内容的数字和承诺校验也把它算作依据；门店没有品牌时提示词与原来完全一致。
这部分用替身模型验证了提示词内容和校验结果，没有用真实模型验证生成效果。图片和视频创作尚未读取品牌资料。

品牌的新建、修改、删除和设为默认只有管理员能做，商户内的成员都能读取。

### 账号、角色与门店范围

一个商户里有两种账号，角色记在账号上（`users.role`），不记在门店上：

| 角色 | 能进的门店 | 额外能做的事 |
|---|---|---|
| `OWNER` 管理员 | 本商户全部门店，不需要任何登记 | 建店、关店、换门店的品牌；管理品牌；员工管理；上传、克隆、改名、删除声音样本并决定开放给哪些门店 |
| `STAFF` 店员 | 只有 `store_members` 里列出的门店 | 无。可以改本店的地址、电话、营业时间 |

进了门店的人都可以管理该店的商品、知识库、直播，使用开放给该店的声音。素材库、图片和视频创作不分门店，所有成员可用。
判断只有两个入口，新模块不要另写一套：

- `StoreAccessService.requireAccess(storeId, userId)`：这个人能不能进这家门店。
- `AccountService.requireOwner(userId)`：这个人是不是管理员。

两者都按 `userId` 查库，不依赖令牌里的内容，所以 worker 里的任务同样受约束：
账号被停用或移除后，它名下排队中的任务（包括自己开播场次的自动讲解）会因无权访问而失败。

员工管理接口都在 `/api/team/members` 下，只有管理员能调用：

| 接口 | 说明 |
|---|---|
| `GET /api/team/members` | 本商户未移除的账号，管理员在前；店员带 `storeIds`，管理员为 `allStores=true` |
| `POST /api/team/members` | `{phone, name, storeIds}` 添加店员。对方用这个手机号验证码登录后直接进入本商户 |
| `PUT /api/team/members/{id}` | `{name, storeIds}` 改姓名和门店范围，立即生效 |
| `POST /api/team/members/{id}/disable`、`/enable` | 停用后令牌版本加一，已签发的访问令牌和刷新令牌立即失效，也无法再登录 |
| `DELETE /api/team/members/{id}` | 移除：账号行保留（业务记录的创建人仍指向它），状态记为 `REMOVED` 并清空手机号，门店范围清空 |

约束与已知边界：

- 一个手机号只属于一个商户。已注册的手机号（包括自己注册后什么都没做的空商户）加不进来，返回 1409；
  移除后手机号被释放，可以重新注册或被别的商户添加。
- 添加店员不需要对方确认：管理员登记了某个手机号，该手机号的主人登录时就会进入这个商户，而不是开自己的商户。
  需要"邀请—接受"时，应与多商户归属一起做。
- 管理员的账号不能通过这些接口修改；目前没有转让管理员、增设多个管理员或更多角色的接口。
  V41 把迁移前已有的账号全部记为 `OWNER`（此前产品里无法给商户加第二个人）。
- `/api/auth/me` 和登录响应里的 `user.role` 供前端决定显示哪些入口，不是权限依据。

V41 同时删除了 `store_members.role`，并清掉管理员名下的成员记录。

### 声音样本的归属

声音样本归商户所有，能在哪些门店使用由 `voice_sample_stores` 决定（V41）。
`voice_samples.store_id` 保留为"上传时所在的门店"，不再决定谁能用；迁移把每个现有样本开放给它原来的门店，可用范围不变。

- 新上传的样本只开放给上传时所在的门店。管理员用 `PUT /api/voice-samples/{id}/stores` `{storeIds}` 调整，
  每条开放记录带 `granted_by` 和时间；取消开放会删除对应记录。
- 至少保留一家门店；某家门店有进行中的场次正在用这个声音时，不能对这家门店取消开放，也不能删除样本。
- 直播选音色、试听合成都按"样本是否开放给当前门店"判断；`GET /api/stores/{storeId}/voice-samples` 只返回开放给该店的样本，
  每项带 `storeIds`。
- `voice_sample_stores` 到样本和门店都是带 `tenant_id` 的复合外键，样本开放不到别的商户的门店。

### 门店、商品、知识库与直播配置

门店通过 `/api/stores` 管理；建店和关店只有管理员能做，`GET /api/stores` 对管理员返回全部门店，对店员只返回分配给他的门店。
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

`EchoProviderAdapter` 仍用于旧的开发调用。图片工作流明确禁止回退到 Echo；
通过可配置的 HTTP 适配器接模型，默认支持 OpenAI Images 生图与参考图编辑，尺寸限制见接入文档。
计费扣款、完整媒体元数据解析与其他业务模块尚未实现。

嘉兴市一方志科技有限公司版权所有。
