# 视频反推接入与运行

更新日期：2026-10-07。

视频反推采用双模型架构：画面使用 OnlyRouter 的 `gpt-6-luna`，声音使用百炼工作空间的 `qwen3.8-omni-flash`。后端用 FFmpeg 提取带近似时间戳的图片和 WAV 音轨，先生成独立声音报告，再把声音报告与抽样画面交给画面模型生成最终拆解结果。前端使用真实任务状态展示两个阶段的进度、结果和历史。

## 配置

在 `backend/growth-api/.env` 配置以下变量。文件使用 Java properties 语法，值不加引号；该文件已被 Git 忽略。API Key 只供服务端读取，不放入前端的 `VITE_*` 变量。

```properties
VIDEO_ANALYSIS_BASE_URL=https://api.onlyrouter.ai/v1
VIDEO_ANALYSIS_MODEL=gpt-6-luna
VIDEO_ANALYSIS_API_KEY=填写画面模型密钥
VIDEO_ANALYSIS_AUDIO_BASE_URL=https://<workspace-id>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
VIDEO_ANALYSIS_AUDIO_MODEL=qwen3.8-omni-flash
VIDEO_ANALYSIS_AUDIO_API_KEY=填写音频模型密钥
VIDEO_ANALYSIS_AUDIO_INPUT_ENCODING=DATA_URL
FFMPEG_PATH=ffmpeg
FFPROBE_PATH=ffprobe
```

音频模型需要支持 `input_audio` 输入。`qwen3-tts-vd-2026-01-26` 是语音生成模型，不能用于音轨理解；本机已验证同一百炼工作空间的 `qwen3.8-omni-flash` 可接收 WAV Data URL 并返回文字声音报告。

抽帧 worker 需要能够运行 `ffmpeg -version` 和 `ffprobe -version`；也可以配置两个程序的绝对路径。应用启动后自动执行 V44（分析记录）和 V45（音轨与声音报告）数据库迁移，迁移账户需要有建表权限，应用账户继续使用受租户隔离约束的 `growth_app`。视频反推分支的迁移在合入主干前由 V23、V24 顺延为 V44、V45，避免与主干已发布的应用角色口令和门店迁移重复编号。

主干已执行到 V43 的数据库可以按顺序升级。曾在旧视频反推分支执行原 V23、V24 的开发数据库，其 Flyway 历史与主干版本号含义不同，升级前需要备份并单独核对迁移历史；合并验证使用独立测试数据库。

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `VIDEO_ANALYSIS_MAX_BYTES` | `104857600` | 最大源文件大小，100 MiB；界面显示为 100 MB |
| `VIDEO_ANALYSIS_MAX_DURATION_SECONDS` | `60` | 最大视频时长 |
| `VIDEO_ANALYSIS_FPS` | `2` | 正常情况下每秒采样数 |
| `VIDEO_ANALYSIS_MAX_FRAMES` | `48` | 单次分析图片上限；较长视频自动降低采样频率 |
| `VIDEO_ANALYSIS_FRAME_LONG_EDGE` | `1280` | 图片最长边上限，较小画面保持原尺寸 |
| `VIDEO_ANALYSIS_TIMEOUT_SECONDS` | `180` | 单次画面模型响应等待上限 |
| `VIDEO_ANALYSIS_MAX_TOKENS` | `6000` | 画面模型输出 token 上限，包含推理 token |
| `VIDEO_ANALYSIS_AUDIO_INPUT_ENCODING` | `DATA_URL` | 音频输入编码；支持 `DATA_URL` 或纯 `BASE64` |
| `VIDEO_ANALYSIS_AUDIO_TIMEOUT_SECONDS` | `120` | 单次音频模型响应等待上限 |
| `VIDEO_ANALYSIS_AUDIO_MAX_TOKENS` | `4000` | 音频模型输出 token 上限 |

默认使用按时间均匀抽帧，尚未实现镜头切换检测。12 秒视频通常得到 24 帧，60 秒视频最多得到 48 帧。增大抽帧数量可能改善短暂动作的覆盖，也会增加图片输入成本。

个别 Windows Java 环境无法建立 NIO Selector/Pipe 的本地连接，会同时影响 Tomcat 和 Lettuce。遇到 `Unable to establish loopback connection` 时，可在本地 `.env` 配置：

```properties
SERVER_PROTOCOL=org.apache.coyote.http11.Http11Nio2Protocol
REDIS_CLIENT_TYPE=jedis
```

没有设置 `SERVER_PROTOCOL` 时保留 Tomcat 默认协议；Redis 默认仍为 Lettuce。本机已配置上述兼容选项。

## 启动

需要 Java 21、Maven 3.9、FFmpeg，以及 PostgreSQL、Redis、MinIO。以下后端命令在 `backend/growth-api` 目录执行：

```powershell
docker compose up -d
mvn package
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=false
```

在另一个终端启动负责抽帧和分析的 worker：

```powershell
java -jar target/growth-api-0.1.0.jar --server.port=8091 --growth.worker.enabled=true --growth.worker.queues=MEDIA_CPU,DEFAULT --growth.worker.parallelism=2
```

`MEDIA_CPU` 执行下载、校验和抽帧，`DEFAULT` 执行模型分析。两条队列都需要有人处理；只启动 API 会让任务停留在等待状态。开发时也可以只运行一个同时启用这两条队列的 API 进程。

在项目根目录启动前端：

```powershell
npm run dev
```

登录后打开 `/video/analyze`。开发前端默认使用 4173 端口，`/api` 代理到本地 8080 端口。部署时需要正确配置对象存储的公开访问地址与跨域策略，确保浏览器可访问签名上传和预览 URL。

## 接口与任务

所有接口复用平台 JWT 认证、统一响应信封与数据库租户隔离。

| 方法 | 路径 | 功能 |
| --- | --- | --- |
| GET | `/api/video/analyses/limits` | 上传限制和模型是否配置 |
| POST | `/api/video/analyses/upload-url` | 按文件名、MIME、大小申请直传地址 |
| POST | `/api/video/analyses` | 用 `assetId`、`requestKey`、`mode`、`reverseNeed` 创建分析 |
| POST | `/api/video/analyses/import-url` | 用公开 HTTPS 视频文件直链创建分析 |
| GET | `/api/video/analyses/{id}` | 状态、结果、原视频和抽帧预览 |
| GET | `/api/video/analyses?page=0&size=20` | 当前租户的分页历史，`size` 最大为 50 |
| PUT | `/api/video/analyses/{id}/name` | 用 `name` 重命名历史记录 |

本地上传流程为：申请签名地址 → 浏览器直接 PUT 到对象存储 → 创建分析 → 轮询详情。签名上传不携带平台 JWT。`mode` 为 `ai` 或 `real`；`reverseNeed` 是可选的关注点，最多 2000 字符。同一次提交须复用 `requestKey`，相同内容返回已有分析；同一编号用于不同内容则返回冲突。

有可分析音轨时，任务状态依次为 `QUEUED → EXTRACTING → ANALYZING_AUDIO → ANALYZING → SUCCEEDED`；无音轨或音频模型未配置时跳过 `ANALYZING_AUDIO`。媒体校验或画面分析失败进入 `FAILED`，音频失败则继续完成画面分析并标明原因。服务端检查真实文件大小、实际容器、可解码画面和真实时长，模型结果经过字段和时间线校验；worker 写回前核对执行租约，防止过期任务覆盖新状态。

链接导入只接受公开 HTTPS 文件地址和默认 443 端口，校验解析后的 IP 并禁止访问内网。下载不携带平台认证信息，也不跟随重定向。抖音、小红书等平台分享页面需要先下载视频再上传。

## 结果与边界

结果包含英文整体生成提示词、负面提示词建议、中文复刻脚本、画面参数、分镜、关键帧提示词、复用亮点、改进建议和分析限制。前端支持复制、导出 JSON 报告、关键帧定位，以及将提示词带入视频工作台；带入提示词后仍需用户主动提交生成。

现在有音轨且音频模型配置完整时，先分析口播、配乐、音效和环境声，再由画面模型合并声画结果；`audioAnalyzed` 只有在声音报告通过校验时才为 `true`。声音区域展示人声、配乐、环境声、带时间的口播转录和声音事件，支持复制口播及点击时间定位视频。没有音轨、未配置音频模型或声音调用失败时，任务仍会完成画面分析，并明确显示原因；诊断日志仅记录错误类型、错误码或 HTTP 状态，不记录密钥、音轨内容或上游响应体。

字幕可以分别来自可见画面和音频转录，不会把建议台词冒充原口播。雷达分数表示模型对画面观察充分程度的主观估计，实拍模式展示实际时长、源尺寸、抽帧数量和估计分镜数量。

提示词用于复现相似画面，不能保证还原原作者的提示词或完整动作。短暂动作、快速转场和采样间的细节可能遗漏；分镜边界和运镜属于根据抽样画面的估计。

原视频和展示帧保留在对象存储以支持历史回看，详情接口签发 30 分钟预览地址。worker 会清理自己创建的临时文件，并尝试删除未成功提交的帧。当前模块没有历史删除、保留期限、按租户分析并发配额或分析扣费逻辑，生产运行前应按产品要求补充这些策略。

2026-10-07 本机双模型完整联调：12 秒视频得到 24 帧，百炼 `qwen3.8-omni-flash` 正确转录两句英文测试口播，OnlyRouter `gpt-6-luna` 识别出白色背景上的红色方块在约第 6 秒变为蓝色，并在最终报告中结合声音证据。返回的 `audioAnalyzed=true`、`audio.status=ANALYZED` 和两个模型标识均通过验证；从创建分析到返回结果约 28.7 秒。这是单条合成测试视频的结果，实际速度和费用取决于素材、网络及两家供应商的响应与结算规则。

双模型改造通过 388 项后端测试，最后补充诊断日志后复验 22 项相关测试；84 项前端测试、类型检查、ESLint 错误检查及生产构建均通过。声音区域的浏览器实测覆盖报告显示、口播复制、时间跳转、JSON 导出、两种展示模式和手机布局。原画面链路已实测上传、关键帧定位、历史重命名、提示词带入工作台和公开 HTTPS 直链导入，伪视频与 61 秒视频在模型调用前被拒绝；临时测试账户、任务及对象存储媒体已清理。

后端相关测试覆盖音频模型独立配置、WAV 提取、结构化声音报告、模型异常降级、过期任务隔离和声画合并。当前真实音频验收样本为清晰英语口播及静音，没有验证复杂配乐、多语言重叠人声或嘈杂环境中的识别质量；声音时间戳由模型估计。
