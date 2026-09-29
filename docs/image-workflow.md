# AI 图片创作工作流

版本：1.4 · 更新：2026-09-29

营销海报与产品套图共用“Claude 需求理解与提示词精修 → 图片生成”的技术流程，但不共用创作指令。
营销海报使用 `poster-claude-director-v1`：聚焦本地商家的传播主题、图文层级、投放场景和历次版式差异；
产品套图使用 `product-set-claude-director-v1`：聚焦商品身份保真、镜头分工、材质表现、使用场景和整组一致性，默认不叠加海报标题或促销装饰。
两者只共享图片身份保真、尺寸校验、参考图角色和安全限制等基础规则。
Claude 一次调用同时输出结构化方案和每张图的最终视觉指令并保留中文事实，
图片模型按工作流的成片要求生成，服务端校验尺寸并保存作品，不再叠加固定文字框。
未配置规划或图片模型时拒绝提交；精修失败时使用原始方案，不回退到 Echo。

## 本地启动

需要 Java 21、Maven、Docker。先按照后端 README 初始化 PostgreSQL、MinIO 和 JWT。
本次新增迁移为 `V6__image_creation.sql`，保留已有 V1–V5。

在 `backend/growth-api/.env` 配置中转站令牌，重启 API 和 worker。
默认采用 OpenAI 兼容协议，文本走 Chat Completions，无参考图走 Images Generations，
带参考图走 Images Edits。保留 `BRIDGE` 模式供原异步桥接服务使用。

```properties
NEW_API_BASE_URL=https://myrouter.online/v1
NEW_API_API_KEY=replace-on-server
IMAGE_PLANNER_ALIAS=TEXT_REFINER
IMAGE_REFINER_ENABLED=true
IMAGE_REFINER_MODEL=claude-sonnet-4-6-ab
IMAGE_REFINER_MAX_TOKENS=3000
IMAGE_REFINER_TIMEOUT_SECONDS=300
IMAGE_GENERATOR_MODEL=gpt-image-2.5-ultra-fast
IMAGE_GENERATOR_PROTOCOL=OPENAI
IMAGE_GENERATOR_URL=https://new.fluapi.com/v1/images/generations
IMAGE_GENERATOR_QUALITY=standard
IMAGE_GENERATOR_RESPONSE_FORMAT=url
IMAGE_GENERATOR_TIMEOUT_SECONDS=900
IMAGE_TIMEOUT_SECONDS=300
IMAGE_SUPPORTED_QUALITIES=1K,2K,4K
IMAGE_MAX_OUTPUT_PIXELS=0
IMAGE_FONT=Microsoft YaHei
```

`NEW_API_BASE_URL` 可填写域名根地址或以 `/v1` 结尾的地址，不会重复拼接 `/v1`。
`IMAGE_PLANNER_URL`、`IMAGE_GENERATOR_URL`、`IMAGE_GENERATOR_EDITS_URL` 可覆盖完整接口地址。
共享令牌通过 `NEW_API_API_KEY` 配置；若设置独立的 `IMAGE_PLANNER_API_KEY` 或
`IMAGE_GENERATOR_API_KEY`，它们优先于共享令牌，不要把独立令牌显式设为空。

`IMAGE_SUPPORTED_QUALITIES` 只填写实际支持的档位。`IMAGE_MAX_OUTPUT_PIXELS` 可按中转站实测能力限制可选输出尺寸；0 表示不额外限制。带参考图时，规划模型必须支持图像输入。
当前生产图片模型为 `gpt-image-2.5-ultra-fast`，使用 Flu 的 OpenAI 兼容
`/v1/images/generations` 接口，并请求 `response_format=url`。中转站返回的临时图片 URL
仍由服务端下载、转 PNG 并保存到对象存储；前端不接触供应商令牌。参考图编辑能力需以
中转站对 `/v1/images/edits` 的实际支持为准。

```powershell
cd backend/growth-api
mvn package
java -jar target/growth-api-0.1.0.jar --server.port=8080
# 在另一个终端启动 worker：
java -jar target/growth-api-0.1.0.jar --growth.worker.enabled=true --server.port=8090
# 仓库根目录另开终端：
npm run dev
```

海报文字由图片模型生成，服务器字体不再参与叠字。
当前没有自动 OCR 校验，发布前仍需检查文案准确性。

容器部署可分别配置 `MINIO_ENDPOINT`（API 可达地址）和 `MINIO_PUBLIC_ENDPOINT`
（浏览器可达地址），两者必须指向同一桶服务。可通过 `MINIO_REGION` 指定签名区域。
对象存储需要允许前端来源的 PUT/GET 跨域请求。

当前本地开发容器 `wuyao-growth-api-local` 已挂载后端 `.env` 和中文字体，
并启用 DEFAULT、IMAGE 任务队列。填写 `NEW_API_API_KEY` 后执行
`docker restart wuyao-growth-api-local` 即可重新加载令牌；前端刷新后重新检查能力。
该容器使用已经打包的 JAR，后端代码修改后需要先重新构建。

## 模型接入位置

- `common/gateway/ImageModelProperties.java`：服务端配置。
- `common/gateway/ImageModelHttpAdapter.java`：目前的 HTTP 传输与协议适配。
- `creative/image/ImagePlanner.java`：结构化提示词、版本与方案校验。
- `creative/image/ImagePromptRefiner.java`：按 POSTER / PRODUCT_SET 分流英文提示词精修、负面提示与失败降级。
- `creative/image/ImageRenderHandler.java`：按工作流分流最终生图指令，提交、查单、保存与渲染。
- 新供应商可以替换 HTTP 适配器，保持能力别名与业务服务不变。
  当前每种能力只应启用一个真实适配器，未实现动态供应商选型。

### 规划层

请求携带 `Authorization: Bearer ...` 和稳定的 `Idempotency-Key`，
JSON 包含 `model`、`messages`、`response_format: {type: "json_object"}`。
用户消息包含需求 JSON、参考图片的角色说明与 `image_url` 数据 URL。
商品主体与风格参考分别使用 `SUBJECT` 和 `STYLE`；也支持只提供版式的 `LAYOUT` 和只提供环境的 `BACKGROUND`。

读取响应 `choices[0].message.content`，内容必须为 JSON 字符串：

```json
{
  "summary": "使用清爽的夏日色彩，突出商品主体。",
  "question": "",
  "visualDirection": "Visual concept: 清爽夏日新品；Focal point: 商品包装与饮品质感；Hierarchy: subject first, message second, environment third；Palette: pale green, warm cream, dark brown accents；Lighting: soft diffused daylight from upper left；Material language: ceramic, paper and natural liquid highlights；Camera language: 50mm eye-level product photography；Composition language: asymmetric rule of thirds with controlled negative space；Typography direction: crisp editorial Chinese typography；Brand guardrails: preserve package geometry, logo and colors.",
  "items": [
    {
      "role": "商品主图",
      "shotType": "hero",
      "focalPoint": "商品包装与饮品质感",
      "materialLanguage": "陶瓷、纸张和自然液体高光",
      "cameraLanguage": "50mm eye-level product photography",
      "mustPreserve": ["包装外形", "Logo", "品牌色"],
      "mustAvoid": ["额外配料", "虚构标签", "随机文字"],
      "prompt": "保留参考图商品包装与外形，建立完整可发布的商业成片。",
      "headline": "",
      "caption": ""
    }
  ]
}
```

有关键歧义时返回一个 `question`，`items` 必须为空，系统进入等待补充状态。
正常方案的图片数量必须与请求一致。商家补充信息后生成关联的新版本。

规划提示词会先确定视觉概念、第一视觉焦点、主体/文字/背景层级、色板、光线、材质、镜头、构图和品牌边界，
再为每张图输出 `shotType`、`focalPoint`、`materialLanguage`、`cameraLanguage`、`mustPreserve` 和 `mustAvoid`。
规划提示词禁止虚构价格、日期、配方、功效或门店事实。
后端额外检查输出文字中的数字是否出现在用户输入中；
该校验不等于完整的语义事实审核。商品视觉一致性仍取决于所选模型与真实样张验收。

### 图片层：桥接协议

此节仅适用于显式设置 `IMAGE_GENERATOR_PROTOCOL=BRIDGE` 的异步供应商。
默认的 `OPENAI` 模式见下一节。

这是本项目的适配契约，并非所有图片供应商的通用接口。
接入特定供应商时，在服务端桥接服务或 `ProviderAdapter` 中转换字段、
处理其上传要求和原生任务查询协议。桥接层必须支持按幂等键恢复原请求。

提交请求：

```json
{
  "model": "your-image-model",
  "operation": "submit",
  "idempotencyKey": "image-item-123-0",
  "seriesKey": "creation-12",
  "prompt": "整组统一设计……本张画面……",
  "width": 1080,
  "height": 1440,
  "quality": "2K",
  "references": [
    { "role": "SUBJECT", "dataUrl": "data:image/png;base64,..." }
  ]
}
```

异步接单返回：

```json
{ "status": "RUNNING", "jobId": "provider-job-123" }
```

后续使用同一个地址 POST，`operation=query`，携带 `jobId` 与原幂等键。
轮询不重新提交参考图。状态允许 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`。

成功返回：

```json
{
  "status": "SUCCEEDED",
  "jobId": "provider-job-123",
  "imageBase64": "纯 PNG 或 JPG 文件的 Base64，不带 data: 前缀",
  "usage": { "providerCost": 0.1, "currency": "CNY" }
}
```

同步图片服务可以在提交时直接返回成功结构。上游也可以返回 `imageUrl`，此时
`IMAGE_RENDER` 保存临时 URL 并投递 `IMAGE_DOWNLOAD` 到 `DEFAULT` 队列，然后立即完成生成任务。
下载任务最多重试三次，将图片转为 PNG 存入对象存储，并将图片项的 `persisted` 置为 `true`。
前端先展示临时 URL，持久化完成后通过轮询切换为对象存储预签名 URL。

配置 `IMAGE_DOWNLOAD_ALLOWED_ORIGINS` 为允许的图片 URL 来源。当前允许 HTTPS 下的
`*.aliyuncs.com` 子域名；自定义 CDN 域名需另行加入其精确来源。OnlyRouter Files API 的
`/v1/files/{file_id}/content` 需要服务端携带 API Key 请求；它返回 `302` 和临时签名链接，
不能把该接口地址直接交给浏览器。跳转后的签名地址不携带 API Key，默认 1 小时有效。
Files API 上传响应只有文件 ID，不含下载 URL；文件默认保留 7 天。
当前接入的图片生成接口已确认返回 URL；其他兼容接口返回 Base64 时仍走原有路径。

成本字段只记录供应商信息，不执行用户扣款。

必须按原幂等键返回同一个任务或结果，包括“已接单但连接超时”的情况。
不具备该能力的供应商不能宣称支持安全自动重试，需先补充查单与提交对账。
当前不会在请求结果未知时切换供应商。

### 图片层：OpenAI 兼容协议

- 无参考图：`POST /v1/images/generations`，发送 JSON。
- 带参考图：`POST /v1/images/edits`，发送 multipart，单图字段为 `image`，多图为 `image[]`。
- 请求包含 `model`、`prompt`、`n=1`、`size=宽x高`；套图按单张任务生成。
- 参考图按原顺序传递，主体与风格的用途说明写入提示词。
- `data[0].b64_json` 走同步保存路径；`data[0].url` 立即作为生成结果返回，
  由 `IMAGE_DOWNLOAD` 后台任务下载和保存。
- URL 来源必须列入 `IMAGE_DOWNLOAD_ALLOWED_ORIGINS`，支持精确来源或
  `*.aliyuncs.com` 这样的 HTTPS 子域名规则。
  普通签名 URL 下载不携带模型令牌且不跟随跳转；OnlyRouter Files API 的
  `/content` 地址仅跟随一次 `302`，且不会向签名地址转发 API Key。

`IMAGE_GENERATOR_QUALITY` 是模型的 `auto/low/medium/high` 等质量参数，
与界面中的 1K、2K、4K 输出分辨率不同。默认不发送 `response_format`、
`input_fidelity` 等可选参数；只有当前图片接口明确要求时才配置
`IMAGE_GENERATOR_RESPONSE_FORMAT`。返回格式以实际接口响应为准。

OpenAI 同步图片接口没有通用的任务查询地址，也不能因发送 `Idempotency-Key` 就认定
中转站会去重。系统在调用前持久化提交标记；请求超时或响应解析失败后，不会自动再次付费生图。
worker 崩溃后若底图已经保存，则从底图恢复；只有提交标记而没有底图时，显示结果待确认。
这种情况使用 `UPSTREAM_UNKNOWN` 状态，前端不会把它显示为确定失败，worker 也不会自动再次提交。
用户核对中转站记录后，显式重试才会创建新的生成轮次。
核对中转站记录后可主动重试，新重试使用新的生成轮次。保存失败仍可恢复已保存的模型图像。

## 画质与排版

1K、2K 按短边定义；4K 使用模型支持的 16:9 或 9:16 原生尺寸。
提交前页面显示当前比例下的实际像素尺寸。

| 画质 | 1:1 | 3:4 | 16:9 |
|---|---|---|---|
| 1K | 1024 × 1024 | 768 × 1024（3:4） | 1536 × 1024（3:2） |
| 2K | 2048 × 2048 | 1536 × 2048（3:4） | 2048 × 1152（16:9） |
| 4K | 不支持 | 不支持 | 3840 × 2160（16:9） |

表格是平台输出尺寸约定，具体模型可能只支持其中部分组合。
对于 `gpt-image-2`，请求宽高必须是 16 的倍数，总像素在 655,360–8,294,400 之间，
长边不超过 3840。系统选取同一比例、满足最小像素限制且不小于目标的原生尺寸，
然后缩小到目标输出尺寸，不裁剪主体、不放大低分辨率图片。
实测中转站可能把 720×960 请求返回为 1086×1448。gpt-image-2 允许返回其他同一比例、
且宽高均不小于目标输出的分辨率，再缩小至用户选择的尺寸；比例不符或清晰度不足仍报错。

1K/2K 的 3:4、4:3、2:3、3:2 等比例按短边等比计算；4K 仅开放 3840×2160 和
2160×3840，正方形、3:4 等比例的 4K 在页面和服务端都会被禁用。
能力接口的 `qualityRatios` 同时约束前端选择和服务端提交，非法组合在调用模型前拒绝。

上述约束来源于 [OpenAI 图片生成指南][image-guide]、[生图接口][image-generate]
及 [图片编辑接口][image-edit]。其他模型沿用精确尺寸校验，不推测其分辨率能力。

参考图限制为最多六张 JPG/PNG、每张 20 MB、解码不超过 3200 万像素。
规划层使用缩略参考图，生成层使用原参考图；第一张主体图是主参考。
规划层指定完整构图和文字层级，精修层保留中文标题、商品名和文案。
图像模型统一设计画面与文字，不强制固定留白或圆角文字框；尚不支持任意图层拖拽。

## 前端 API

均使用 JWT 身份、租户作用域和统一响应信封；page 从零开始。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/image-creations/capabilities` | 配置状态及支持画质 |
| POST | `/api/image-creations` | 创建并开始规划 |
| GET | `/api/image-creations?workflow=POSTER&page=0&size=20` | 历史与任务概况 |
| GET | `/api/image-creations/{id}` | 方案、逐张状态和结果 |
| POST | `/api/image-creations/{id}/answers` 或 `revisions` | 补充信息或重新规划 |
| POST | `/api/image-creations/{id}/retry` | 恢复规划任务 |
| POST | `/api/image-creations/{id}/items/{itemId}/retry` | 恢复中断任务或重试失败图片 |

作品接口为 `GET /api/image-creations/works`，支持 page/size。
已完成图片可通过单图路径后的 `/text` 修改标题说明，
或 `/regenerate` 仅重做该张。两种操作均创建新版本，原作品保留。

创建请求包含 `requestKey, workflow, brief, references, ratio, quality, count, purpose, style, templateId`。
`workflow` 为 POSTER 或 PRODUCT_SET。海报 count 固定 1；套图支持一到六张且必须提供主体素材。
`templateId` 可空，支持 romantic/newyear/seasonal。不接受客户端 tenantId 或任意素材 URL。

revisions/answers 使用 `{requestKey, instruction}`。
text 使用 `{requestKey, headline, caption}`；regenerate 使用 `{requestKey, variation}`。
海报 variation 可选 `LAYOUT`、`SCENE`、`MESSAGE`，重新规划画面并原样保留文案；产品套图沿用原单张方案。
retry 使用当前查询结果中的 `{taskId}`，重复点击不会重复排队。
创建、新版本请求的 requestKey 必须稳定，网络结果未知时重放原请求。

## 任务、数据与边界

`image_creations` 保存输入、方案、父版本、规划任务；
`image_items` 保存逐张任务、供应商任务号、模型与用量信息、底图和作品存储键。
两张表都启用强制 RLS。业务访问其他模块仅经过公开服务。

规划与图片任务分别进入 DEFAULT、IMAGE 队列。
异步桥接模式的每次轮询是可恢复的小任务，网络调用不持有数据库事务；
业务回写检查任务执行轮次及租约。最终任务失败会在查询中显示为中断，
可恢复原任务；供应商明确失败时才使用新生成轮次。

输出对象键含租户、图片 ID 和生成轮次。
若对象保存成功而数据库回写中断，可从已保存的底图恢复，避免重复生图。
海报改文字将原图作为参考调用图片编辑，局部画面可能变化；此路径只处理文字修改。
海报换版重新规划视觉方向，同一参考图的近期方案用于避开重复构图；成图与近期作品过于相似时只提示，不自动再次付费生成。
产品套图的单张重做继续沿用原方案。
精修结果保存在逐张方案中，轮询、恢复和重做同一方案时复用，避免反复精修。
一组有成功有失败时显示部分完成，不丢弃成功图片。

未包括真实支付扣款、多商品批量分组、任意图层编辑、自动发布及模型效果保证。
不提供默认商家事实。真实供应商凭证及商品保真效果需在选定模型后联调验证。

## 验证

在后端目录执行 `mvn clean verify`，测试使用独立 PostgreSQL 与 MinIO，
覆盖租户隔离、素材归属、并发幂等、异步查单、保存恢复、失败单张重试、
文字版本、HTTP 模型协议、尺寸与规划方案校验。
在仓库根目录执行 `npm test`、`npm run lint`、`npm run typecheck`、`npm run build`。

测试中的模型响应是受控测试数据；测试通过不代表真实供应商已配置或模型效果已验收。

## Claude 单层文本模型配置

规划和精修统一使用 `TEXT_REFINER` 别名，对应 `IMAGE_REFINER_MODEL`、
`IMAGE_REFINER_URL`、`IMAGE_REFINER_API_KEY`。旧的 `IMAGE_PLANNER_ALIAS`、
`IMAGE_ADVANCED_PLANNER_*` 配置仅为兼容旧环境保留，不再路由到 GPT-6 Luna；无论旧值是什么，图片规划都会固定走 Claude。
省略地址时从 `NEW_API_BASE_URL` 推导，省略密钥时使用 `NEW_API_API_KEY`。
Claude Sonnet 必须由中转站提供兼容 `/chat/completions` 的协议；未实现 Anthropic 原生协议。
文本端点默认使用 0.5 温度、3000 最大令牌和 300 秒超时；令牌上限以 `max_completion_tokens` 发送。

精修输出 JSON `{"prompt":"Identity: ... Composition: ... Lighting: ... Camera: ... Restrictions: ..."}`，
须包含 Depth、Quality 和 Avoid。中文标题、说明和引号内中文名称必须保留。
请求异常、超时、空结果或校验不通过均退回原提示词，并补充 Avoid 排除项。
精修只允许优化镜头、构图、信息层级、光线、材质和色彩；事实字段视为不可变约束。服务端只追加一次工作流专属 Avoid，避免同一段负面约束在规划、精修和渲染阶段重复堆叠。
`8K` 是提示词质感引导，实际图片分辨率仍按所选档位校验。

无需新增数据库列：规划模型、版本与用量保存在 `image_creations.plan.planning`；
最终提示词、精修模型、耗时、用量和状态保存在 `image_items.spec.refinement`。
规划阶段生成的精修状态为 `DIRECTOR_REFINED`；旧数据回退精修仍可能出现 `REFINED`、`DISABLED`、`NOT_CONFIGURED`、`FAILED`、`INVALID_OUTPUT`。
持久化受现有 RLS、任务租约与执行轮次保护；已有 JSON 数据缺失这些字段时仍可读取。

DEBUG 日志包含 Original visualDirection、Raw prompt、Refined prompt。
网关通过 Micrometer 记录 `ai.calls`、`ai.failures`、`ai.tokens`、`ai.duration`；
供应商明确提供费用时才记录 `ai.reported.cost` 和样本数，费用币种沿用供应商口径，
不把未提供费用视为免费，不按预期百分比或固定人民币金额伪造实测结果。
这些指标不带租户标签，详细单图追踪仍受租户隔离约束；指标为进程级，重启会重置。

升级后无需双模型迁移；重启 API 和 worker 后，新任务固定由 Claude 完成需求理解与精修。
已持久化的图片提示词继续复用；旧任务缺少精修结果时会按兼容路径补做一次 Claude 精修。

[image-guide]: https://developers.openai.com/api/docs/guides/image-generation
[image-generate]: https://developers.openai.com/api/reference/resources/images/methods/generate
[image-edit]: https://developers.openai.com/api/reference/resources/images/methods/edit
