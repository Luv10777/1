# ADR 002: 统一 AI Gateway 用于 FluAPI 和 ToAPIs

**状态**: 已批准  
**日期**: 2026-08-26  
**决策者**: 架构团队  
**影响范围**: AI 模型调用、供应商适配、成本核算

---

## 背景

梧曜星枢 AI 视频工作流需要调用多种 AI 能力：
1. **文本模型**: 创意规划、脚本生成、分镜设计
2. **视觉理解**: 参考图筛选、质量评估
3. **图片生成**: Image 2.0 参考帧生成
4. **视频生成**: Seedance 2.0/2.5 镜头视频生成

根据技术决策，所有 AI 能力统一路由到两个供应商：
- **FluAPI**: 文本、视觉理解、Image 2.0
- **ToAPIs**: Seedance 2.0（快速）、Seedance 2.5（高质量）

核心约束：
- **前端不能直连供应商** - 安全风险、无成本控制
- **业务层不能直接依赖供应商 JSON** - 供应商变更影响范围大
- **Python Worker 不能持有外部密钥** - 密钥泄漏风险
- **需要统一限流、熔断、成本采集、审计**

## 决策

**我们决定在 Spring Boot Platform API 中建立统一 AI Gateway，作为系统唯一对外 AI 调用通道。**

### 架构设计

```
Vue Web
  │
  ▼
[Platform API]
  ├─ Auth / Tenant / RBAC
  ├─ Workflow State Machine
  └─ AI Gateway ◄─────────── 系统唯一 AI 出口
      ├─ Capability Router (TEXT_FAST, IMAGE_PRIMARY, VIDEO_PRIMARY)
      ├─ Provider Adapters
      │   ├─ FluAPI Adapter
      │   └─ ToAPIs Adapter
      ├─ Rate Limiter (Redis)
      ├─ Circuit Breaker
      ├─ Cost Calculator
      └─ Audit Logger
          │
          ▼
Python AI Worker
  ├─ vimax_core (无状态 Activity)
  └─ Platform Clients (调用 Spring AI Gateway 内部接口)
          │
          ▼
Spring AI Gateway (内部接口，不暴露给前端)
  ├─ POST /internal/ai/text/generations
  ├─ POST /internal/ai/vision/analyses
  ├─ POST /internal/ai/images/generations
  ├─ POST /internal/ai/videos/jobs
  ├─ GET  /internal/ai/videos/jobs/{jobId}
  └─ POST /internal/providers/toapis/callback
          │
          ▼
FluAPI / ToAPIs (真实供应商)
```

### 核心接口

#### 1. 能力抽象（业务层只调用能力，不调用供应商）

```java
POST /internal/ai/text/generations
Content-Type: application/json
Authorization: Internal-Service <signature>

{
  "tenantId": "tenant_xxx",
  "workflowRunId": "wf_xxx",
  "activityId": "act_xxx",
  "traceId": "trace_xxx",
  "idempotencyKey": "idem_xxx",
  "capabilityAlias": "TEXT_PLANNER",    // 能力别名，不是模型名
  "messages": [...],
  "temperature": 0.7,
  "maxTokens": 2000
}

Response:
{
  "requestId": "req_xxx",
  "provider": "fluapi",                  // 内部字段，前端不可见
  "modelAlias": "TEXT_PLANNER",
  "providerModel": "gpt-4o",            // 内部字段，前端不可见
  "status": "SUCCEEDED",
  "output": {
    "text": "...",
    "json": {...}
  },
  "usage": {
    "inputTokens": 1500,
    "outputTokens": 800
  },
  "estimatedCost": 0.025,               // 平台成本（前端可见）
  "actualCost": 0.020                   // 供应商实际成本（前端不可见）
}
```

#### 2. 图片生成（同步/异步）

```java
POST /internal/ai/images/generations
{
  "tenantId": "tenant_xxx",
  "workflowRunId": "wf_xxx",
  "activityId": "act_xxx",
  "idempotencyKey": "idem_xxx",
  "capabilityAlias": "IMAGE_PRIMARY",
  "prompt": "...",
  "negativePrompt": "...",
  "referenceImages": ["s3://..."],
  "width": 1024,
  "height": 1024,
  "aspectRatio": "1:1"
}

Response (异步):
{
  "requestId": "req_xxx",
  "taskId": "pt_xxx",                   // Provider Task ID
  "status": "QUEUED",                   // QUEUED → RUNNING → SUCCEEDED/FAILED
  "estimatedCost": 0.08
}
```

#### 3. 视频生成（异步）

```java
POST /internal/ai/videos/jobs
{
  "tenantId": "tenant_xxx",
  "workflowRunId": "wf_xxx",
  "shotId": "shot_xxx",
  "idempotencyKey": "idem_xxx",
  "capabilityAlias": "VIDEO_PRIMARY",   // VIDEO_PRIMARY=Seedance2.0, VIDEO_PREMIUM=Seedance2.5
  "prompt": "...",
  "referenceImage": "s3://...",
  "firstFrame": "s3://...",
  "duration": 5,
  "aspectRatio": "9:16"
}

Response:
{
  "requestId": "req_xxx",
  "jobId": "job_xxx",
  "providerJobId": "toapis_job_xxx",    // 供应商任务 ID
  "status": "SUBMITTED",
  "estimatedCost": 0.50
}
```

#### 4. 任务状态查询

```java
GET /internal/ai/videos/jobs/{jobId}

Response:
{
  "jobId": "job_xxx",
  "providerJobId": "toapis_job_xxx",
  "status": "SUCCEEDED",                // SUBMITTED → QUEUED → PROCESSING → SUCCEEDED/FAILED
  "progress": 100,
  "output": {
    "videoUrl": "s3://tenant_xxx/...",  // 已转存平台对象存储
    "duration": 5.2,
    "width": 1080,
    "height": 1920
  },
  "actualCost": 0.48
}
```

#### 5. 回调接口（ToAPIs 异步通知）

```java
POST /internal/providers/toapis/callback
X-Toapis-Signature: sha256=...
X-Toapis-Timestamp: 1234567890

{
  "jobId": "toapis_job_xxx",
  "status": "SUCCEEDED",
  "videoUrl": "https://toapis-cdn.example/temp/xxx.mp4",  // 临时 URL
  "duration": 5.2,
  "width": 1080,
  "height": 1920
}

AI Gateway 行为:
1. 验证签名 (HMAC-SHA256)
2. 防重放 (检查 timestamp + jobId 去重)
3. 下载视频到平台对象存储 (s3://tenant_xxx/...)
4. ffprobe 校验 (时长、分辨率、编码格式)
5. 更新 provider_jobs 表状态
6. 发送内部事件通知 Platform API 状态机
```

### Provider 适配器设计

#### FluAPI Adapter

```java
@Service
public class FluApiAdapter implements TextModelProvider, VisionModelProvider, ImageGeneratorProvider {
    
    @Value("${fluapi.base-url}")
    private String baseUrl;
    
    @Value("${fluapi.api-key}")
    private String apiKey;
    
    public ProviderSubmission generateText(TextGenerationRequest request) {
        // 1. 鉴权：添加 API Key 到 Header
        // 2. 请求：POST {baseUrl}/v1/chat/completions
        // 3. 重试：指数退避，最多 3 次
        // 4. 限流：Redis 令牌桶 (每秒 10 次)
        // 5. 超时：connectTimeout=5s, readTimeout=30s
        // 6. 错误映射：FluAPI 错误码 → 内部 ProviderError
        // 7. 用量采集：记录 inputTokens/outputTokens
        // 8. 成本计算：根据配置单价计算
        // 9. 日志脱敏：不记录 api_key 和完整 Prompt
        // 10. 返回标准化 ProviderSubmission
    }
    
    public ProviderSubmission generateImage(ImageGenerationRequest request) {
        // 1. 调用 FluAPI Image 2.0 接口
        // 2. 如果异步：保存 providerTaskId，返回 QUEUED
        // 3. 如果同步：下载图片 → 校验 → 转存 S3 → 返回 SUCCEEDED
    }
    
    private ProviderError mapError(FluApiException e) {
        return switch(e.getCode()) {
            case "invalid_api_key" -> new ProviderError("AUTH_ERROR", e.getMessage(), false);
            case "rate_limit_exceeded" -> new ProviderError("RATE_LIMITED", e.getMessage(), true);
            case "insufficient_balance" -> new ProviderError("INSUFFICIENT_BALANCE", e.getMessage(), false);
            // ... 完整错误映射
            default -> new ProviderError("UNKNOWN_PROVIDER_ERROR", e.getMessage(), true);
        };
    }
}
```

#### ToAPIs Adapter

```java
@Service
public class ToApisAdapter implements AsyncVideoGeneratorProvider {
    
    public ProviderSubmission submitVideoJob(VideoGenerationRequest request) {
        // 1. 鉴权：添加 API Key
        // 2. 上传参考图到 ToAPIs (如果需要)
        // 3. 提交异步任务：POST {baseUrl}/v1/videos/jobs
        // 4. 保存 providerJobId 到 provider_jobs 表
        // 5. 注册回调 URL：{platformUrl}/internal/providers/toapis/callback
        // 6. 返回 SUBMITTED 状态
    }
    
    public ProviderJobStatus queryJobStatus(String providerJobId) {
        // 1. 轮询：GET {baseUrl}/v1/videos/jobs/{providerJobId}
        // 2. 状态映射：ToAPIs 状态 → 内部 JobStatus
        // 3. 如果完成：下载视频 → ffprobe 校验 → 转存 S3
        // 4. 返回标准化 ProviderJobStatus
    }
    
    public void handleCallback(ToApisCallback callback, String signature) {
        // 1. 验证签名：HMAC-SHA256(callback_secret, body)
        // 2. 防重放：Redis 去重 (jobId + timestamp)
        // 3. 幂等检查：如果已处理，直接返回 200
        // 4. 下载视频：从临时 URL 下载到平台 S3
        // 5. 校验视频：ffprobe 检查时长、分辨率、编码
        // 6. 更新状态：provider_jobs 表
        // 7. 发送事件：通知 Platform API 状态机
    }
    
    public void cancelJob(String providerJobId) {
        // 1. 调用：POST {baseUrl}/v1/videos/jobs/{providerJobId}/cancel
        // 2. 如果成功：更新状态为 CANCELLED
        // 3. 如果失败 (不支持取消)：记录日志，继续追踪实际成本
    }
}
```

### 能力配置管理

```yaml
# application.yml (服务端配置，不提交 Git)
ai:
  capabilities:
    TEXT_FAST:
      provider: fluapi
      model: gpt-4o-mini
      enabled: true
    TEXT_PLANNER:
      provider: fluapi
      model: gpt-4o
      enabled: true
    IMAGE_PRIMARY:
      provider: fluapi
      model: image-2-turbo
      enabled: true
    VIDEO_PRIMARY:
      provider: toapis
      model: seedance-2.0
      enabled: true
    VIDEO_PREMIUM:
      provider: toapis
      model: seedance-2.5
      enabled: false                   # 未开通时明确 false
      
fluapi:
  enabled: true
  base-url: ${FLUAPI_BASE_URL}
  api-key: ${FLUAPI_API_KEY}
  connect-timeout-ms: 5000
  read-timeout-ms: 30000
  
toapis:
  enabled: true
  base-url: ${TOAPIS_BASE_URL}
  api-key: ${TOAPIS_API_KEY}
  callback-secret: ${TOAPIS_CALLBACK_SECRET}
  connect-timeout-ms: 5000
  read-timeout-ms: 60000
```

管理端可配置：
- 能力开关 (enabled/disabled)
- 单价 (用于成本预估)
- 限流配置 (QPS、并发数)
- 熔断阈值 (错误率、超时率)

前端只看到能力别名 (`TEXT_PLANNER`)，不看到供应商和模型名。

### Python Worker 客户端

```python
# packages/vimax-core/src/vimax_core/clients/platform_text_model.py
from pydantic import BaseModel
import httpx

class PlatformTextModelClient:
    def __init__(self, gateway_url: str, service_key: str):
        self.gateway_url = gateway_url
        self.service_key = service_key
        self.client = httpx.AsyncClient()
    
    async def generate(self, request: TextGenerationRequest) -> TextGenerationResponse:
        response = await self.client.post(
            f"{self.gateway_url}/internal/ai/text/generations",
            json=request.model_dump(),
            headers={
                "Authorization": f"Internal-Service {self.service_key}",
                "X-Trace-Id": request.trace_id
            },
            timeout=60.0
        )
        response.raise_for_status()
        return TextGenerationResponse(**response.json())
```

ViMax Core Agent 调用示例：
```python
async def write_script(input: ScriptInput, client: PlatformTextModelClient) -> VideoScript:
    # 1. 编译 Prompt（注入商家事实快照）
    messages = compile_screenwriter_prompt(
        fact_snapshot=input.merchant_fact_snapshot,
        creative=input.creative_variant
    )
    
    # 2. 调用平台 Gateway（不直接调用供应商）
    response = await client.generate(
        TextGenerationRequest(
            tenant_id=input.tenant_id,
            workflow_run_id=input.workflow_run_id,
            activity_id=input.activity_id,
            trace_id=input.trace_id,
            idempotency_key=f"script_{input.workflow_run_id}_{input.creative_variant.variant_key}",
            capability_alias="TEXT_PLANNER",   # 能力别名，不是模型名
            messages=messages,
            temperature=0.7,
            response_format=VideoScriptSchema   # Pydantic Schema
        )
    )
    
    # 3. 解析并返回
    return VideoScript.model_validate(response.output.json)
```

## 后果

### 优势

✅ **统一出口，安全可控**
- 所有密钥只在服务端，前端和 Worker 无法直接调用供应商
- 统一鉴权、限流、熔断、审计

✅ **供应商透明**
- 业务层只调用能力 (`TEXT_PLANNER`)，不依赖供应商 JSON
- 供应商变更只需修改 Adapter，业务层不受影响

✅ **成本精准核算**
- 每次调用记录 requestId、usage、estimatedCost、actualCost
- 支持预估、预占、实际结算、幂等防重复扣费

✅ **可观测性强**
- 统一追踪 (traceId)
- 统一日志脱敏
- 统一监控指标 (延迟、错误率、成本)

✅ **任务恢复**
- 异步任务状态持久化到 provider_jobs 表
- 支持轮询 + 回调双保险
- 崩溃恢复时可继续查询 providerJobId

### 劣势与缓解

⚠️ **Gateway 成为单点**
- **缓解**: 无状态设计，可水平扩展；使用 Redis 共享限流状态

⚠️ **增加一跳延迟**
- **缓解**: Gateway 逻辑轻量 (< 10ms)，相比 AI 调用延迟 (5-30s) 可忽略

⚠️ **Adapter 开发工作量**
- **缓解**: 提供 Adapter 基类和契约测试模板，减少重复代码

### 风险

🔴 **FluAPI/ToAPIs 真实 API 文档缺失**
- **应对**: 先完成 Adapter 骨架和契约测试，用 fixture 验证接口设计，标记为"待真实验证"

🟡 **回调验签失败**
- **应对**: 详细记录验签失败日志，支持手动重放

🟡 **供应商临时 URL 过期**
- **应对**: 回调时立即下载转存平台 S3，不依赖供应商 URL

## 替代方案

### 方案A: 前端直连供应商（已拒绝）

**优势**: 延迟最低  
**劣势**: 
- API Key 暴露给前端（安全风险）
- 无法限流、熔断、成本控制
- 无法审计

**决策**: 拒绝，安全风险不可接受

### 方案B: Worker 直连供应商（已拒绝）

**优势**: 架构简单  
**劣势**: 
- Worker 持有外部密钥（泄漏风险）
- 无法统一限流、熔断
- 供应商变更影响 Worker 代码

**决策**: 拒绝，不符合企业级安全要求

### 方案C: 使用 LangChain/LiteLLM 作为 Gateway（已评估）

**优势**: 开源，支持多供应商  
**劣势**: 
- 无租户隔离
- 无成本账本
- 无 Transactional Outbox
- Python 实现，与 Spring Boot 技术栈不一致

**决策**: 拒绝，自研 Gateway 更可控

## 相关决策

- [ADR 001: 采用 ViMax Core 并进行无状态改造](./001-adopt-vimax-core-with-stateless-adaptation.md)
- [ADR 003: Transactional Outbox 用于 RabbitMQ](./003-transactional-outbox-for-rabbitmq.md)
- [ADR 008: 成本账本仅追加设计](./008-cost-ledger-append-only-design.md)

## 实施检查清单

- [ ] Spring Boot AI Gateway 模块
- [ ] FluAPI Adapter (文本、视觉、Image 2.0)
- [ ] ToAPIs Adapter (Seedance 2.0、2.5)
- [ ] Provider 错误统一映射
- [ ] 限流器 (Redis 令牌桶)
- [ ] 熔断器 (Resilience4j)
- [ ] 成本计算器
- [ ] 日志脱敏
- [ ] 回调验签
- [ ] Python Platform Client
- [ ] 契约测试 (脱敏 fixture)
- [ ] 管理端能力配置页面

---

**批准人**: 技术总监  
**生效日期**: 2026-08-26
