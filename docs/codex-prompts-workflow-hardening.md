# 生图 / 视频工作流加固 —— Codex 任务提示词

来源：2026-10-05 对 `backend/growth-api` 两条工作流的代码审查。
每条提示词独立可交付，建议按 P0 → P1 → P2 顺序执行，每条完成后单独提交。

## 使用说明

- 每条提示词都是**自包含**的：Codex 不需要读这份审查报告，也不需要读上一条的上下文。
- 提示词里给出的行号是审查时的快照，改动后可能漂移；**以代码内容为准，不要盲信行号**。
- 所有任务都适用仓库约定（见 `CLAUDE.md` 与 `backend/growth-api/README.md`）：
  - `legacy/` 目录禁止参考，其代码风格、接口约定、错误处理与当前约定直接冲突
  - 新功能一律写在 `backend/growth-api/`
  - 租户和用户只从 JWT 取，接口不接受传参
  - 建表/改表必须走 Flyway，命名 `V?__模块.sql`；已合并的迁移不修改
  - 每张业务表必须 `ENABLE`/`FORCE ROW LEVEL SECURITY` 并建 POLICY
  - `TaskHandler` 按至少一次执行设计，外部副作用使用稳定幂等键
  - 响应统一 `ApiResponse.ok(...)`
  - 后端改动必须在 `backend/growth-api` 运行 `mvn verify`（需要 Docker）
- **交付纪律**：不要在没有实际运行验证的情况下声称任务完成。若某项验收标准无法在本地验证，明确说明未验证的部分，不要写成"已完成"。

## 优先级总览

| 编号 | 严重度 | 问题 | 影响 |
|---|---|---|---|
| P0-1 | 阻断 | systemd 缺 `VIDEO_PROVIDER` / `MEDIA_CPU` 队列的 worker | 视频功能在生产完全不可用 |
| P0-2 | 高 | `editText` / `regenerate` 并发许可泄漏且不自愈 | 租户图片额度永久耗尽 |
| P0-3 | 高 | 预签名 URL 1 小时过期 vs 提交请求体冻结 | 重试必失败，用户被误报计费风险 |
| P1-4 | 中 | 限流等待排在 reserve 之后 | 未发出的请求被报成"上游结果未确认" |
| P1-5 | 中 | 视频没有 retry 接口 | 失败即需重建工作流，重复计费 |
| P1-6 | 中 | 视频失败/取消不清理对象存储 | 孤儿对象累积，单文件可达 1GB |
| P1-7 | 中 | 视频无 health / metrics | 静默卡死无法发现 |
| P1-8 | 中 | 不校验实际分辨率与时长 | 请求 1080p 收到 480p 仍标 SUCCEEDED |
| P2-9 | 低 | 导入确定性失败走重试 | 无谓重下 1GB 视频三次 |
| P2-10 | 低 | `CANCELED` / `CANCELLED` 两套拼写 | 前端需处理两种终态字符串 |
| P2-11 | 低 | `@NotBlank prompt` 与 create 校验自相矛盾 | 纯参考图生成被挡死 |
| P2-12 | 低 | 死配置 `IMAGE_FONT` / `creation-lock-*` | 文档误导，用户配置无效 |
| P2-13 | 低 | `tenant_quotas` 四个字段无写入点 | 配额形同虚设且未标注 |
| P2-14 | 低 | 文档版本滞后、视频无设计文档 | 与仓库纪律冲突 |
| P2-15 | 低 | 图片下载路径私网防护弱于视频 | 安全强度不对称 |
| P2-16 | 中 | `VIDEO_POLL_SECONDS` 配置键名错配，永远不生效 | 运维调整轮询间隔无效，且无从察觉 |

---

# P0-1 · 补齐视频工作流的 worker 队列

**严重度：阻断。这是唯一一条"功能完全不可用"的问题，建议最先做。**

## 提示词正文

```
背景：这是一个 Spring Boot 3.5 / Java 21 项目，位于 backend/growth-api/。
它用一张 tasks 表作为 API 进程和 worker 进程之间的唯一协作面：
API 只 INSERT 任务行并立刻返回，worker 进程用 SELECT ... FOR UPDATE SKIP LOCKED 抢任务执行。
同一个 jar 通过 --growth.worker.enabled 和 --growth.worker.queues 参数区分进程角色。

问题：视频工作流的四个阶段分别投递到 VIDEO_PROVIDER 和 MEDIA_CPU 两个队列，
但生产部署单元里没有任何进程监听这两个队列。

证据（请先自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  提交任务投到 "VIDEO_PROVIDER"、轮询任务投到 "VIDEO_PROVIDER"、导入任务投到 "MEDIA_CPU"、
  QA 任务投到 "MEDIA_CPU"（搜索 tasks.submit 的调用点即可看到全部四处）
- deploy/systemd/ 目录下只有三个单元：
  wuyao-api.service（API，worker 关闭）
  wuyao-worker.service（--growth.worker.queues=DEFAULT）
  wuyao-image-worker@.service（--growth.worker.queues=IMAGE）
- backend/growth-api/src/main/resources/application.yml 的 growth.worker.queues 默认值
  包含这两个队列，但 systemd 用命令行参数覆盖了默认值

后果（供你理解影响，不需要在代码里处理）：
任务永远停留在 PENDING；而创建视频时申请的并发许可只在 workflow 进入终态时才释放，
于是一个卡住的任务会永久占用该租户的视频并发额度，之后所有创建请求都返回
"当前有较多视频任务正在生成，请稍后重试"，但实际没有任何任务在执行。

任务：让这两个队列在生产上真正有 worker 消费。

请完成：
1. 新增 systemd 单元，覆盖 VIDEO_PROVIDER 队列。视频轮询是长间隔的短请求，
   并行度按 1-2 配置即可，参考 deploy/systemd/wuyao-image-worker@.service 的写法
   （同样的 User/Group/WorkingDirectory/权限加固/日志与配置路径约定）。
2. 新增 systemd 单元，覆盖 MEDIA_CPU 队列。注意这个队列执行的是下载导入和 ffprobe 媒体探测，
   属于 CPU 与内存密集型（单个视频上限 1GB），并行度和堆内存要给足，
   不要照抄图片 worker 的参数。两个队列可以合并到一个单元，也可以拆开，请你判断并说明理由。
3. 更新 deploy/systemd/README.md 的启动与重启说明，把新单元纳入
   systemctl enable / restart 的命令列表。
4. 检查是否有其它已投递但无人消费的队列。把 application.yml 里
   growth.worker.queues 的默认值与实际部署的队列集合对齐，或明确说明为何保留差异。

约束：
- 不要修改 legacy/ 目录下的任何内容。
- 单元文件里的路径、用户、环境变量引用方式必须与现有三个单元保持一致
  （投产路径是 /home/ubuntu/wuyao-current 符号链接，生产配置在 /etc/wuyao/growth-api.env）。
- 不要改动 tasks 表的结构或 TaskWorker 的领取语义。

验收标准：
1. 新增的单元文件通过 systemd-analyze verify（若环境中不可用，请说明并改为人工核对语法）。
2. deploy/systemd/README.md 中的命令列表与实际单元文件一一对应，没有遗漏也没有多余。
3. 逐条列出：VIDEO_SUBMIT / VIDEO_POLL / VIDEO_IMPORT / VIDEO_QA 四种任务类型
   各自由哪个单元、哪个队列消费。
4. 运行 mvn verify 通过（此改动不涉及 Java 代码，若确实无影响请说明）。

请在完成后报告：新增了哪些文件、每个队列的并行度取值及理由、以及第 4 点的核对结论。
不要声称"视频功能已可用"——你没有在真实环境验证过端到端流程。
```

---

# P0-2 · 修复并发许可泄漏，并让限流计数可自愈

**严重度：高。这是会真实消耗用户额度的问题。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
图片创作和视频生成都用 Redis 上的租户+全局并发许可做资源保护，实现见
backend/growth-api/src/main/java/com/wuyao/growth/common/ratelimit/TenantRateLimiter.java。

问题：有两处申请许可后没有对应的释放路径，异常时计数器只增不减。

证据（请自行打开确认，行号是快照）：
- backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageCreationService.java
  createVersion 方法里 acquirePermit 之后包了 try/catch，异常时调用
  rateLimiter.releaseImageGeneration(tenantId) 兜底——这是正确写法。
  但 editText 方法和 regenerate 方法各自调用 acquirePermit 之后，
  没有任何 try/catch 保护。这两个方法后续会做 saveAndFlush、enqueue、view 等操作，
  任意一处抛异常导致事务回滚时，Redis 里的许可计数已经 INCR 却永远不会 DECR。

  createVersion 和这两个方法的区别值得注意：createVersion 是在
  "先落库再提交任务"这段里失败，而 editText/regenerate 失败点更靠近业务校验和视图组装。

- 同一个文件里还有 releasePermitIfTerminal 方法，它依赖
  ImageCreation.concurrencyPermitHeld 这个数据库字段做幂等，
  通过 clearConcurrencyPermit 的条件更新保证只释放一次。这个设计是对的，请保留。

为什么泄漏不会自愈（这是本条的关键）：
TenantRateLimiter 的 ACQUIRE_SCRIPT 中，每次成功申请都会对租户键和全局键执行 PEXPIRE。
也就是说只要该租户还在创建新任务，TTL 就被不断刷新，泄漏的计数会永久累积，
而不会像设计预期那样在两小时后自动归零。视频侧的 TTL 是 6 小时，同样问题。

任务：让许可申请与释放成对，且不依赖调用方手写 catch。

请完成：
1. 先复现问题：写单元测试，证明 editText 或 regenerate 在后续步骤抛异常后，
   租户的活跃计数没有回落。用 Mockito 模拟 TenantRateLimiter 或 Redis 模板均可，
   参考 backend/growth-api/src/test/java/com/wuyao/growth/creative/image/
   下已有的测试风格。测试要能在修复前失败、修复后通过。
2. 修复泄漏。有两种方向，请你评估后选择一种并说明理由：
   (a) 在 editText 和 regenerate 的 acquirePermit 之后补上与 createVersion 等价的
       try/catch 释放；
   (b) 把释放注册为事务同步回调（TransactionSynchronizationManager），
       仅在事务回滚时释放，这样三个调用点共享同一套逻辑，不容易再次遗漏。
   (b) 更能防止未来新增调用点时重犯，但要注意许可可能在无事务上下文中申请的情况。
3. 重新审视 ACQUIRE_SCRIPT 的 TTL 刷新行为。当前实现让"租户持续活跃"成为
   泄漏永久化的原因。请判断：TTL 应该只设置一次（用 SET NX 之类的方式）还是
   保持现在每次都刷新？给出你的判断和理由。若需要改动，注意不要破坏
   release 脚本中"递减与清理放在同一个脚本里、避免并发 acquire 被 DEL 覆盖"这个已有保证。

约束：
- TenantRateLimiter 位于 common/，按仓库约定属于公共基础，接口变更需要双方审查。
  尽量不改变现有方法的签名；如果必须改签名，请在报告中单独列出并说明影响面。
- 不要修改 legacy/ 目录。
- 不要为了"看起来更干净"而重写现有的 releasePermitIfTerminal / clearConcurrencyPermit 机制，
  它同时承担了幂等释放的职责。

验收标准：
1. 新增的测试在修复前失败、修复后通过——请在报告中给出两次运行的实际输出。
2. 说明修复后"apply 许可数 == 释放许可数"在哪些路径上得到保证，以及仍有哪些残余风险。
3. 运行 mvn verify 通过。

请在完成后报告：选择了哪种修复方向及理由、TTL 行为的判断结论、以及残余风险。

最后请注意：本任务涉及并发计数的正确性，这类 bug 在单次手工测试里往往看不出来。请务必用测试证明修复有效，不要在报告里写"已修复"却只给出代码 diff。
```

---

# P0-3 · 修复预签名 URL 过期导致的重试必失败

**严重度：高。会向用户报告错误的计费风险提示。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
视频工作流把用户的生成请求提交给一个 OpenAI 兼容的视频供应商，流程是
提交 → 轮询 → 下载导入 → QA，每步都是一个独立的异步任务。

问题：提交给供应商的请求体里含有我们对象存储的预签名素材 URL，
这个请求体会被持久化并在重试时复用，但预签名 URL 的有效期只有 1 小时。
两者叠加的结果是：worker 停机超过 1 小时后重启，重试必定失败。

证据（请自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  prepareSubmissionRequest 方法：如果 provider_submit_request 为空就写入候选请求体，
  之后永远返回这个已持久化的请求体。
- 该方法上有注释解释了为什么必须复用：供应商的幂等键只有在请求体逐字节稳定时才安全。
  这个理由成立，**不要改成每次重试都重建请求体**，否则会破坏幂等语义。
- 迁移 backend/growth-api/src/main/resources/db/migration/V17__video_provider_request.sql
  新增了 provider_submit_request 列，注释说明了预签名 URL 会变化所以必须复用首次请求体。
- 请求体里的 URL 来自
  backend/growth-api/src/main/java/com/wuyao/growth/asset/AssetService.java
  的 presignedReference 方法，它签发的有效期是 Duration.ofHours(1)。
- 有效期配置在 application.yml：growth.video.max-duration-seconds 默认 7200（2 小时）。

失败链路：worker 停机 > 1 小时 → 任务租约过期被 reclaimExpired 转回 PENDING →
重试时复用已过期的预签名 URL → 供应商返回 403 → 该 HTTP 状态被判定为不可重试 →
VideoSubmitHandler 直接调用 markFailed。用户看到"供应商提交失败"，
但这个请求实际上从未成功提交给供应商，也不应该被当成失败。

任务：让"重试复用请求体"和"预签名 URL 有效期"不再互相拆台。

请完成：
1. 修正有效期。预签名 URL 的有效期必须覆盖"从提交到所有重试耗尽"的最坏时间窗口，
   即至少不小于 growth.video.max-duration-seconds。请评估是直接提高
   presignedReference 的有效期，还是给它加一个可配置参数由视频侧传入更长的值
   ——注意 AssetService.presignedReference 可能被图片侧复用，
   如果你要改它的行为，先确认所有调用方都不会因此受影响，并在报告中列出调用方。
   请在 application.yml 中把相关配置暴露成环境变量，遵循现有的命名风格
   （growth.video.* 或 growth.storage.*，参考同文件里其它条目的写法）。
2. 修正语义。区分"尚未提交过、可以重建请求体"和"已经提交过、必须复用请求体"两种状态。
   当前代码只看 provider_submit_request 是否为空，无法区分这两种情况。
   建议在 video_workflows 上增加一个明确的标记列（例如记录首次提交是否已发出），
   这样在"未提交"状态下重试可以安全地重建请求体并刷新预签名 URL。
   若你认为有更简单的做法，请说明并论证。
   如果需要改表结构，新建 Flyway 迁移，按命名规范 V?__video_*.sql，
   **不要修改已合并的 V15/V16/V17**。下一个可用版本号请先检查
   backend/growth-api/src/main/resources/db/migration/ 下现有的最大编号并同步主干后确定。
3. 补充边界处理：如果复用的请求体里含有即将过期或已过期的预签名 URL，
   不要静默地把请求发给供应商然后收到 403。要么重建（在允许重建的状态下），
   要么给出明确的、能指导用户下一步操作的错误信息。

约束：
- 不要修改 legacy/ 目录。
- 不要破坏请求体复用带来的幂等保证——这是 V17 存在的理由。
- 租户和用户只从 JWT 取，不要新增接受 tenant/user 传参的接口。
- 视频工作流相关表如果有新增，必须遵守 RLS 约定（见 V15__video_workflow.sql 的写法）。

验收标准：
1. 新增或修改测试覆盖这个场景：模拟"请求体已持久化且其中的预签名 URL 已过期"，
   验证重试时的行为符合第 2 步的设计（在允许重建的状态下重建并成功提交，
   或在不允许重建的状态下给出明确错误而非静默 403）。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/video/VideoProviderGatewayTest.java
   和 VideoSubmitHandlerTest.java 的测试风格。
2. 在报告中给出：预签名有效期的新值与推导过程、状态标记的取值与迁移号、
   以及 AssetService.presignedReference 的调用方清单。
3. 运行 mvn verify 通过。

请在完成后报告上述三点，并明确说明哪些部分你实际运行验证过、哪些只是静态推理。
```

---

# P1-4 · 把限流等待挪到 provider 提交标记之前

**严重度：中。会向用户报告误导性的错误信息。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
图片生成任务在执行时会先做一个"提交意图登记"，再调用供应商接口。
这个登记的目的是：OpenAI 兼容协议不保证幂等，所以必须在发起付费的外部调用前
把意图落库，避免 worker 崩溃后重复提交产生重复费用。

问题：限流等待被放在了提交意图登记之后。限流超时抛出的异常会被后续的
catch 块当成"供应商调用已发出但结果未知"来处理，于是给用户一条与事实不符的提示。

证据（请自行打开确认，行号是快照）：
- backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageRenderHandler.java
  handleInternal 方法内，大约在 77-80 行附近，顺序是：
  先调用 service.reserveProviderSubmission(id, task)，
  再调用 apiRateLimiter.waitForPermission()。
- reserveProviderSubmission 的实现在
  backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageCreationService.java，
  它会把 item 的 providerCode 置为 "SUBMITTING" 作为"已登记提交意图"的标记。
- 同一个 handler 的 catch(RuntimeException) 分支中，当判定为同步提交模式时
  会调用 service.failSynchronousSubmission(...)，其错误文案是
  "上次提交状态尚未确认。请先核对中转站记录，重新生成可能产生新的费用。"
- waitForPermission 位于
  backend/growth-api/src/main/java/com/wuyao/growth/common/ratelimit/ImageApiRateLimiter.java，
  它在一个固定时间窗内自旋等待，超时后抛 IllegalStateException。

后果：当全局限流达到上限、等待超时（默认 10 秒）时，用户被告知
"上次提交状态尚未确认，请核对中转站记录，重新生成可能产生新的费用"，
但实际上请求根本没有发出去，既不需要核对也没有重复计费风险。
这条提示会诱导用户去做无意义的排查，还会让他们不敢重试。

同时，这次尝试已经消耗掉了一次提交机会（providerCode 已被置为 SUBMITTING），
下一次重试会走到"上次提交状态尚未确认"的分支，形成一次性的死路。

任务：让限流等待发生在提交意图登记之前。

请完成：
1. 调整 ImageRenderHandler 中的调用顺序，使 waitForPermission 在
   reserveProviderSubmission 之前执行。确认这样调整后，
   "限流失败"和"供应商调用结果未知"这两种情况在用户看来是可区分的：
   限流失败应该是明确的"请求过于频繁，请稍后重试"，且不留下 providerCode 标记，
   用户可以直接重试而不需要任何人工核对。
2. 检查是否还有其它"等待/阻塞"操作被错误地放在提交意图登记之后。
   特别检查图片和视频两侧的 handler，以及是否有可能在网络调用之后才做本地校验的情况。
   把你的检查范围和结论写进报告。
3. 确认调整顺序不会引入新问题：reserveProviderSubmission 的返回值语义是
   "本次执行是否获得提交权"，如果限流在它之前失败，任务会走普通的失败重试路径，
   请确认这条路径的 backoff 和重试次数是合理的，不会因为限流而快速耗尽 max_attempts
   从而把一个本可成功的任务判死。如果存在这个风险，请在报告中指出并给出建议。

约束：
- 不要移除提交意图登记机制，它是防止重复付费的关键，只调整顺序。
- 不要修改 legacy/ 目录。
- 尽量不改变公开方法签名，避免影响 common/ 与 creative/ 之间已有的契约。

验收标准：
1. 新增测试：模拟限流等待超时，验证 (a) 用户看到的错误信息是限流语义而非
   "上游结果未确认"；(b) item 上没有留下 providerCode="SUBMITTING" 标记；
   (c) 用户可以立即重试而不被"上次提交状态未确认"挡住。
2. 在报告中说明第 2 步的检查范围，以及第 3 步关于重试预算的结论。
3. 运行 mvn verify 通过。

请在完成后报告以上内容，不要声称未验证的部分已经确认。
```

---

# P1-5 · 为视频工作流补充重试接口

**严重度：中。视频单价远高于图片，失败即重建的成本最该避免。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
这个平台有两条 AI 创作工作流：图片创作和视频生成。
图片侧已经有一套成熟的重试能力，视频侧完全没有——这是两者之间最值得补齐的不对称。

图片侧已有的重试能力（请阅读作为参考）：
- backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageCreationService.java
  的 retry 方法：接受 creationId、可选的 itemId 和一个 expectedTaskId，
  通过比对 expectedTaskId 实现乐观并发控制，避免用户在一个已经推进的任务上重复触发重试。
  它会重置失败状态、清理上一次执行留下的中间产物（providerJobId、rawKey、
  providerImageUrl、输出 key 等），然后重新入队。
- 该方法处理的终态包括 FAILED、INTERRUPTED、UPSTREAM_UNKNOWN，
  其中 UPSTREAM_UNKNOWN 是"付费调用可能已经发出但结果未知"，重试前要求用户先核对。
- 对应的接口定义在同一个包的 ImageTaskController 和 ImageCreationController 中。
- 前端消费的视图里会返回 taskId，让客户端能在重试时回传做并发校验。

视频侧的现状（请自行确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  没有对应的 retry 方法。
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoController.java
  只有 capabilities、create、get、cancel、history 五个端点。
- 视频工作流的状态机包含 QUEUED / SUBMITTING / GENERATING / IMPORTING / QA /
  SUCCEEDED / FAILED / CANCELED，失败后无法从任何中间状态恢复。

任务：为视频工作流实现重试能力。

请完成：
1. 设计并实现重试。至少要明确回答以下问题，并把你选择的方案和理由写进报告：
   - 哪些状态允许重试？考虑 FAILED 的各个失败原因差异很大：
     供应商明确拒绝（参数问题，重试无用）、供应商生成失败（可重试）、
     超时（可重试）、导入失败（可重试且不需要重新消耗供应商额度）、
     媒体校验失败（重试可能仍失败）。是否要按 errorCode 区分？
   - 从哪一步重试？如果 provider_job_id 已经存在且供应商任务可能还在跑，
     应该重新提交还是继续轮询原任务？重复提交会不会产生第二次费用？
     这是本条最关键的判断，请重点论证。
   - 是否需要乐观并发控制（对应图片侧的 expectedTaskId）？如果不加，
     两个客户端同时点重试会发生什么？
2. 实现接口。遵循现有约定：
   - 路径风格与 VideoController 现有端点一致（/api/video/workflows/...）
   - 响应包装用 ApiResponse.ok(...)
   - 租户和用户只从 JWT 取，不要新增接受 tenant/user 传参的参数
   - 通过 @AuthenticationPrincipal AuthPrincipal 获取当前用户
3. 处理并发许可。视频的并发许可是通过 video_workflows 上的
   video_concurrency_permit_held 字段管理的，只在 workflow 进入终态时释放。
   重试会把 workflow 从终态拉回进行中，请确保许可语义仍然正确——
   不能出现"已经释放了许可但又回到进行中"导致超额并发的漏洞。
   如果不重新申请许可会有超额风险，请说明你的处理方式。
4. 重试时的中间产物清理策略要明确：上次失败的 provider 任务记录（video_provider_jobs）、
   provider_result_url、已落盘但未通过 QA 的对象，哪些该清、哪些该留。
   注意与 P1-6（视频失败对象清理）的处理保持一致，不要出现两套互相矛盾的清理逻辑。

约束：
- 不要修改 legacy/ 目录。
- 不要新增跳过校验的"强制重试"后门。
- 新增 Flyway 迁移时遵守命名规范与 RLS 约定，先检查现有最大版本号。
- 不要在文档里声明未经验证的完成度。

验收标准：
1. 测试覆盖：允许重试的状态能成功重新入队并推进；不允许重试的状态被正确拒绝；
   并发重试只有一个生效；许可计数在重试前后保持正确。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/video/ 下现有测试风格。
2. 报告中明确回答第 1 步的三个问题，特别是"重复提交是否会产生第二次费用"的论证。
3. 报告中列出第 3 步许可语义的处理方式，以及第 4 步的清理策略与 P1-6 的关系。
4. 运行 mvn verify 通过。

请在完成后报告以上内容。这一条涉及付费外部调用与状态的交互，
如果有任何你无法通过测试覆盖的分支，请明确指出。
```

---

# P1-6 · 视频失败与取消时清理对象存储

**严重度：中。孤儿对象累积，单个视频文件上限 1GB。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/，使用 MinIO 作为对象存储。
视频工作流会把供应商生成的视频下载到自己的对象存储，再创建一条 Asset 记录。

问题：视频工作流在失败或取消时，已经落盘的对象不会被删除，形成孤儿对象。

证据（请自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  的 importUrl 方法把视频写入对象存储，路径形如
  t{tenantId}/generated-video/{workflowId}/output.mp4。
- 同一个类的 completeQa 方法在 ffprobe 媒体校验失败时，
  只把 Asset 的状态改成 INVALID，没有删除对象存储里的文件。
- 同一个类的 cancel 方法直接改状态，也没有清理任何已落盘的对象。
- 单文件大小上限由 growth.video.max-provider-bytes 控制，默认 1073741824（1GB）。

图片侧已有的正确做法（请阅读作为参考）：
backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageCreationService.java
的 cleanupFailedObjects 方法，会在许可释放前遍历子项，
把 FAILED / CANCELLED 状态项对应的 rawKey 和 outputKey 都从对象存储删除，
并且删除失败时只记录警告、不抛出，避免清理动作本身影响主流程。

另外请注意：AssetService 里已经有一个定时清理（cleanupAbandonedUploads），
但它清理的是"用户申请了上传但从未确认"的 PENDING 素材，
与本条讨论的"工作流生成的产物在失败后没被删除"是两个不同问题，不要混淆。

任务：为视频工作流的失败与取消路径补充对象存储清理。

请完成：
1. 确定需要清理的对象集合。至少包括：
   - QA 校验失败时的 output.mp4
   - 导入过程中失败留下的不完整对象（注意 importUrl 的 catch 块里已经有一次
     storage.delete 尝试，请确认它覆盖了所有失败分支，特别是重定向次数超限、
     内容校验失败、流写入中途失败这几种情况）
   - 取消时已落盘但尚未成为可用作品的对象
   在报告中列出完整的清理清单，以及每一项对应代码里的哪个位置。
2. 实现清理，并遵循图片侧已验证的原则：
   - 清理失败不要抛出，只记录警告并保留对象键，避免"清理动作本身导致主流程失败"
   - 不要删除已经成功交付给用户的作品对象。特别是：如果视频已经通过 QA
     并且用户可能已经看过或下载过，即使后续状态变化也不应删除。
     请确认你的实现不会触发这种情况。
3. 考虑清理的边界：Asset 记录与对象存储不一致时怎么办？
   孤儿对象（有对象无记录）和悬空记录（有记录无对象）哪种该处理、哪种该保留？
   在报告中说明你的取舍。
4. 评估是否需要一次性清理历史遗留的孤儿对象。
   如果需要，不要写进 Flyway 迁移（迁移只应改结构，不应做数据清理这种有副作用的操作），
   而是提供一个可手动执行的脚本或管理端点，并在报告中说明如何安全地识别孤儿对象
   （提示：只删除没有任何 Asset 记录引用、且对应 workflow 已处于终态的对象）。

约束：
- 不要修改 legacy/ 目录。
- 不要删除成功状态的作品对象。
- 不要用定时任务扫描全桶的方式做常规清理——按 workflow 状态触发更安全，
  误删已交付作品是不可接受的。
- 清理逻辑要能在 tenant 上下文中正确执行（对象键本身就含 tenantId 前缀）。

验收标准：
1. 测试覆盖：QA 失败后对象被删除；取消后已落盘对象被删除；
   成功的作品对象不被删除；清理抛异常时主流程仍能正常结束。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/asset/AssetCleanupTest.java
   和 backend/growth-api/src/test/java/com/wuyao/growth/video/VideoWorkflowServiceImportTest.java
   的风格（项目中用 Mockito 模拟 ObjectStorage）。
2. 报告中给出第 1 步的完整清理清单和第 3 步的取舍说明。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。特别注意说明：你的实现是否可能误删已成功交付的作品。

请如实区分"用测试验证过"和"通过阅读代码推断"的部分。对象删除是不可逆操作，如果你对某个分支的删除行为没有测试覆盖，请明确列为未验证，不要含糊带过。
```

---

# P1-7 · 为视频工作流补充健康检查与指标

**严重度：中。没有观测手段，静默卡死无法发现。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/，使用 Micrometer 与 Actuator。
平台有图片和视频两条工作流，图片侧有一套完整的观测能力，视频侧完全没有。
本任务是把图片侧已验证的模式搬到视频侧。

图片侧已有的观测能力（请阅读作为参考）：
- backend/growth-api/src/main/java/com/wuyao/growth/common/health/ImageHealthIndicator.java
  实现 HealthIndicator，检查 IMAGE 队列的 PENDING / RUNNING 数量，
  积压超过阈值时返回 down，同时检查图片模型是否已配置。
- backend/growth-api/src/main/java/com/wuyao/growth/common/metrics/ImageMetrics.java
  注册 Micrometer Gauge 监控队列深度，并记录生成耗时、下载耗时等指标，
  用 Timer/Counter 按 outcome 维度打标签。
- 队列计数来自 backend/growth-api/src/main/java/com/wuyao/growth/common/task/TaskRepository.java
  的 countByStatusAndQueue 方法。

视频侧的现状：没有任何 health indicator 或 metrics，搜索 VideoHealth / VideoMetrics 为空。

为什么这条重要（供理解影响）：
视频任务涉及长轮询（默认每 15 秒一次，最多 360 轮）和 1GB 级文件下载。
当队列没有 worker 消费、或者供应商长时间无响应时，任务会静默停在中间状态，
用户界面上只是"生成中"，运维侧没有任何信号。
另外视频创建时会申请并发许可，许可只在终态释放——
如果任务卡住不推进，许可会被永久占用，而这一点在指标上目前完全不可见。

任务：为视频工作流建立与图片侧对等的观测能力。

请完成：
1. Health indicator。至少包含：
   - VIDEO_PROVIDER 和 MEDIA_CPU 两个队列的 PENDING / RUNNING 数量
   - 视频供应商是否已配置（参考图片侧对 gateway.configured 的检查方式，
     视频侧的配置判断在 VideoProviderGateway.configured 方法）
   - 一个能反映"任务卡在非终态过久"的信号。这是图片侧没有的、而视频侧特别需要的：
     可以统计处于 GENERATING / IMPORTING / QA 状态超过某个时长仍未推进的 workflow 数量。
     请注意这个查询要能利用现有索引——
     V15__video_workflow.sql 里建了 idx_video_workflow_status (status, created_at DESC)。
     如果你需要的查询用不上现有索引，请在报告中指出，并评估是否需要新增索引。
   - 积压或卡死的阈值要可通过配置调整，遵循现有的环境变量命名风格。
2. Metrics。至少包含：
   - 两个队列的深度 Gauge
   - 视频生成各阶段的耗时（提交、轮询总时长、导入、QA），按 outcome 打标签
   - 轮询轮次分布（视频轮询次数直接关系到供应商侧的成本与超时判断）
   - 并发许可的使用情况（当前占用的许可数，对照配置上限）
   参考 ImageMetrics 的构造与注册方式，保持命名风格一致（前缀用 video. 而不是 image.）。
3. 报告现有的 ImageMetrics 实现中任何你发现的问题，
   如果这些问题同样适用于视频侧，在视频侧实现时避免重犯，但**不要顺手重构图片侧**——
   那属于另一个改动范围，请在报告中单独提出建议。

约束：
- 不要修改 legacy/ 目录。
- 不要把队列深度等指标做成阻塞式的同步查询放在请求路径上——
  参考图片侧的 Gauge 按需拉取方式。
- health indicator 的查询要能容忍数据库短暂不可用，返回 down 而不是抛异常传播
  （参考 ImageHealthIndicator 的 try/catch 处理）。
- 指标命名用 video. 前缀，与图片侧的 image. 前缀对应。

验收标准：
1. 报告中列出新增的 health 检查项、每个指标的完整名称与标签维度。
2. 说明"卡死检测"依赖的查询与索引的关系，以及阈值配置项的名称与默认值。
3. 运行 mvn verify 通过。
4. 如果环境中可以启动应用，实际访问 /actuator/health 确认新增检查项的返回结构；
   如果不能，请在报告中明确说明未做运行时验证。

请在完成后报告以上内容，不要声称未验证的部分已经确认。
```

---

# P1-8 · 校验视频实际分辨率与时长

**严重度：中。用户请求 1080p 却收到 480p，系统仍标记成功。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
视频工作流在 QA 阶段会用 ffprobe 探测生成视频的真实属性，
但目前探测到的信息只是写进数据库，不与用户的请求做任何比对。

问题：用户请求的分辨率和时长与实际得到的不一致时，系统仍标记任务成功。

证据（请自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  的 completeQa 方法：调用 videoProbe.probe(...) 拿到元数据后，
  把 width/height/durationMs/mimeType/sizeBytes 写入 Asset 并置 READY，
  然后把 workflow 置为 SUCCEEDED、progress 置为 100。
  全程没有拿这些值与 workflow 上记录的 resolution 和 durationSeconds 做比较。
- workflow 请求的分辨率和时长在创建时就已持久化：
  VideoWorkflow 实体有 resolution 和 durationSeconds 字段，
  取值在创建时由请求校验（见 VideoCapabilities.validate）。
- 对外返回的视图在
  backend/growth-api/src/main/java/com/wuyao/growth/video/VideoDtos.java
  的 View 记录里，包含请求的 resolution 和 durationSeconds，
  但**不包含实际生成的宽高和时长**，前端无法得知真实结果。
- 模型能力表 VideoCapabilities 里，各个模型支持的时长和分辨率上限已经定义好。

任务：让实际生成结果得到校验，并让用户能看到真实值。

请完成：
1. 校验实际值与请求值。请仔细设计判定规则，并说明理由：
   - 分辨率：供应商可能返回接近但不完全相同的尺寸（例如 1080p 实际输出 1088x1920）。
     完全相等的要求会误判。请定义容差，并区分"横向/纵向"是否与请求的 ratio 一致。
   - 时长：视频供应商通常允许一定误差（例如请求 5 秒得到 5.2 秒或 4.8 秒）。
     请定义容差。同时注意 durationSeconds 是整数秒，而 ffprobe 返回毫秒。
   - 抽样帧率、编码格式等是否需要校验？说明你的取舍。
2. 决定不一致时的行为。这是一个需要判断的设计点，请在报告中论证：
   (a) 视为失败（用户没拿到他要的东西，不应该被计费为成功）
   (b) 视为成功但附带警告，让用户自己决定是否接受
   (c) 按偏差程度分级处理
   注意：视频是付费生成，判为失败意味着用户可能要求重新生成，
   而重新生成会再次产生供应商费用。请权衡这一点。
   无论选择哪种，都要保证不会出现"标记成功但用户拿到的不是他要的东西且毫不知情"。
3. 在对外视图里暴露实际的宽高和时长，让前端能展示并提示差异。
   遵循 VideoDtos 现有 record 的写法。注意修改 record 会影响前端契约——
   请检查前端代码里消费视频工作流视图的位置（在仓库根的 src/ 目录下，
   有一个 composable 处理数字人视频流程），确认新增字段不会破坏现有解析，
   并在报告中说明前端需要配合的改动（如果有）。
4. 如果第 2 步选择了"视为失败"，需要处理与 P1-6 的交互：
   失败的视频对象应该被清理，但清理前提是用户确实没有拿到可用产物。
   在报告中说明两者的边界。

约束：
- 不要修改 legacy/ 目录。
- 不要绕过 ffprobe 校验——它存在的意义就是不信任供应商返回的元数据。
- 前端改动保持最小，不要重构前端结构；请求统一走 src/utils/request.js 的约定不变。

验收标准：
1. 测试覆盖：分辨率在容差内通过；分辨率明显不符按设计的行为处理；
   时长在容差内通过；时长明显不符按设计的行为处理；
   ffprobe 失败时仍按现有的 INVALID 路径处理（这条已有实现，确认未被破坏）。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/video/ 下的测试风格。
2. 报告中给出：容差取值与推导、不一致时的行为选择与论证、
   新增的视图字段、以及前端需要配合的改动清单。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。

容差取值和"不一致时如何处置"都涉及产品判断，你的报告需要让人工能据此做决策，而不只是告知你选了哪个值。同时请明确哪些结论有测试支撑、哪些只是推理。
```

---

# P2-9 · 让视频导入的确定性失败不再重试

**严重度：低。浪费带宽与时间，重下 1GB 视频三次。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
任务框架对 handler 抛出的异常默认按至少一次语义重试，最多 max_attempts 次（默认 3），
每次退避时间指数增长。对于确定性的、重试也不会成功的失败，
handler 需要抛出 NonRetryableTaskException 来跳过重试。

问题：视频导入 handler 对确定性失败没有使用不可重试语义。

证据（请自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoImportHandler.java
  的 catch 块只做了一件事：如果已经到达最大尝试次数就标记 workflow 失败，
  然后原样重新抛出异常。它从不抛 NonRetryableTaskException。
- 对比 backend/growth-api/src/main/java/com/wuyao/growth/video/VideoSubmitHandler.java
  和 VideoPollHandler.java：这两个都判断了 ProviderHttpException 的 retryable()，
  对不可重试的 HTTP 状态（非 408/429/5xx）标记失败并抛 NonRetryableTaskException。
- 真正的下载逻辑在
  backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  的 importUrl 方法和 downloadResponse 方法，它们会抛出这些确定性错误：
  供应商视频 URL 为空、重定向次数过多、下载响应缺少重定向地址、
  下载 HTTP 4xx（非超时类）、下载响应为空、视频文件超过大小限制、
  供应商返回的内容不是有效视频（内容类型与文件头校验失败）。
- 其中"供应商返回的内容不是有效视频"和"文件超过大小限制"意味着
  重新下载一次会得到完全相同的结果，但当前会重新下载最多三次，
  而单文件上限是 growth.video.max-provider-bytes 默认 1GB。
- NonRetryableTaskException 的定义在
  backend/growth-api/src/main/java/com/wuyao/growth/common/task/NonRetryableTaskException.java。

任务：区分视频导入中的可重试与不可重试失败。

请完成：
1. 梳理 importUrl 及其调用的 downloadResponse 抛出的全部异常，
   逐个判断哪些是确定性的（重试无意义）、哪些是暂时性的（重试有意义）。
   判断时请考虑：网络抖动导致的中断是可重试的，但内容校验失败不是；
   供应商返回 5xx 是可重试的，返回 404/410（链接已失效）则取决于
   供应商的结果 URL 是否有获取期限——请查看 video_provider_jobs 表是否有
   result_expires_at 字段以及它是否被写入，据此判断。
   把完整的分类表写进报告。
2. 实现分类。推荐的做法是引入一个明确的异常类型或错误标记来区分这两类，
   而不是在 handler 里用字符串匹配异常消息。请选择实现方式并说明理由。
   注意：标记 workflow 失败与抛出异常是两件事，现有代码在到达最大尝试次数时
   标记失败——请确保不可重试路径也能正确地把 workflow 置为 FAILED，
   不要让 workflow 卡在非终态（这一点很重要，因为许可只在终态释放）。
3. 检查视频两侧其它 handler（提交、轮询、QA）是否有同类遗漏。
   QA handler 目前完全不处理异常——如果 ffprobe 因为环境问题（可执行文件缺失）
   而失败，当前会重试三次然后失败，请判断这是否合理。

约束：
- 不要修改 legacy/ 目录。
- 不要移除现有"到达最大尝试次数时标记失败"的兜底逻辑，
  它覆盖的是可重试失败的最终失败，与不可重试路径互补。
- 不要改变任务框架本身的重试语义，只在 handler 层面做分类。

验收标准：
1. 测试覆盖：内容校验失败时 handler 抛 NonRetryableTaskException
   且 workflow 被标记为 FAILED；网络类暂时性失败时 handler 抛普通异常
   且 workflow 保持非终态等待重试。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/video/VideoWorkflowServiceImportTest.java。
2. 报告中给出第 1 步的完整分类表和第 3 步的检查结论。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。

请在报告中区分：哪些分类是靠代码阅读和测试确认的，哪些是因为拿不到供应商的实际错误响应而做了推测。后者请标注出来，不要写成确定结论。
```

---

# P2-10 · 统一视频工作流的取消状态拼写

**严重度：低。前端需要处理两种终态字符串。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
项目里存在两种"已取消"的英文拼写：CANCELLED（双 L）和 CANCELED（单 L）。
两者在不同模块中并存，前端不得不分别处理。

现状（请自行确认）：
- 使用 CANCELLED 的位置：
  backend/growth-api/src/main/java/com/wuyao/growth/common/task/TaskStatus.java
  （任务框架的枚举），以及图片工作流
  backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ 包下的服务与控制器。
- 使用 CANCELED 的位置：
  backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  （设置 workflow 状态），以及
  backend/growth-api/src/main/java/com/wuyao/growth/video/VideoProviderGateway.java
  的 normalizeStatus 方法（把供应商返回的状态归一化成内部值）。
- 前端已经同时适配了两种：仓库根目录 src/composables/useDigitalHumanVideo.js
  判断视频状态用的是 CANCELED，而 src/domain/creative.js 和 src/domain/imageCreation.js
  用的是 CANCELLED。

重要前提，请务必注意：
仓库根目录 src/domain/commerce.js 和 src/domain/billing.js 里的 CANCELED
是订阅与订单状态，遵循的是另一套业界惯例（Stripe 风格），
**不要动它们**。本任务的范围仅限视频工作流。同时也不要把图片工作流改成 CANCELED。

任务：把视频工作流的状态拼写统一到 CANCELLED，与任务框架和图片工作流一致。

请完成：
1. 修改视频侧的拼写。范围包括 VideoWorkflowService 中所有设置和比较该状态的位置，
   以及 VideoProviderGateway.normalizeStatus 的返回值与调用方对它的比较。
   注意 normalizeStatus 的返回值会写入 VideoProviderJob.status 和
   VideoWorkflow.providerStatus 两个字段，请一并核对。
2. 处理存量数据。video_workflows.status 存的是字符串（VARCHAR(32)），
   历史数据里可能存在 CANCELED 值。请新建一个 Flyway 数据迁移把它们更新为 CANCELLED。
   遵守命名规范，先检查 backend/growth-api/src/main/resources/db/migration/
   下现有的最大版本号。注意：迁移只做数据订正，不要顺手加索引或改字段类型。
3. 更新前端。把 src/composables/useDigitalHumanVideo.js 里的 CANCELED
   改为 CANCELLED。如果前端还有其它地方消费视频状态，一并更新。
   注意前端其它模块的 CANCELED 不要改动。
4. 在报告中列出你修改的每一处位置，并明确说明哪些 CANCELED 是你**故意保留**的
   （commerce/billing 域）以及原因。

约束：
- 不要修改 legacy/ 目录。
- 不要修改 common/ 或图片工作流的 CANCELLED 拼写。
- 不要动 src/domain/commerce.js 和 src/domain/billing.js。
- 前端改动完成后需要能通过 npm run lint 和 npm run typecheck。

验收标准：
1. 报告中给出完整的修改清单与保留清单。
2. 后端运行 mvn verify 通过；前端运行 npm test && npm run lint && npm run typecheck 通过。
3. 新增或更新测试，确认视频取消后返回的状态是 CANCELLED。

请在完成后报告以上内容。

改完后请说明你实际运行了哪些检查（后端 mvn verify、前端 lint/typecheck 等），以及是否有任何一项你没能跑起来。没跑的就写没跑。
```

---

# P2-11 · 修复视频请求 DTO 与业务校验的矛盾

**严重度：低。一个已实现的能力被 DTO 层挡死。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
视频生成支持三种模式：纯文本生成、图片生视频、视频生视频。
业务逻辑允许"只上传参考图、不写提示词"这种用法，但 DTO 层把它挡住了。

问题：DTO 的校验注解与业务方法的校验逻辑互相矛盾，导致后者的分支永远进不去。

证据（请自行打开确认）：
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoDtos.java
  的 Create 记录里，prompt 字段标注了 @NotBlank。
  这意味着请求体在进入控制器之前就会被校验拒绝，无法到达业务层。
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  的 create 方法里，有一条校验：
  当 prompt 为空、且没有参考图、且没有参考视频时才抛错，
  错误信息是"请至少描述需求或添加一个参考素材"。
  这条逻辑表达的正确意图是"三者至少有一个"，但由于 @NotBlank 的存在，
  prompt 为空的情况永远到不了这里。
- 对比图片侧的设计意图：
  backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageCreationService.java
  的 createVersion 方法里有一条明确允许二选一的校验
  （需求文字为空但提供了参考图是合法的）。视频侧本来应该是同样的语义。
- 另外注意 VideoProviderGateway.buildSubmitRequest 方法已经处理了
  prompt 为空时的请求体构造（它会根据是否有参考图决定 mode 和 content 的组织方式），
  说明下游本来就是按"prompt 可以为空"设计的。

任务：让 DTO 校验与业务语义一致，允许纯参考图生成。

请完成：
1. 放宽 prompt 的校验注解。改成非空但允许空字符串，或者改用其它方式表达
   "长度上限"这类真正需要约束的规则。
   请先确认 prompt 在数据库里的列定义（VideoWorkflow 的 request 是 JSONB，
   实际长度约束可能在应用层），据此设定合理的长度上限。
2. 确认放宽后业务层的"三者至少有一个"校验能真正生效并给出用户能理解的提示。
   请检查这个错误信息是否明确告诉用户该做什么。
3. 检查放宽校验带来的下游影响：
   - 供应商请求体构造是否会因为 prompt 为空而出错？
     （buildSubmitRequest 里对 prompt 为空的处理路径，请确认它确实是可达且正确的）
   - 是否存在其它地方假设了 prompt 非空？例如日志、错误信息拼接、
     或前端展示。请全局搜索 VideoDtos.Create 的使用点。
   - 前端是否有对应的限制？仓库根目录 src/ 下的数字人视频相关代码
     可能在提交前就要求填写提示词，如果有，请在报告中指出需要配合的改动。
4. 顺带检查这个 DTO 里其它校验注解是否存在类似的"过严导致分支不可达"问题。
   referenceImageAssetIds 的 @Size(max = 6) 是否与供应商能力一致？
   这个上限的依据是什么？如果找不到依据，请在报告中提出而非擅自修改。

约束：
- 不要修改 legacy/ 目录。
- 不要放宽到"什么都不填也能提交"——至少要有一个输入来源。
- 供应商能力上限必须有依据才能改动，不要凭猜测调整 @Size 之类的约束。

验收标准：
1. 测试覆盖：只有参考图、无提示词的请求能通过校验并进入业务层；
   三者都为空时被拒绝且错误信息明确。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/video/ 下的测试风格。
2. 报告中给出第 3 步的完整影响面检查结果和第 4 步的结论。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。

放宽校验属于降低防御，请说明你为什么确信这不会引入"空请求打到供应商"这类问题——用测试证明，而不是用推理宣称。
```

---

# P2-12 · 清理无效配置项与相应文档

**严重度：低。用户按文档配置了不生效的参数。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
配置文件中存在一些已经不再被代码读取的配置项，文档还在引导用户去设置它们。

问题：死配置 + 误导性文档。

证据（请自行验证，不要只信这份描述）：
- backend/growth-api/src/main/resources/application.yml 的 growth.image 下有：
  - font: ${IMAGE_FONT:Microsoft YaHei}
    代码中没有任何地方读取 growth.image.font。图片生成早已改成
    让模型直接输出成品图（包含文字排版），不再由服务端叠加文字层，
    因此字体配置失去意义。请在 creative/image/ 包下搜索确认这一点。
  - creation-lock-wait-seconds 和 creation-lock-lease-seconds
    同样没有任何读取点，请搜索确认。
- docs/image-workflow.md 的配置示例中列出了 IMAGE_FONT，
  会让使用者以为自己可以控制生成图片的字体。
- 作为对比，application.yml 里其它配置项都有明确的读取方，
  例如 growth.image.poll-seconds 被 ImageCreationService 读取。

任务：消除死配置与文档误导。

请完成：
1. 先独立核实上面列的每一项是否确实是死配置。
   搜索范围包括 Java 源码、测试代码、前端代码、部署脚本与 systemd 单元。
   如果某项其实有读取方（例如通过 @ConfigurationProperties 绑定、
   或在前端使用），请指出并保留，不要误删。
   把你核实的方法与结果写进报告。
2. 对确认无用的配置项，选择处理方式并说明理由：
   (a) 从 application.yml 删除，并同步删除 .env.example 里的对应条目
   (b) 保留但加注释标明"已废弃，不再生效"
   倾向于 (a)——保留无效配置只会让下一个人再次困惑。
   但如果有外部部署脚本引用了这些环境变量名，删除会导致启动报错，
   请先检查 deploy/ 目录和 backend/growth-api/.env.example。
3. 修正 docs/image-workflow.md 中相关的说明。
   同时注意：该文档开头写着"本次新增迁移为 V6__image_creation.sql，保留已有 V1–V5"，
   而实际迁移已经到 V17。请把它改成不依赖具体版本号的表述，
   或更新为当前实际状态（先核实 db/migration/ 下的最大编号）。
4. 全面扫一遍 application.yml，找出其它"定义了但无读取方"的配置项，
   列在报告里。不要顺手删除需要业务判断的项（例如故意保留给未来使用的），
   只报告并由人工决定。

约束：
- 不要修改 legacy/ 目录。
- 删除配置前必须确认没有外部引用，避免生产环境启动失败。
- docs/ 下的其它文档本次不要改动（视频文档的补充是另一个任务）。
- 这一条不涉及 Java 逻辑改动，如果你发现某处代码确实需要这些配置，
  说明你的发现而不是强行删除。

验收标准：
1. 报告中给出：核实方法、逐项的删除/保留决定与理由、外部引用检查结果、
   以及第 4 步的全量扫描结果。
2. 确认删除后应用仍能正常启动（若本地可启动则实际验证；
   否则说明通过静态检查确认无引用，并明确指出未做运行时验证）。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。
```

---

# P2-13 · 明确租户配额的落地状态

**严重度：低。配额字段形同虚设且未按仓库纪律标注。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
数据库里有一张租户配额表 tenant_quotas，但其中大部分字段从未被写入或校验。

问题：配额字段看起来像已实现的功能，实际只有一个字段在生效。

证据（请自行核实）：
- 迁移 backend/growth-api/src/main/resources/db/migration/V10__tenant_quotas.sql
  定义了这些业务字段：storage_bytes_used、storage_bytes_limit、
  image_count、image_count_limit、concurrent_limit，
  以及 V12__tenant_quota_defaults.sql 可能补充的默认值，请一并查看。
- backend/growth-api/src/main/java/com/wuyao/growth/common/quota/TenantQuota.java
  是对应的实体。
- 全局搜索这些字段的读写点可以确认：
  - concurrent_limit 被
    backend/growth-api/src/main/java/com/wuyao/growth/common/ratelimit/TenantRateLimiter.java
    读取（用于图片并发上限），这是唯一生效的字段。
  - storage_bytes_used / storage_bytes_limit / image_count / image_count_limit
    没有任何写入点，也没有任何校验点。也就是说存储配额和图片数量配额
    既不累加也不拦截。
- 仓库约定（见根目录 CLAUDE.md）明确要求：
  "不写静态假数据冒充真实业务数据；未接通的能力必须显式标注"。
  当前这几个字段既未接通，也没有标注，属于违反约定的状态。

任务：让配额的落地状态变得明确。你需要先评估，再选择处理方向。

请完成：
1. 全面核实每个字段的读写情况，给出确切的搜索结果（哪些文件、哪些行）。
2. 评估实现完整配额校验的代价，并给出建议：
   - 存储配额：需要在哪些路径上累加和校验？
     至少包括素材上传确认、图片生成落盘、视频导入落盘，以及删除时的回退。
     注意并发场景：多个任务同时落盘时如何避免超额。
   - 图片数量配额：在创建时校验，还是完成时累加？
     考虑失败任务是否应该计数（从用户视角，失败的生成不应该消耗配额，
     但从成本视角，供应商已经收费了）。这个取舍请给出你的判断。
   - 需要在哪些表上加字段或索引？是否需要新迁移？
3. 根据评估结论，选择以下之一并在报告中论证：
   (a) 实现这两个配额（如果代价可控，且你能给出完整方案）
   (b) 只做显式标注：在代码、配置和文档中明确说明这两个配额尚未强制执行，
       并说明当前行为（不限制）。这符合仓库纪律的最低要求。
   倾向于 (b) 作为本次交付，因为 (a) 涉及多条写入路径和并发正确性，
   风险和工作量都超出"清理"的范围。但如果你评估后认为 (a) 可以在
   可控范围内完成，可以提出方案供人工决策，不要直接实现。
4. 无论选哪种，都要确保不会出现"配额表存在、用户以为有限制、
   实际无限使用"这种静默状态。如果选择标注，
   请在 backend/growth-api/README.md 的配额相关章节和
   TenantQuota 实体上加清晰的说明。

约束：
- 不要修改 legacy/ 目录。
- 不要为了让字段"看起来被使用"而写没有实际效果的读写代码。
- 不要新增用户可见的配额查询接口，除非配额真的生效——
  返回一个假的用量数字比不返回更糟。
- 这一条很可能以文档与注释为主，不要为了显得有产出而扩大改动范围。

验收标准：
1. 报告中给出第 1 步的完整搜索结果和第 2 步的代价评估。
2. 明确说明本次选择了哪个方向以及理由。
3. 若只做标注，确认标注位置能让后续开发者立刻发现这个缺口。
4. 如涉及代码改动，运行 mvn verify 通过。

请在完成后报告以上内容。

这条任务的产出可能以文档和注释为主，这没有问题。请抵抗"为了让改动看起来有分量而扩大范围"的冲动，如实报告你评估后认为不该做的事。
```

---

# P2-14 · 补齐视频工作流设计文档并修正过期文档

**严重度：低。与仓库纪律直接冲突。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
仓库根目录的 CLAUDE.md 里有一条明确纪律：
"不要在文档里声明未经运行验证的完成度。这个仓库以前吃过大亏：
两个后端各写了一份「BUILD SUCCESS / 所有阶段完成」的报告，
而其中一个从第一个提交起就编译不过。"

现状问题一：文档描述的是旧版本。
- docs/image-workflow.md 的正文里写着
  "本次新增迁移为 V6__image_creation.sql，保留已有 V1–V5"，
  但实际 db/migration/ 下已经有 V1 到 V17 共十七个迁移文件。
  文档还描述了图片服务端叠加文字层的行为（配置里有 IMAGE_FONT），
  而现在的实现是让模型直接输出带文字的成品图，
  提示词里甚至明确禁止"留空背景等待叠字"。

现状问题二：视频工作流没有设计文档。
- backend/growth-api/src/main/java/com/wuyao/growth/video/ 下有 14 个文件、
  约 1400 行代码，包含一个四阶段状态机和四个独立的异步任务 handler，
  但 docs/ 下没有任何关于它的设计说明。
- docs/adr/ 下只有三篇决策记录（组织联邦、开放平台授权、模板与 Agent 自主性），
  都与视频无关。
- 对比：图片工作流有 docs/image-workflow.md 记录了工作流选择、模型配置、
  迁移版本和启动方式。

任务：让文档与代码的实际状态一致。

请完成：
1. 修正 docs/image-workflow.md 中已过期的内容：
   - 移除或改写依赖具体迁移版本号的表述（不要改成"本次新增 V17"，
     这种写法必然再次过期；改成描述性的、不绑定版本号的表述）
   - 核实并更新对当前实现行为的描述。特别是文字生成的方式：
     当前是模型直接产出带文字的成品图，请阅读
     backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageRenderHandler.java
     里的提示词构造逻辑，准确描述实际行为，不要照抄旧描述
   - 配置示例部分与 application.yml 的实际内容对齐
     （注意：有一个独立任务在处理死配置项 IMAGE_FONT 和 creation-lock-*，
     你这里只需保证文档不引用无效配置，不需要处理代码侧）
2. 新增视频工作流的设计文档，建议路径 docs/video-workflow.md，
   风格与详略程度向 docs/image-workflow.md 看齐。至少覆盖：
   - 四阶段状态机：提交 → 轮询 → 导入 → QA，
     每阶段对应哪个 handler、投递到哪个队列（注意：队列配置可能存在
     部署缺口，请如实描述代码中的投递目标，不要假设它已被正确消费）
   - 供应商协议边界：当前只实现了 CHAT_COMPLETIONS 一种协议，
     VideoProviderGateway 里有明确判断；支持的模型与能力上限
     （见 VideoCapabilities）
   - 幂等设计：request_key 与 request_hash 的用途、
     provider_submit_request 为什么要持久化复用
     （见 V17 迁移的注释和 prepareSubmissionRequest 方法）
   - 并发许可：video_concurrency_permit_held 字段的作用，
     以及它只在终态释放这一特性带来的运维含义
   - 配置项清单：来自 application.yml 的 growth.video.* 全部条目
   - 本地启动方式：参考 backend/growth-api/README.md 中
     按队列启动 worker 的说明，如实写出视频需要的队列
3. 严格遵守仓库纪律：文档只描述**已经存在于代码中的行为**。
   对于你知道存在缺陷或未验证的部分（例如队列可能无人消费、
   重试能力缺失、配额未生效），要么不提，要么明确标注为
   "当前实现如此"或"尚未实现"，绝不要写成"已完成"或"已支持"。
   宁可不写，也不要写不准确的完成度声明。
4. 如果视频工作流存在值得记录的架构决策，
   评估是否需要新增一篇 docs/adr/ 决策记录。请在报告中给出判断，
   不要为了凑数而写。若要写，先阅读 docs/adr/ 下现有三篇的格式并保持一致。

约束：
- 不要修改 legacy/ 目录。
- 不要修改任何 Java 代码或前端代码——这一条只改文档。
- 不要声明未经验证的完成度，这是本任务的核心要求。
- 文档用中文撰写，与现有 docs/ 下文档的语言一致。

验收标准：
1. docs/image-workflow.md 中不再出现会立即过期的版本号表述，
   配置示例与实际配置一致。
2. 新增的视频文档覆盖第 2 步列出的全部要点，
   每一项都能在代码中找到对应依据。
3. 报告中列出：你核实过的每一项结论对应的代码位置，
   以及你在文档中明确标注为"未实现/未验证"的内容清单。
4. 报告中说明第 4 步关于 ADR 的判断。

请在完成后报告以上内容。注意：本任务不涉及代码改动，
所以不需要运行 mvn verify，但也不要因此跳过对代码的核实阅读。
```

---

# P2-15 · 对齐图片下载路径的网络防护强度

**严重度：低。安全强度在两条工作流之间不对称。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
两条工作流都需要从外部 URL 下载供应商产出的文件到自己的对象存储，
两边各有一套 URL 安全校验，但强度不一致。

现状对比（请自行打开确认）：
- 视频侧的实现更严格：backend/growth-api/src/main/java/com/wuyao/growth/video/VideoUrlSecurity.java
  的 rejectPrivateHost 方法不仅做字符串前缀检查（localhost、127.、10.、192.168.、
  169.254.、fc/fd/fe80 等），还会对主机名做 DNS 解析，
  然后逐个检查解析出的 IP 地址是否为任意本地地址、回环、链路本地、
  站点本地、组播地址。这能防住"解析到私网 IP 的域名"这种绕过方式。
- 图片侧的实现较弱：
  backend/growth-api/src/main/java/com/wuyao/growth/common/gateway/ImageDownloadOrigins.java
  的 allows 方法只做白名单匹配（配置项 growth.image.generator.download-allowed-origins，
  默认 *.aliyuncs.com），通配符只匹配 HTTPS 子域。
  没有 DNS 解析后的私网段校验。
  它是被 backend/growth-api/src/main/java/com/wuyao/growth/creative/image/ImageDownloadHandler.java
  在下载前调用的。
- 相关测试：backend/growth-api/src/test/java/com/wuyao/growth/common/gateway/ImageDownloadOriginsTest.java
  和 backend/growth-api/src/test/java/com/wuyao/growth/video/VideoUrlSecurityTest.java。

为什么这值得处理：
图片侧的防护完全依赖白名单配置正确。默认值 *.aliyuncs.com 是安全的，
但如果运维把 download-allowed-origins 配置成过宽的值（例如为了适配新的中转站
而写成 *.* 或某个可被控制的域名），就会失去保护。
视频侧的 DNS 解析校验提供了第二道防线，不依赖配置正确性。

另外还有一处硬编码：
- ImageDownloadHandler.java 里有一个 isOnlyRouterFilesContent 方法，
  判断主机名是否等于 api.onlyrouter.ai 且路径匹配 /v1/files/{id}/content，
  匹配时会给请求带上 Bearer 令牌。这个主机名是写死在 Java 代码里的。
- 对比视频侧的供应商地址是可配置的（application.yml 的
  growth.video.provider.base-url），换中转站不需要改代码。
  图片侧换中转站则需要改代码并重新构建，这既是运维负担，
  也容易在改动时引入错误。

任务：让图片下载路径获得与视频侧对等的网络防护，并移除硬编码的供应商主机名。

请完成：
1. 把 DNS 解析后的私网地址校验引入图片下载路径。
   有两种实现方向，请评估后选择并说明理由：
   (a) 复用视频侧的 VideoUrlSecurity——但它在 video 包中且是包级可见的
       final 类，直接复用会破坏模块边界（仓库约定要求模块间通过公开 Service/DTO 交互）
   (b) 把这段校验提取到 common/ 下作为共享工具，两侧都调用它
   倾向于 (b)，因为这是一段与业务无关的安全校验逻辑，
   且能避免未来两边再次分化。但这属于 common/ 的改动，
   按仓库约定属于公共基础，请在报告中明确说明影响面。
   注意：提取后两个原有的测试都必须继续通过，测试本身可以调整导入路径。
2. 处理硬编码的供应商主机名。
   把 isOnlyRouterFilesContent 中的 api.onlyrouter.ai 改为可配置项，
   遵循 application.yml 中现有的命名风格。
   请判断是否需要同时把路径正则也做成可配置的，并说明理由——
   如果只配置主机名而路径写死，未来协议变化时仍要改代码。
3. 检查这次改动是否影响现有的下载行为：
   - 图片侧允许重定向，重定向后是否会再次做私网校验？请确认。
   - 默认配置 *.aliyuncs.com 在加入 DNS 校验后是否仍能正常工作？
     注意有些 CDN 域名可能解析到多个地址，请确认校验逻辑不会误杀。
     （视频侧的做法是"任一解析结果为私网就拒绝"，这是保守策略，
     图片侧沿用同样策略的话，误杀风险如何？请给出判断。）
4. 报告中评估图片与视频两侧是否还有其它安全校验不对称的地方，
   列出但不要擅自修改超出本任务范围的项。

约束：
- 不要修改 legacy/ 目录。
- 不要降低视频侧现有的校验强度。
- 改动 common/ 下的代码属于公共基础变更，接口设计需要考虑两侧调用方，
  请在报告中说明如何避免破坏现有调用。
- 不要在日志或错误信息中泄露完整的供应商 URL（现有代码有守住这一点，
  请保持）。

验收标准：
1. 测试覆盖：图片下载地址解析到私网 IP 时被拒绝；
   正常白名单域名仍可通过；重定向到私网地址被拒绝；
   硬编码主机名改为配置后，配置生效且默认值保持现有行为。
   参考 backend/growth-api/src/test/java/com/wuyao/growth/common/gateway/ImageDownloadOriginsTest.java
   的测试风格，以及视频侧 VideoUrlSecurityTest 对私网地址的用例设计。
2. 报告中给出第 1 步的方向选择与理由、第 3 步的兼容性确认、第 4 步的不对称清单。
3. 运行 mvn verify 通过。

请在完成后报告以上内容。

安全改动的误杀风险和后门风险都要评估。请明确说明哪些场景你实际写了测试覆盖、哪些只是预期行为，不要声称未经验证的安全性。
```

---

# P2-16 · 修复视频轮询间隔的配置键名错配

**严重度：中。配置项存在、文档有说明、用户会去改，但永远不生效。**

## 提示词正文

```
背景：Spring Boot 3.5 / Java 21 项目，backend/growth-api/。
视频工作流在轮询阶段会按固定间隔查询供应商的任务状态，
这个间隔本应可以通过环境变量调整，但实际配置键名不匹配，调整无效。

问题：配置定义的位置和代码读取的位置不是同一个键。
由于两侧的默认值恰好都是 15 秒，问题被完全掩盖，运维不会察觉。

证据（请自行打开确认，请特别注意 YAML 的缩进层级）：
- backend/growth-api/src/main/resources/application.yml 中，
  在 growth.video 下有：
      poll-seconds: ${VIDEO_POLL_SECONDS:15}
  但它是与 provider、model 同级的条目，
  即完整键名是 growth.video.poll-seconds，
  而不是 growth.video.provider.poll-seconds。
  请用带缩进的查看方式确认层级，不要只看行号。
- backend/growth-api/src/main/java/com/wuyao/growth/video/VideoWorkflowService.java
  中读取的是 @Value("${growth.video.provider.poll-seconds:15}")，
  即 growth.video.provider.poll-seconds。
- 两者键名不同，因此 yml 中的定义（以及 VIDEO_POLL_SECONDS 环境变量）
  从来不会被读取；代码只会拿到自己 @Value 里的默认值 15。
- backend/growth-api/.env.example 中也存在 VIDEO_POLL_SECONDS=15，
  同样不生效。
- 对比正确的例子：同文件里 growth.image.poll-seconds 被
  ImageModelProperties（@ConfigurationProperties(prefix="growth.image")）正确绑定，
  并由 ImageCreationService 读取使用。

影响：
视频轮询间隔直接关系到供应商侧的请求频率与任务完成的响应速度。
运维若为了降低供应商压力而调大这个值，或在调试时调小它，都不会有任何效果。
更麻烦的是没有任何报错或警告——配置看起来被接受了，实际被忽略。

任务：让这个配置项真正生效。

请完成：
1. 判断应该修正哪一侧，并说明理由。两个方向：
   (a) 改代码的 @Value 键名，让它指向 growth.video.poll-seconds
   (b) 改 yml 的缩进，把 poll-seconds 移到 provider 下面
   请考虑两种键名的语义合理性：poll 间隔描述的是"我们多久查一次供应商"，
   与 provider 的协议配置（base-url、path、timeout）不是同一类信息。
   同时检查这个配置项还有没有其它同类兄弟（例如 max-polls、
   max-duration-seconds）的层级是否一致，避免只修一半。
2. 修正后确认 VIDEO_POLL_SECONDS 环境变量链路真正打通：
   yml 的 ${VIDEO_POLL_SECONDS:...} → 配置键 → @Value 注入。
   请实际验证注入的生效路径，而不是只改字符串后声明完成。
3. 全面排查同类问题。检查 application.yml 中所有配置项是否都能被代码读到。
   重点检查：
   - 每个 @Value 或 @ConfigurationProperties 引用的键，在 yml 中是否存在且层级正确
   - 是否存在定义了但缩进层级错误的条目（这类错误不会有启动报错，
     因为 Spring 允许 yml 里存在未被使用的键）
   - .env.example 中列出的每个环境变量，是否都真的被 yml 引用
   把完整的排查结果写进报告。注意：有一个独立任务（P2-12）在处理
   "定义了但完全无读取方"的死配置，你这里关注的是
   "键名层级错配导致读不到"的情况，两者角度不同但可能指向同一些条目，
   在报告中注明交集即可，不要越界处理。
4. 评估是否需要加一层防护，让这类静默错配在启动时暴露。
   例如：对关键配置项在启动时打印实际生效值，
   或使用 @ConfigurationProperties 绑定替代散落的 @Value
   （类型安全的绑定会在键名不匹配时表现为字段为 null 或默认值，
   配合构造器校验可以提前发现）。
   请在报告中提出建议，但**不要在本任务中做大规模重构**——
   在报告中给出方案供人工决策，实施范围仅限本任务确认的错配项。

约束：
- 不要修改 legacy/ 目录。
- 不要把修复做成"在代码里同时读两个键"这种兼容性妥协，
  那会让下一个读代码的人更困惑。选一个正确的键名并统一。
- 涉及视频生成的其它配置不要顺手改动默认值——
  本任务只修键名错配，不改配置语义。
- .env.example 中的说明要与实际生效的键名一致。

验收标准：
1. 新增或修改测试，验证配置项被正确注入。
   如果项目中有针对配置绑定的测试可以参考
   backend/growth-api/src/test/java/com/wuyao/growth/DotEnvConfigTest.java
   和 backend/growth-api/src/test/java/com/wuyao/growth/common/config/
   下的测试风格。
2. 报告中给出：第 1 步的方向选择与理由、第 2 步的实际验证方式与结果、
   第 3 步的完整排查结果、第 4 步的建议。
3. 明确说明你实际设置了环境变量验证生效，还是仅做了静态确认。
   如果你能实际启动应用并观测生效值，请给出观测结果；
   如果不能，请明确标注未做运行时验证。
4. 运行 mvn verify 通过。

请在完成后报告以上内容。
```

---

## 执行顺序建议

各条提示词之间存在这些依赖关系，建议按此顺序执行以减少返工：

- **P0-1 最先做**。它不涉及 Java 代码，风险最低；
  更重要的是，其余涉及视频的改动（P1-5、P1-6、P1-7、P2-9）都需要一个
  能真正跑起来的视频 worker 才能在本地端到端验证。
- **P0-2、P0-3 可并行**。两者改动面独立，一个在限流器与图片服务，
  一个在视频提交链路。
- **P1-5（重试）与 P1-6（清理）需要先对齐设计再动手**。
  重试会把 workflow 从终态拉回进行中，而清理只在终态触发，
  两者的边界（什么算"用户已拿到的产物"）必须一致。
  建议先做 P1-6 建立清理原则，再做 P1-5。
- **P1-8（实际值校验）若选择"不一致判失败"**，会与 P1-6 的清理路径交互，
  建议在 P1-6 之后。
- **P2-10（拼写统一）建议在 P1-8 之后**。两者都会改动视图契约与前端，
  分开做可以避免同一批前端改动混在一起。
- **P2-9（不可重试分类）建议在 P1-5 之后**。重试能力的实现会明确
  哪些失败值得重试，这直接影响 P2-9 的分类表。
- **P2-12、P2-13、P2-14 是文档与配置清理**，可与任何一条并行。
  P2-12 和 P2-14 都涉及 `docs/image-workflow.md`，
  如果并行执行请注意避免同时修改同一文件产生冲突。
- **P2-11、P2-15 相互独立**，随时可做。

## 通用提醒

有两条提示词（P0-3、P1-5）会新增 Flyway 迁移。按仓库约定，
新建版本前需先同步主干并登记编号，已合并的迁移不修改。
当前 `db/migration/` 下最大编号是 V17，但执行时请以实际状态为准。

`common/task`、`common/ratelimit`、`common/quota` 属于公共基础。
按其约定，`common/` 和 `iam/` 的接口变更需要双方审查。
涉及这些目录的提示词（P0-2、P1-4、P2-9、P2-15）在提交时应走这条审查约定。

每条提示词都刻意要求 Codex 在报告中区分"实际运行验证过的"与"静态推理的"部分。
这是对仓库纪律的落实——本仓库此前出现过未经验证的完成度声明，不要重蹈。
