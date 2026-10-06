# 视频轮询配置键核对报告

更新日期：2026-10-06。范围为 `backend/growth-api/src/main` 的配置与读取方，
以及 `backend/growth-api/.env.example`。未扫描或修改 `legacy/`，未读取实际部署的密钥文件。

## 修复与语义

唯一确认的“YAML 定义与读取键名层级不同”是视频轮询间隔：YAML 中 provider、model 和
poll-seconds 同级，完整键为 `growth.video.poll-seconds`，原代码却读取
`growth.video.provider.poll-seconds`。将 `VideoWorkflowService` 的 `@Value` 改为前者，
默认仍为 15 秒，不提供两个键的兼容读法。max-polls、max-duration-seconds 也位于 growth.video，
读取方与之相符；供应商地址、协议、路径和请求超时仍属于 growth.video.provider。

轮询间隔属于工作流调度策略，与供应商接口协议参数分开更清楚。现有算法为
`min(60, max(5, pollSeconds) * max(1, round))`：首次查询立即入队，后续按轮次放大，
最低 5 秒、最高 60 秒。这次只修读取键名，不修改算法、边界、默认值或重试语义。

## 检查方法与边界

使用 YAML 解析器按实际缩进展开 130 个叶子键，核对所有生产 Java 文件中的 91 处 @Value，
共 75 个不同引用键；同时核对 @Scheduled 与 @ConditionalOnProperty，合计 78 个不同键。
逐字段核对唯一的 @ConfigurationProperties 类型 ImageModelProperties，包含嵌套字段及继承字段。
补查 Environment/getProperty、系统变量直接读取以及业务 getter 调用，并核对 prod profile 的八个叶子键。

修复后 75 个 @Value 键中有 74 个位于 application.yml；唯一例外是
`growth.cors.allowed-origins`，只在 WebConfig 中定义 localhost 的默认值。
它没有 YAML 中另一层级的同名定义，也未在 .env.example 暴露，属于配置未集中声明，
不是本次的层级错配；保留现状，可用规范属性名或 Spring 系统环境映射覆盖。

96 个 growth 自定义叶子键中，72 个有直接注解读取，22 个通过 ImageModelProperties 绑定，
两个没有读取方。后者是 creation-lock-wait-seconds、creation-lock-lease-seconds。
另有两个已绑定但业务代码没有读取的字段：顶层 growth.image.timeout-seconds 和 growth.image.font；
HTTP 请求使用 text/generator 的 timeout-seconds，不使用该顶层 timeoutSeconds。
这四项与 P2-12 的死配置排查相交，本任务只记录，未删除或接入。

34 个框架叶子键由 Boot、Hikari、Hibernate、Actuator 或 LoggingSystem 使用，不应因为没有业务 @Value
而认定为死配置。logging.level 的后缀是动态 logger 名，Hibernate 6 的 BasicBinder 日志名另见表中备注；
这也不是缩进错配，没有在本任务改动。prod 配置有七个既有属性覆盖及一个 profile 激活键，
未发现新增层级错配；未在实际生产 profile 启动并逐项观察这些配置。

.env.example 的 87 个变量全部被 YAML 占位符引用，包含 NEW_API_API_KEY 等嵌套回退。
“被 YAML 引用”不等于“业务行为一定使用”：上述四个图片死配置对应的环境变量保留并标注。
未发现其他“变量已定义而 YAML 无引用”的例子，未改动其他默认值。

这些全量清单依据解析、代码读取和框架配置约定核对；除下述视频轮询链路外，
没有对每一个变量分别启动应用、改变参数或发起外部请求，因此不声称所有配置均经过运行时验证。

## 测试与运行时证据

VideoPollingConfigTest 用 ConfigDataApplicationContextInitializer 加载实际 application.yml，
以 SystemEnvironmentPropertySource 分别提供 7、37 秒，注册真实 VideoWorkflowService bean，
由 Spring 执行 @Value 注入；只 mock 构造依赖，没有手动写入 pollSeconds。
它检查 YAML 解析值、注入字段、第一轮及第二轮退避；未提供变量时检查默认值仍为 15。

修复前实际运行：

```text
Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
expected: 7  but was: 15
expected: 37 but was: 15
BUILD FAILURE
```

修复后实际运行：

```text
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整 verify 在真实进程环境设置 VIDEO_POLL_SECONDS=37 下运行，结束后恢复原进程变量。
FoundationIntegrationTest 启动完整 Spring Boot 应用与 Tomcat，连接 Testcontainers 的
PostgreSQL、Redis、MinIO；新测试检查 System.getenv、Environment、真实服务 bean 字段，
并通过 savePoll 推进工作流，检查数据库中下一条 PENDING 任务的 run_after。
该测试只模拟供应商结果，没有发起付费供应商请求，也没有等待或测量真实网络轮询间隔。

实际运行时输出：

```text
VIDEO_POLL_CONFIG_RUNTIME VIDEO_POLL_SECONDS=37 yaml=37 injected=37 next_task_delay_seconds=37
Tests run: 391, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整 mvn verify 已通过，用时 1 分 14 秒；git diff --check 通过，legacy 无改动。
没有执行生产部署或真实供应商轮询。全量清单中的其他配置只做上述静态核对，
没有逐变量运行验证；本次未改前端，也未重新运行前端检查。

## 启动防护建议

近期可在启动日志打印已解析的轮询基础间隔、5–60 秒边界、max-polls 和最长处理时限，
不打印 API key 或素材 URL；日志有助于运维核对实际值，但单独打印无法可靠识别未知键。
本次的非默认值配置测试保留在 CI 中，比只检查两侧默认 15 更容易捕获回归。

后续可将视频工作流策略与 provider 协议分别做成类型安全的 @ConfigurationProperties，
集中默认值并做范围校验。仅更换绑定形式仍会静默回落默认值；还需要非默认绑定测试、
明确未知字段策略或校验外部设置与最终值，才能暴露拼写错误。
若对整个 growth.video 使用 ignoreUnknownFields=false，需要完整建模 health/provider/model 等子树；
只绑定部分字段就开启严格模式会错误拒绝合法配置。此方案供人工决定，本任务没有实施大规模绑定重构。

## 所有 YAML 叶子键与读取方

以下使用 YAML 中的完整键名。环境列包含嵌套回退引用；“—”代表固定值，不代表无人消费。
业务绑定路径已核对字段与 getter；条件 bean 只在对应 profile/供应商模式启用时读取。

| YAML 完整键 | 环境变量引用 | 读取方与结论 |
|---|---|---|
| `spring.config.import` | — | Spring Boot ConfigData |
| `spring.application.name` | — | Spring Boot Environment / 应用标识 |
| `spring.datasource.url` | `DB_URL` | DataSourceProperties / DataSource 自动配置 |
| `spring.datasource.username` | `DB_USER` | `ProductionConfigurationValidator`；键名一致 |
| `spring.datasource.password` | `DB_PASSWORD` | `ProductionConfigurationValidator`；键名一致 |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_SIZE` | HikariConfig；DataSource 自动配置 |
| `spring.datasource.hikari.minimum-idle` | `DB_POOL_MIN_IDLE` | HikariConfig；DataSource 自动配置 |
| `spring.datasource.hikari.connection-timeout` | `DB_POOL_CONNECTION_TIMEOUT` | HikariConfig；DataSource 自动配置 |
| `spring.datasource.hikari.idle-timeout` | `DB_POOL_IDLE_TIMEOUT` | HikariConfig；DataSource 自动配置 |
| `spring.datasource.hikari.max-lifetime` | `DB_POOL_MAX_LIFETIME` | HikariConfig；DataSource 自动配置 |
| `spring.datasource.hikari.leak-detection-threshold` | `DB_POOL_LEAK_DETECTION` | HikariConfig；DataSource 自动配置 |
| `spring.jpa.open-in-view` | — | JpaProperties / HibernateProperties / JPA 自动配置 |
| `spring.jpa.hibernate.ddl-auto` | — | JpaProperties / HibernateProperties / JPA 自动配置 |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | — | JpaProperties.properties → Hibernate 设置 map |
| `spring.flyway.enabled` | — | FlywayProperties / Flyway 自动配置 |
| `spring.flyway.locations` | — | FlywayProperties / Flyway 自动配置 |
| `spring.flyway.user` | `DB_MIGRATE_USER`, `DB_USER` | `ProductionConfigurationValidator`；键名一致 |
| `spring.flyway.password` | `DB_MIGRATE_PASSWORD`, `DB_PASSWORD` | FlywayProperties / Flyway 自动配置 |
| `spring.data.redis.host` | `REDIS_HOST` | `ProductionConfigurationValidator`；键名一致 |
| `spring.data.redis.port` | `REDIS_PORT` | RedisProperties / Redis 自动配置 |
| `spring.data.redis.password` | `REDIS_PASSWORD` | `ProductionConfigurationValidator`；键名一致 |
| `spring.jackson.time-zone` | — | JacksonProperties / ObjectMapper 自动配置 |
| `spring.jackson.default-property-inclusion` | — | JacksonProperties / ObjectMapper 自动配置 |
| `server.port` | `SERVER_PORT` | ServerProperties / WebServer 自动配置 |
| `server.forward-headers-strategy` | — | ServerProperties / WebServer 自动配置 |
| `server.shutdown` | — | ServerProperties / WebServer 自动配置 |
| `growth.image.base-url` | `NEW_API_BASE_URL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.tenant-max-concurrent` | `IMAGE_TENANT_MAX_CONCURRENT` | `ImageCreationService`；键名一致 |
| `growth.image.global-max-concurrent` | `IMAGE_GLOBAL_MAX_CONCURRENT` | `ImageCreationService`；键名一致 |
| `growth.image.api-rate-limit` | `IMAGE_API_RATE_LIMIT` | `ImageApiRateLimiter`；键名一致 |
| `growth.image.api-timeout` | `IMAGE_API_TIMEOUT` | `ImageApiRateLimiter`；键名一致 |
| `growth.image.upload-max-pixels` | `IMAGE_UPLOAD_MAX_PIXELS` | `AssetService`；键名一致 |
| `growth.image.creation-lock-wait-seconds` | `IMAGE_CREATION_LOCK_WAIT_SECONDS` | 无读取方、无绑定字段；P2-12 交集，未修改 |
| `growth.image.creation-lock-lease-seconds` | `IMAGE_CREATION_LOCK_LEASE_SECONDS` | 无读取方、无绑定字段；P2-12 交集，未修改 |
| `growth.image.text.url` | `IMAGE_TEXT_URL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.text.api-key` | `IMAGE_TEXT_API_KEY`, `NEW_API_API_KEY` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.text.model` | `IMAGE_TEXT_MODEL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.text.temperature` | `IMAGE_TEXT_TEMPERATURE` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.text.max-tokens` | `IMAGE_TEXT_MAX_TOKENS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.text.timeout-seconds` | `IMAGE_TEXT_TIMEOUT_SECONDS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.protocol` | `IMAGE_GENERATOR_PROTOCOL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.url` | `IMAGE_GENERATOR_URL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.edits-url` | `IMAGE_GENERATOR_EDITS_URL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.api-key` | `IMAGE_GENERATOR_API_KEY`, `NEW_API_API_KEY` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.model` | `IMAGE_GENERATOR_MODEL` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.timeout-seconds` | `IMAGE_GENERATOR_TIMEOUT_SECONDS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.quality` | `IMAGE_GENERATOR_QUALITY` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.response-format` | `IMAGE_GENERATOR_RESPONSE_FORMAT` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.generator.download-allowed-origins` | `IMAGE_DOWNLOAD_ALLOWED_ORIGINS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.qualities` | `IMAGE_SUPPORTED_QUALITIES` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.max-output-pixels` | `IMAGE_MAX_OUTPUT_PIXELS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.timeout-seconds` | `IMAGE_TIMEOUT_SECONDS` | ImageModelProperties；可绑定 timeoutSeconds，但生产业务代码未读该顶层字段；P2-12 交集 |
| `growth.image.poll-seconds` | `IMAGE_POLL_SECONDS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.max-polls` | `IMAGE_MAX_POLLS` | `ImageModelProperties`（growth.image 绑定）；字段存在、业务读取路径存在 |
| `growth.image.font` | `IMAGE_FONT` | ImageModelProperties；可绑定 font，但生产业务代码未读该字段；P2-12 交集 |
| `growth.video.tenant-max-concurrent` | `VIDEO_TENANT_MAX_CONCURRENT` | `VideoMetrics` / `VideoWorkflowService`；键名一致 |
| `growth.video.global-max-concurrent` | `VIDEO_GLOBAL_MAX_CONCURRENT` | `VideoMetrics` / `VideoWorkflowService`；键名一致 |
| `growth.video.concurrency-permit-ttl-seconds` | `VIDEO_CONCURRENCY_PERMIT_TTL_SECONDS` | `VideoWorkflowService`；键名一致 |
| `growth.video.max-polls` | `VIDEO_MAX_POLLS` | `VideoWorkflowService`；键名一致 |
| `growth.video.max-duration-seconds` | `VIDEO_MAX_DURATION_SECONDS` | `AssetService` / `VideoWorkflowObservations` / `VideoWorkflowService`；键名一致 |
| `growth.video.reference-presign-ttl-seconds` | `VIDEO_REFERENCE_PRESIGN_TTL_SECONDS` | `AssetService`；键名一致 |
| `growth.video.reference-url-safety-seconds` | `VIDEO_REFERENCE_URL_SAFETY_SECONDS` | `AssetService` / `VideoWorkflowService`；键名一致 |
| `growth.video.health.provider-pending-threshold` | `VIDEO_HEALTH_PROVIDER_PENDING_THRESHOLD` | `VideoHealthIndicator`；键名一致 |
| `growth.video.health.media-pending-threshold` | `VIDEO_HEALTH_MEDIA_PENDING_THRESHOLD` | `VideoHealthIndicator`；键名一致 |
| `growth.video.health.stuck-after-seconds` | `VIDEO_HEALTH_STUCK_AFTER_SECONDS`, `VIDEO_MAX_DURATION_SECONDS` | `VideoWorkflowObservations`；键名一致 |
| `growth.video.health.stuck-workflows-threshold` | `VIDEO_HEALTH_STUCK_WORKFLOWS_THRESHOLD` | `VideoHealthIndicator`；键名一致 |
| `growth.video.health.snapshot-cache-seconds` | `VIDEO_HEALTH_SNAPSHOT_CACHE_SECONDS` | `VideoWorkflowObservations`；键名一致 |
| `growth.video.max-provider-bytes` | `VIDEO_MAX_PROVIDER_BYTES` | `VideoWorkflowService`；键名一致 |
| `growth.video.reference-max-bytes` | `VIDEO_REFERENCE_MAX_BYTES` | `AssetProbeHandler` / `AssetService`；键名一致 |
| `growth.video.reference-max-duration-seconds` | `VIDEO_REFERENCE_MAX_DURATION_SECONDS` | `AssetProbeHandler` / `AssetService`；键名一致 |
| `growth.video.reference-max-width` | `VIDEO_REFERENCE_MAX_WIDTH` | `AssetProbeHandler` / `AssetService`；键名一致 |
| `growth.video.reference-max-height` | `VIDEO_REFERENCE_MAX_HEIGHT` | `AssetProbeHandler` / `AssetService`；键名一致 |
| `growth.video.ffprobe-path` | `FFPROBE_PATH` | `VideoMediaProbe`；键名一致 |
| `growth.video.provider.name` | `VIDEO_PROVIDER_NAME` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.protocol` | `VIDEO_PROVIDER_PROTOCOL` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.base-url` | `VIDEO_PROVIDER_BASE_URL` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.api-key` | `VIDEO_PROVIDER_API_KEY` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.submit-path` | `VIDEO_PROVIDER_SUBMIT_PATH` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.poll-path` | `VIDEO_PROVIDER_POLL_PATH` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.content-path` | `VIDEO_PROVIDER_CONTENT_PATH` | `VideoProviderGateway`；键名一致 |
| `growth.video.provider.timeout-seconds` | `VIDEO_PROVIDER_TIMEOUT_SECONDS` | `AssetService` / `VideoProviderGateway` / `VideoWorkflowService`；键名一致 |
| `growth.video.model.seedance-2-5` | `VIDEO_MODEL_SEEDANCE_2_5` | `VideoProviderGateway`；键名一致 |
| `growth.video.model.seedance-2-0` | `VIDEO_MODEL_SEEDANCE_2_0` | `VideoProviderGateway`；键名一致 |
| `growth.video.model.seedance-2-0-mini` | `VIDEO_MODEL_SEEDANCE_2_0_MINI` | `VideoProviderGateway`；键名一致 |
| `growth.video.model.seedance-2-0-fast` | `VIDEO_MODEL_SEEDANCE_2_0_FAST` | `VideoProviderGateway`；键名一致 |
| `growth.video.poll-seconds` | `VIDEO_POLL_SECONDS` | `VideoWorkflowService`；键名一致 |
| `growth.http.max-total` | `HTTP_MAX_TOTAL` | `HttpClientConfig`；键名一致 |
| `growth.http.max-per-route` | `HTTP_MAX_PER_ROUTE` | `HttpClientConfig`；键名一致 |
| `growth.http.connection-timeout` | `HTTP_CONNECTION_TIMEOUT` | `HttpClientConfig`；键名一致 |
| `growth.http.socket-timeout` | `HTTP_SOCKET_TIMEOUT` | `HttpClientConfig`；键名一致 |
| `growth.jwt.secret` | `JWT_SECRET` | `JwtSecurityValidator` / `JwtService`；键名一致 |
| `growth.jwt.access-ttl` | — | `JwtService`；键名一致 |
| `growth.jwt.refresh-ttl` | — | `JwtService`；键名一致 |
| `growth.sms.provider` | `SMS_PROVIDER` | `AliyunSmsSender` / `ConsoleSmsSender` / `ProductionConfigurationValidator`；键名一致 |
| `growth.sms.code-length` | — | `AuthService`；键名一致 |
| `growth.sms.code-ttl` | — | `AuthService`；键名一致 |
| `growth.sms.per-phone-per-minute` | — | `AuthService`；键名一致 |
| `growth.sms.per-phone-per-day` | — | `AuthService`；键名一致 |
| `growth.sms.per-ip-per-minute` | `SMS_PER_IP_PER_MINUTE` | `AuthService`；键名一致 |
| `growth.sms.per-ip-per-day` | `SMS_PER_IP_PER_DAY` | `AuthService`；键名一致 |
| `growth.sms.global-per-minute` | `SMS_GLOBAL_PER_MINUTE` | `AuthService`；键名一致 |
| `growth.sms.global-per-day` | `SMS_GLOBAL_PER_DAY` | `AuthService`；键名一致 |
| `growth.sms.aliyun.access-key-id` | `ALIYUN_SMS_ACCESS_KEY_ID` | `AliyunSmsSender`；键名一致 |
| `growth.sms.aliyun.access-key-secret` | `ALIYUN_SMS_ACCESS_KEY_SECRET` | `AliyunSmsSender`；键名一致 |
| `growth.sms.aliyun.sign-name` | `ALIYUN_SMS_SIGN_NAME` | `AliyunSmsSender`；键名一致 |
| `growth.sms.aliyun.template-code` | `ALIYUN_SMS_TEMPLATE_CODE` | `AliyunSmsSender`；键名一致 |
| `growth.security.trusted-proxies` | `TRUSTED_PROXY_CIDRS` | `ClientIpResolver`；键名一致 |
| `growth.storage.endpoint` | `MINIO_ENDPOINT` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.public-endpoint` | `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.region` | `MINIO_REGION` | `MinioObjectStorage`；键名一致 |
| `growth.storage.access-key` | `MINIO_ACCESS_KEY` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.secret-key` | `MINIO_SECRET_KEY` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.bucket` | `MINIO_BUCKET` | `MinioObjectStorage`；键名一致 |
| `growth.storage.presign-ttl` | — | `AssetService`；键名一致 |
| `growth.storage.cleanup-interval-ms` | `STORAGE_CLEANUP_INTERVAL_MS` | `AssetService`；键名一致 |
| `growth.worker.enabled` | `WORKER_ENABLED` | `TaskWorker` / `WorkerSchedulingConfig`；键名一致 |
| `growth.worker.queues` | — | `TaskWorker`；键名一致 |
| `growth.worker.poll-interval` | — | `TaskWorker`；键名一致 |
| `growth.worker.batch-size` | — | `TaskWorker`；键名一致 |
| `growth.worker.parallelism` | `WORKER_PARALLELISM` | `TaskWorker`；键名一致 |
| `growth.worker.lease` | — | `TaskService` / `TaskWorker`；键名一致 |
| `growth.worker.heartbeat-interval` | — | `TaskWorker`；键名一致 |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_EXPOSURE_INCLUDE` | WebEndpointProperties / Actuator 自动配置 |
| `management.endpoint.health.show-details` | — | HealthEndpointProperties / Actuator health |
| `management.endpoint.health.roles` | — | HealthEndpointProperties / Actuator health |
| `logging.level.root` | `LOG_LEVEL_ROOT` | LoggingSystem；按 logger 名动态配置 |
| `logging.level.com.wuyao.growth` | `LOG_LEVEL` | LoggingSystem；按 logger 名动态配置 |
| `logging.level.com.wuyao.growth.creative.image` | `LOG_LEVEL_IMAGE` | LoggingSystem；按 logger 名动态配置 |
| `logging.level.org.hibernate.SQL` | `LOG_LEVEL_SQL` | LoggingSystem；按 logger 名动态配置 |
| `logging.level.org.hibernate.type.descriptor.sql.BasicBinder` | `LOG_LEVEL_SQL_PARAMS` | LoggingSystem 动态 logger 名；Hibernate 6 实际绑定日志使用 org.hibernate.orm.jdbc.bind，此名效果未运行验证 |

## 所有代码注解引用键

下表合并重复引用，保留所有读取位置。所有 @Value、@Scheduled 与 @ConditionalOnProperty
引用都在表中；属性值由 YAML 或外部属性源提供，代码默认值不是“未读取 YAML”的证据。

| 引用键 | 读取位置 | YAML 对应 |
|---|---|---|
| `growth.cors.allowed-origins` | `WebConfig.java:17 (@Value)` | 未定义；仅代码默认，已单独说明 |
| `growth.http.connection-timeout` | `HttpClientConfig.java:29 (@Value)` | 存在、层级一致 |
| `growth.http.max-per-route` | `HttpClientConfig.java:26 (@Value)` | 存在、层级一致 |
| `growth.http.max-total` | `HttpClientConfig.java:23 (@Value)` | 存在、层级一致 |
| `growth.http.socket-timeout` | `HttpClientConfig.java:32 (@Value)` | 存在、层级一致 |
| `growth.image.api-rate-limit` | `ImageApiRateLimiter.java:32 (@Value)` | 存在、层级一致 |
| `growth.image.api-timeout` | `ImageApiRateLimiter.java:33 (@Value)` | 存在、层级一致 |
| `growth.image.global-max-concurrent` | `ImageCreationService.java:38 (@Value)` | 存在、层级一致 |
| `growth.image.tenant-max-concurrent` | `ImageCreationService.java:36 (@Value)` | 存在、层级一致 |
| `growth.image.upload-max-pixels` | `AssetService.java:47 (@Value)` | 存在、层级一致 |
| `growth.jwt.access-ttl` | `JwtService.java:34 (@Value)` | 存在、层级一致 |
| `growth.jwt.refresh-ttl` | `JwtService.java:35 (@Value)` | 存在、层级一致 |
| `growth.jwt.secret` | `JwtSecurityValidator.java:17 (@Value)`；`JwtService.java:33 (@Value)` | 存在、层级一致 |
| `growth.security.trusted-proxies` | `ClientIpResolver.java:15 (@Value)` | 存在、层级一致 |
| `growth.sms.aliyun.access-key-id` | `AliyunSmsSender.java:31 (@Value)` | 存在、层级一致 |
| `growth.sms.aliyun.access-key-secret` | `AliyunSmsSender.java:32 (@Value)` | 存在、层级一致 |
| `growth.sms.aliyun.sign-name` | `AliyunSmsSender.java:33 (@Value)` | 存在、层级一致 |
| `growth.sms.aliyun.template-code` | `AliyunSmsSender.java:34 (@Value)` | 存在、层级一致 |
| `growth.sms.code-length` | `AuthService.java:71 (@Value)` | 存在、层级一致 |
| `growth.sms.code-ttl` | `AuthService.java:74 (@Value)` | 存在、层级一致 |
| `growth.sms.global-per-day` | `AuthService.java:92 (@Value)` | 存在、层级一致 |
| `growth.sms.global-per-minute` | `AuthService.java:89 (@Value)` | 存在、层级一致 |
| `growth.sms.per-ip-per-day` | `AuthService.java:86 (@Value)` | 存在、层级一致 |
| `growth.sms.per-ip-per-minute` | `AuthService.java:83 (@Value)` | 存在、层级一致 |
| `growth.sms.per-phone-per-day` | `AuthService.java:80 (@Value)` | 存在、层级一致 |
| `growth.sms.per-phone-per-minute` | `AuthService.java:77 (@Value)` | 存在、层级一致 |
| `growth.sms.provider` | `AliyunSmsSender.java:22 (@ConditionalOnProperty)`；`ConsoleSmsSender.java:13 (@ConditionalOnProperty)`；`ProductionConfigurationValidator.java:40 (@Value)` | 存在、层级一致 |
| `growth.storage.access-key` | `MinioObjectStorage.java:27 (@Value)`；`ProductionConfigurationValidator.java:34 (@Value)` | 存在、层级一致 |
| `growth.storage.bucket` | `MinioObjectStorage.java:29 (@Value)` | 存在、层级一致 |
| `growth.storage.cleanup-interval-ms` | `AssetService.java:226 (@Scheduled)` | 存在、层级一致 |
| `growth.storage.endpoint` | `MinioObjectStorage.java:26 (@Value)`；`MinioObjectStorage.java:30 (@Value)`；`ProductionConfigurationValidator.java:33 (@Value)`；`ProductionConfigurationValidator.java:38 (@Value)` | 存在、层级一致 |
| `growth.storage.presign-ttl` | `AssetService.java:45 (@Value)` | 存在、层级一致 |
| `growth.storage.public-endpoint` | `MinioObjectStorage.java:30 (@Value)`；`ProductionConfigurationValidator.java:38 (@Value)` | 存在、层级一致 |
| `growth.storage.region` | `MinioObjectStorage.java:31 (@Value)` | 存在、层级一致 |
| `growth.storage.secret-key` | `MinioObjectStorage.java:28 (@Value)`；`ProductionConfigurationValidator.java:35 (@Value)` | 存在、层级一致 |
| `growth.video.concurrency-permit-ttl-seconds` | `VideoWorkflowService.java:88 (@Value)` | 存在、层级一致 |
| `growth.video.ffprobe-path` | `VideoMediaProbe.java:22 (@Value)` | 存在、层级一致 |
| `growth.video.global-max-concurrent` | `VideoMetrics.java:29 (@Value)`；`VideoWorkflowService.java:87 (@Value)` | 存在、层级一致 |
| `growth.video.health.media-pending-threshold` | `VideoHealthIndicator.java:26 (@Value)` | 存在、层级一致 |
| `growth.video.health.provider-pending-threshold` | `VideoHealthIndicator.java:25 (@Value)` | 存在、层级一致 |
| `growth.video.health.snapshot-cache-seconds` | `VideoWorkflowObservations.java:34 (@Value)` | 存在、层级一致 |
| `growth.video.health.stuck-after-seconds` | `VideoWorkflowObservations.java:33 (@Value)` | 存在、层级一致 |
| `growth.video.health.stuck-workflows-threshold` | `VideoHealthIndicator.java:27 (@Value)` | 存在、层级一致 |
| `growth.video.max-duration-seconds` | `AssetService.java:59 (@Value)`；`VideoWorkflowObservations.java:33 (@Value)`；`VideoWorkflowService.java:84 (@Value)` | 存在、层级一致 |
| `growth.video.max-polls` | `VideoWorkflowService.java:83 (@Value)` | 存在、层级一致 |
| `growth.video.max-provider-bytes` | `VideoWorkflowService.java:85 (@Value)` | 存在、层级一致 |
| `growth.video.model.seedance-2-0` | `VideoProviderGateway.java:53 (@Value)` | 存在、层级一致 |
| `growth.video.model.seedance-2-0-fast` | `VideoProviderGateway.java:55 (@Value)` | 存在、层级一致 |
| `growth.video.model.seedance-2-0-mini` | `VideoProviderGateway.java:54 (@Value)` | 存在、层级一致 |
| `growth.video.model.seedance-2-5` | `VideoProviderGateway.java:52 (@Value)` | 存在、层级一致 |
| `growth.video.poll-seconds` | `VideoWorkflowService.java:82 (@Value)` | 存在、层级一致 |
| `growth.video.provider.api-key` | `VideoProviderGateway.java:45 (@Value)` | 存在、层级一致 |
| `growth.video.provider.base-url` | `VideoProviderGateway.java:44 (@Value)` | 存在、层级一致 |
| `growth.video.provider.content-path` | `VideoProviderGateway.java:48 (@Value)` | 存在、层级一致 |
| `growth.video.provider.name` | `VideoProviderGateway.java:49 (@Value)` | 存在、层级一致 |
| `growth.video.provider.poll-path` | `VideoProviderGateway.java:47 (@Value)` | 存在、层级一致 |
| `growth.video.provider.protocol` | `VideoProviderGateway.java:50 (@Value)` | 存在、层级一致 |
| `growth.video.provider.submit-path` | `VideoProviderGateway.java:46 (@Value)` | 存在、层级一致 |
| `growth.video.provider.timeout-seconds` | `AssetService.java:63 (@Value)`；`VideoProviderGateway.java:51 (@Value)`；`VideoWorkflowService.java:90 (@Value)` | 存在、层级一致 |
| `growth.video.reference-max-bytes` | `AssetProbeHandler.java:26 (@Value)`；`AssetService.java:49 (@Value)` | 存在、层级一致 |
| `growth.video.reference-max-duration-seconds` | `AssetProbeHandler.java:28 (@Value)`；`AssetService.java:51 (@Value)` | 存在、层级一致 |
| `growth.video.reference-max-height` | `AssetProbeHandler.java:32 (@Value)`；`AssetService.java:55 (@Value)` | 存在、层级一致 |
| `growth.video.reference-max-width` | `AssetProbeHandler.java:30 (@Value)`；`AssetService.java:53 (@Value)` | 存在、层级一致 |
| `growth.video.reference-presign-ttl-seconds` | `AssetService.java:57 (@Value)` | 存在、层级一致 |
| `growth.video.reference-url-safety-seconds` | `AssetService.java:61 (@Value)`；`VideoWorkflowService.java:89 (@Value)` | 存在、层级一致 |
| `growth.video.tenant-max-concurrent` | `VideoMetrics.java:30 (@Value)`；`VideoWorkflowService.java:86 (@Value)` | 存在、层级一致 |
| `growth.worker.batch-size` | `TaskWorker.java:39 (@Value)` | 存在、层级一致 |
| `growth.worker.enabled` | `TaskWorker.java:26 (@ConditionalOnProperty)`；`WorkerSchedulingConfig.java:9 (@ConditionalOnProperty)` | 存在、层级一致 |
| `growth.worker.heartbeat-interval` | `TaskWorker.java:41 (@Value)`；`TaskWorker.java:87 (@Scheduled)` | 存在、层级一致 |
| `growth.worker.lease` | `TaskService.java:25 (@Value)`；`TaskWorker.java:40 (@Value)` | 存在、层级一致 |
| `growth.worker.parallelism` | `TaskWorker.java:42 (@Value)` | 存在、层级一致 |
| `growth.worker.poll-interval` | `TaskWorker.java:63 (@Scheduled)` | 存在、层级一致 |
| `growth.worker.queues` | `TaskWorker.java:38 (@Value)` | 存在、层级一致 |
| `spring.data.redis.host` | `ProductionConfigurationValidator.java:37 (@Value)` | 存在、层级一致 |
| `spring.data.redis.password` | `ProductionConfigurationValidator.java:39 (@Value)` | 存在、层级一致 |
| `spring.datasource.password` | `ProductionConfigurationValidator.java:36 (@Value)` | 存在、层级一致 |
| `spring.datasource.username` | `ProductionConfigurationValidator.java:41 (@Value)` | 存在、层级一致 |
| `spring.flyway.user` | `ProductionConfigurationValidator.java:42 (@Value)` | 存在、层级一致 |

ImageModelProperties 的 YAML 绑定字段是 base-url、qualities、max-output-pixels、timeout-seconds、
poll-seconds、max-polls、font，text 下的 url/api-key/model/temperature/max-tokens/timeout-seconds，
以及 generator 下的 protocol/url/edits-url/api-key/model/timeout-seconds/quality/response-format/
download-allowed-origins，共 22 项。Generator 继承 Endpoint 的 temperature/maxTokens 也可以绑定，
但 YAML 未声明；当前只有文本规划分支读取这两个参数，不构成已定义键的层级错配。

## .env.example 全量引用清单

这里只记录变量名，不复制凭证值。每一项都可到达 YAML；业务无读取方的例外明确标出。

| .env.example 变量 | YAML 完整键 | 结论 |
|---|---|---|
| `DB_URL` | `spring.datasource.url` | 有 YAML 引用；读取/绑定路径见上表 |
| `DB_USER` | `spring.datasource.username`；`spring.flyway.user` | 有 YAML 引用；读取/绑定路径见上表 |
| `DB_PASSWORD` | `spring.datasource.password`；`spring.flyway.password` | 有 YAML 引用；读取/绑定路径见上表 |
| `DB_MIGRATE_USER` | `spring.flyway.user` | 有 YAML 引用；读取/绑定路径见上表 |
| `DB_MIGRATE_PASSWORD` | `spring.flyway.password` | 有 YAML 引用；读取/绑定路径见上表 |
| `REDIS_HOST` | `spring.data.redis.host` | 有 YAML 引用；读取/绑定路径见上表 |
| `REDIS_PORT` | `spring.data.redis.port` | 有 YAML 引用；读取/绑定路径见上表 |
| `REDIS_PASSWORD` | `spring.data.redis.password` | 有 YAML 引用；读取/绑定路径见上表 |
| `TRUSTED_PROXY_CIDRS` | `growth.security.trusted-proxies` | 有 YAML 引用；读取/绑定路径见上表 |
| `SMS_PER_IP_PER_MINUTE` | `growth.sms.per-ip-per-minute` | 有 YAML 引用；读取/绑定路径见上表 |
| `SMS_PER_IP_PER_DAY` | `growth.sms.per-ip-per-day` | 有 YAML 引用；读取/绑定路径见上表 |
| `SMS_GLOBAL_PER_MINUTE` | `growth.sms.global-per-minute` | 有 YAML 引用；读取/绑定路径见上表 |
| `SMS_GLOBAL_PER_DAY` | `growth.sms.global-per-day` | 有 YAML 引用；读取/绑定路径见上表 |
| `JWT_SECRET` | `growth.jwt.secret` | 有 YAML 引用；读取/绑定路径见上表 |
| `SMS_PROVIDER` | `growth.sms.provider` | 有 YAML 引用；读取/绑定路径见上表 |
| `ALIYUN_SMS_ACCESS_KEY_ID` | `growth.sms.aliyun.access-key-id` | 有 YAML 引用；读取/绑定路径见上表 |
| `ALIYUN_SMS_ACCESS_KEY_SECRET` | `growth.sms.aliyun.access-key-secret` | 有 YAML 引用；读取/绑定路径见上表 |
| `ALIYUN_SMS_SIGN_NAME` | `growth.sms.aliyun.sign-name` | 有 YAML 引用；读取/绑定路径见上表 |
| `ALIYUN_SMS_TEMPLATE_CODE` | `growth.sms.aliyun.template-code` | 有 YAML 引用；读取/绑定路径见上表 |
| `MINIO_ENDPOINT` | `growth.storage.endpoint`；`growth.storage.public-endpoint` | 有 YAML 引用；读取/绑定路径见上表 |
| `MINIO_ACCESS_KEY` | `growth.storage.access-key` | 有 YAML 引用；读取/绑定路径见上表 |
| `MINIO_SECRET_KEY` | `growth.storage.secret-key` | 有 YAML 引用；读取/绑定路径见上表 |
| `MINIO_BUCKET` | `growth.storage.bucket` | 有 YAML 引用；读取/绑定路径见上表 |
| `WORKER_ENABLED` | `growth.worker.enabled` | 有 YAML 引用；读取/绑定路径见上表 |
| `WORKER_PARALLELISM` | `growth.worker.parallelism` | 有 YAML 引用；读取/绑定路径见上表 |
| `NEW_API_BASE_URL` | `growth.image.base-url` | 有 YAML 引用；读取/绑定路径见上表 |
| `NEW_API_API_KEY` | `growth.image.text.api-key`；`growth.image.generator.api-key` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_PROTOCOL` | `growth.image.generator.protocol` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_URL` | `growth.image.text.url` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_MODEL` | `growth.image.text.model` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_API_KEY` | `growth.image.text.api-key` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_TEMPERATURE` | `growth.image.text.temperature` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_MAX_TOKENS` | `growth.image.text.max-tokens` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TEXT_TIMEOUT_SECONDS` | `growth.image.text.timeout-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_URL` | `growth.image.generator.url` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_EDITS_URL` | `growth.image.generator.edits-url` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_MODEL` | `growth.image.generator.model` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_TIMEOUT_SECONDS` | `growth.image.generator.timeout-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_QUALITY` | `growth.image.generator.quality` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GENERATOR_RESPONSE_FORMAT` | `growth.image.generator.response-format` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_DOWNLOAD_ALLOWED_ORIGINS` | `growth.image.generator.download-allowed-origins` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_TIMEOUT_SECONDS` | `growth.image.timeout-seconds` | 可绑定 timeoutSeconds，但生产业务代码未读该顶层字段；P2-12 交集 |
| `IMAGE_SUPPORTED_QUALITIES` | `growth.image.qualities` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_MAX_OUTPUT_PIXELS` | `growth.image.max-output-pixels` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_FONT` | `growth.image.font` | 可绑定 font，但生产业务代码未读该字段；P2-12 交集 |
| `IMAGE_TENANT_MAX_CONCURRENT` | `growth.image.tenant-max-concurrent` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_GLOBAL_MAX_CONCURRENT` | `growth.image.global-max-concurrent` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_API_RATE_LIMIT` | `growth.image.api-rate-limit` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_API_TIMEOUT` | `growth.image.api-timeout` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_UPLOAD_MAX_PIXELS` | `growth.image.upload-max-pixels` | 有 YAML 引用；读取/绑定路径见上表 |
| `IMAGE_CREATION_LOCK_WAIT_SECONDS` | `growth.image.creation-lock-wait-seconds` | 无读取方、无绑定字段；P2-12 交集，未修改 |
| `IMAGE_CREATION_LOCK_LEASE_SECONDS` | `growth.image.creation-lock-lease-seconds` | 无读取方、无绑定字段；P2-12 交集，未修改 |
| `VIDEO_PROVIDER_NAME` | `growth.video.provider.name` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_PROTOCOL` | `growth.video.provider.protocol` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_BASE_URL` | `growth.video.provider.base-url` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_API_KEY` | `growth.video.provider.api-key` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_SUBMIT_PATH` | `growth.video.provider.submit-path` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_POLL_PATH` | `growth.video.provider.poll-path` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_CONTENT_PATH` | `growth.video.provider.content-path` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_PROVIDER_TIMEOUT_SECONDS` | `growth.video.provider.timeout-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_TENANT_MAX_CONCURRENT` | `growth.video.tenant-max-concurrent` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_GLOBAL_MAX_CONCURRENT` | `growth.video.global-max-concurrent` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_CONCURRENCY_PERMIT_TTL_SECONDS` | `growth.video.concurrency-permit-ttl-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MAX_POLLS` | `growth.video.max-polls` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MAX_DURATION_SECONDS` | `growth.video.max-duration-seconds`；`growth.video.health.stuck-after-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_HEALTH_PROVIDER_PENDING_THRESHOLD` | `growth.video.health.provider-pending-threshold` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_HEALTH_MEDIA_PENDING_THRESHOLD` | `growth.video.health.media-pending-threshold` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_HEALTH_STUCK_AFTER_SECONDS` | `growth.video.health.stuck-after-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_HEALTH_STUCK_WORKFLOWS_THRESHOLD` | `growth.video.health.stuck-workflows-threshold` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_HEALTH_SNAPSHOT_CACHE_SECONDS` | `growth.video.health.snapshot-cache-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_PRESIGN_TTL_SECONDS` | `growth.video.reference-presign-ttl-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_URL_SAFETY_SECONDS` | `growth.video.reference-url-safety-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MAX_PROVIDER_BYTES` | `growth.video.max-provider-bytes` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_MAX_BYTES` | `growth.video.reference-max-bytes` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_MAX_DURATION_SECONDS` | `growth.video.reference-max-duration-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_MAX_WIDTH` | `growth.video.reference-max-width` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_REFERENCE_MAX_HEIGHT` | `growth.video.reference-max-height` | 有 YAML 引用；读取/绑定路径见上表 |
| `FFPROBE_PATH` | `growth.video.ffprobe-path` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_POLL_SECONDS` | `growth.video.poll-seconds` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MODEL_SEEDANCE_2_5` | `growth.video.model.seedance-2-5` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MODEL_SEEDANCE_2_0` | `growth.video.model.seedance-2-0` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MODEL_SEEDANCE_2_0_MINI` | `growth.video.model.seedance-2-0-mini` | 有 YAML 引用；读取/绑定路径见上表 |
| `VIDEO_MODEL_SEEDANCE_2_0_FAST` | `growth.video.model.seedance-2-0-fast` | 有 YAML 引用；读取/绑定路径见上表 |
| `HTTP_MAX_TOTAL` | `growth.http.max-total` | 有 YAML 引用；读取/绑定路径见上表 |
| `HTTP_MAX_PER_ROUTE` | `growth.http.max-per-route` | 有 YAML 引用；读取/绑定路径见上表 |
| `HTTP_CONNECTION_TIMEOUT` | `growth.http.connection-timeout` | 有 YAML 引用；读取/绑定路径见上表 |
| `HTTP_SOCKET_TIMEOUT` | `growth.http.socket-timeout` | 有 YAML 引用；读取/绑定路径见上表 |

## prod profile 覆盖

| YAML 完整键 | 环境变量引用 | 读取方与结论 |
|---|---|---|
| `spring.config.activate.on-profile` | — | Spring Boot ConfigData |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_EXPOSURE_INCLUDE` | WebEndpointProperties / Actuator 自动配置 |
| `management.endpoint.health.show-details` | — | HealthEndpointProperties / Actuator health |
| `growth.storage.endpoint` | `MINIO_ENDPOINT` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.public-endpoint` | `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.access-key` | `MINIO_ACCESS_KEY` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.secret-key` | `MINIO_SECRET_KEY` | `MinioObjectStorage` / `ProductionConfigurationValidator`；键名一致 |
| `growth.storage.bucket` | `MINIO_BUCKET` | `MinioObjectStorage`；键名一致 |

## 已存在的默认值分散

YAML 与代码/绑定字段的回退默认值有以下不同，键名仍一致，正常加载 YAML 时由 YAML 值覆盖。
它们不会阻断本次环境变量链路，但会让绕过 ConfigData 的测试或手动构造对象得到不同结果。
本任务未统一这些默认值，未修改视频供应商地址、路径、模型或队列默认配置。

| 完整键 | YAML 默认 | 代码/绑定默认 |
|---|---|---|
| `growth.sms.provider` | `console` | ProductionConfigurationValidator 的回退为空 |
| `growth.http.socket-timeout` | `300` | `120` |
| `growth.worker.queues` | `DEFAULT,IMAGE,VIDEO_PROVIDER,MEDIA_CPU` | `DEFAULT` |
| `growth.video.provider.base-url` | `https://api.onlyrouter.ai` | `https://api.onlyrouter.ai/v1` |
| `growth.video.provider.submit-path` | `/v1/videos` | `/videos` |
| `growth.video.provider.poll-path` | `/v1/videos/{jobId}` | `/videos/{jobId}` |
| `growth.video.provider.content-path` | `/v1/videos/{jobId}/content` | `/videos/{jobId}/content` |
| `growth.video.provider.name` | `onlyrouter` | `seedance` |
| `growth.video.model.seedance-2-5` | `doubao-seedance-2-5-260628` | `doubao-seedance-2-5-260128` |
| `growth.video.model.seedance-2-0-mini` | `doubao-seedance-2-0-mini-260615` | `doubao-seedance-2-0-mini-260128` |
| `growth.image.text.model` | `deepseek-flash` | 空 |
| `growth.image.text.temperature` | `0.5` | null |
| `growth.image.text.max-tokens` | `6000` | null |
| `growth.image.text.timeout-seconds` | `300` | Endpoint 的 `60` |
| `growth.image.generator.timeout-seconds` | `600` | Endpoint 的 `60` |
| `growth.image.generator.download-allowed-origins` | `*.aliyuncs.com` | 空列表 |
| `growth.image.timeout-seconds` | `120` | `300`；该顶层字段未被业务读取 |

ImageModelProperties 未建模 tenant/global 并发、API 限流、上传像素等字段，但这些键有单独 @Value
读取，不能因为绑定类里没有字段而判为死配置。两个 creation-lock 配置没有这种独立读取方，
且当前 creation 并发锁用 PostgreSQL 锁，属于 P2-12 的处理范围。
