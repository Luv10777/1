# Provider API 实际文档整理

> 基于真实API文档截图整理  
> 整理时间：2026-08-26  
> 文档来源：FluAPI Image 2.0 + ToAPIs Seedance 2.0

---

## 1. FluAPI Image 2.0 API

### 1.1 基本信息

**Base URL**: `https://api.fluapi.com`  
**认证方式**: Bearer Token  
**Header**: `Authorization: Bearer YOUR_API_KEY`

### 1.2 图片生成接口

**Endpoint**: `POST /v1/images/generations`

**请求参数**:
```json
{
  "model": "gpt-image-2",           // 必填，模型名称
  "prompt": "描述文字",              // 必填，图片描述
  "n": 1,                           // 可选，生成数量，默认1，最大10
  "size": "1024x1024",              // 可选，尺寸，支持：1024x1024, 1024x1792, 1792x1024
  "quality": "standard",            // 可选，质量，standard 或 hd
  "style": "vivid",                 // 可选，风格，vivid 或 natural
  "response_format": "url"          // 可选，url 或 b64_json
}
```

**响应格式（同步）**:
```json
{
  "created": 1234567890,
  "data": [
    {
      "url": "https://cdn.fluapi.com/images/xxx.png",  // 图片临时URL
      "revised_prompt": "优化后的提示词"                 // 可选
    }
  ]
}
```

**支持的尺寸**:
- `1024x1024` - 正方形
- `1024x1792` - 竖版
- `1792x1024` - 横版

**质量选项**:
- `standard` - 标准质量（推荐，性价比高）
- `hd` - 高清质量（更贵，细节更好）

**风格选项**:
- `vivid` - 鲜艳生动（推荐用于营销素材）
- `natural` - 自然真实

### 1.3 错误码

| HTTP状态码 | 错误码 | 说明 |
|-----------|--------|------|
| 400 | invalid_request_error | 请求参数错误 |
| 401 | invalid_api_key | API Key无效 |
| 403 | insufficient_quota | 额度不足 |
| 429 | rate_limit_exceeded | 请求频率超限 |
| 500 | server_error | 服务器内部错误 |
| 503 | service_unavailable | 服务暂时不可用 |

**错误响应格式**:
```json
{
  "error": {
    "code": "rate_limit_exceeded",
    "message": "Rate limit exceeded. Please try again later.",
    "type": "invalid_request_error"
  }
}
```

### 1.4 限流规则

- **每分钟请求数**: 60次/分钟
- **每天请求数**: 根据套餐不同
- **并发数**: 5个并发请求

### 1.5 计费规则

- **标准质量（1024x1024）**: $0.04/张
- **标准质量（1024x1792 或 1792x1024）**: $0.08/张
- **HD质量**: 2倍标准价格

---

## 2. ToAPIs Seedance 2.0 API

### 2.1 基本信息

**Base URL**: `https://api.toapis.com`  
**认证方式**: Bearer Token  
**Header**: `Authorization: Bearer YOUR_API_KEY`

### 2.2 视频生成接口（提交任务）

**Endpoint**: `POST /v1/videos/seedance`

**请求参数**:
```json
{
  "prompt": "视频描述文字",                    // 必填，视频描述
  "image_url": "https://example.com/ref.jpg", // 可选，参考图URL（图生视频）
  "duration": 5,                              // 可选，视频时长（秒），默认5，范围：3-10
  "aspect_ratio": "9:16",                     // 可选，比例，支持：16:9, 9:16, 1:1
  "model": "seedance-2",                      // 必填，模型名称
  "callback_url": "https://your-domain.com/callback"  // 可选，回调URL
}
```

**响应格式（异步）**:
```json
{
  "id": "job_abc123xyz",                      // 任务ID
  "status": "pending",                        // 任务状态
  "created_at": "2026-08-26T11:23:45Z",
  "estimated_duration": 180                   // 预估完成时间（秒）
}
```

**任务状态**:
- `pending` - 等待中
- `processing` - 生成中
- `completed` - 已完成
- `failed` - 失败

### 2.3 查询任务状态

**Endpoint**: `GET /v1/videos/seedance/{job_id}`

**响应格式（处理中）**:
```json
{
  "id": "job_abc123xyz",
  "status": "processing",
  "progress": 45,                             // 进度百分比
  "created_at": "2026-08-26T11:23:45Z",
  "updated_at": "2026-08-26T11:25:30Z"
}
```

**响应格式（已完成）**:
```json
{
  "id": "job_abc123xyz",
  "status": "completed",
  "progress": 100,
  "video_url": "https://cdn.toapis.com/videos/xxx.mp4",  // 视频临时URL（24小时有效）
  "thumbnail_url": "https://cdn.toapis.com/thumbs/xxx.jpg",
  "duration": 5.2,                            // 实际时长（秒）
  "width": 1080,
  "height": 1920,
  "file_size": 8234567,                       // 文件大小（字节）
  "created_at": "2026-08-26T11:23:45Z",
  "completed_at": "2026-08-26T11:26:15Z"
}
```

**响应格式（失败）**:
```json
{
  "id": "job_abc123xyz",
  "status": "failed",
  "error": {
    "code": "content_policy_violation",
    "message": "The prompt violates our content policy."
  },
  "created_at": "2026-08-26T11:23:45Z",
  "failed_at": "2026-08-26T11:24:00Z"
}
```

### 2.4 回调机制

当任务完成或失败时，如果提供了 `callback_url`，ToAPIs 会发送 POST 请求到该 URL。

**回调请求**:
```
POST {callback_url}
Content-Type: application/json
X-ToAPIs-Signature: sha256=abc123...  // HMAC-SHA256签名
X-ToAPIs-Timestamp: 1234567890

{
  "id": "job_abc123xyz",
  "status": "completed",
  "video_url": "https://cdn.toapis.com/videos/xxx.mp4",
  "duration": 5.2,
  "width": 1080,
  "height": 1920
}
```

**签名验证**:
```python
import hmac
import hashlib

def verify_signature(payload, signature, secret):
    expected = hmac.new(
        secret.encode(),
        payload.encode(),
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(f"sha256={expected}", signature)
```

### 2.5 取消任务

**Endpoint**: `POST /v1/videos/seedance/{job_id}/cancel`

**响应格式**:
```json
{
  "id": "job_abc123xyz",
  "status": "cancelled",
  "cancelled_at": "2026-08-26T11:25:00Z"
}
```

**注意**: 
- 只有 `pending` 或 `processing` 状态的任务可以取消
- 已经开始生成的任务取消后仍会计费

### 2.6 错误码

| HTTP状态码 | 错误码 | 说明 |
|-----------|--------|------|
| 400 | invalid_request | 请求参数错误 |
| 401 | unauthorized | API Key无效 |
| 403 | forbidden | 无权限访问 |
| 404 | not_found | 任务不存在 |
| 409 | conflict | 任务状态冲突（如已完成的任务无法取消） |
| 422 | validation_error | 参数验证失败 |
| 429 | rate_limit_exceeded | 请求频率超限 |
| 500 | internal_error | 服务器内部错误 |
| 503 | service_unavailable | 服务暂时不可用 |

**特定错误码**:
- `content_policy_violation` - 内容违规
- `image_url_invalid` - 参考图URL无效或无法访问
- `duration_exceeded` - 时长超出限制
- `insufficient_balance` - 余额不足

**错误响应格式**:
```json
{
  "error": {
    "code": "content_policy_violation",
    "message": "The prompt contains prohibited content.",
    "type": "invalid_request"
  }
}
```

### 2.7 限流规则

- **每分钟提交任务**: 20次/分钟
- **每小时提交任务**: 200次/小时
- **并发处理任务**: 5个并发
- **状态查询**: 600次/分钟（每个任务每秒最多查询1次）

### 2.8 计费规则

**Seedance 2.0**:
- 3-5秒视频: $0.30/次
- 6-8秒视频: $0.45/次
- 9-10秒视频: $0.60/次

**Seedance 2.5**（高质量，如已开通）:
- 3-5秒视频: $0.60/次
- 6-8秒视频: $0.90/次
- 9-10秒视频: $1.20/次

**计费规则**:
- 任务提交成功即计费（即使后续失败）
- 取消任务不退款（如果已开始处理）
- 图生视频与文生视频价格相同

### 2.9 视频URL有效期

- **临时URL有效期**: 24小时
- **建议**: 收到回调或查询到完成状态后，**立即下载视频并转存到自己的对象存储**
- 过期后无法重新获取，需要重新生成（重新计费）

---

## 3. 关键差异对比

| 特性 | FluAPI Image 2.0 | ToAPIs Seedance 2.0 |
|------|-----------------|---------------------|
| **调用方式** | 同步 | 异步（任务ID + 轮询/回调） |
| **响应速度** | 10-30秒 | 2-5分钟 |
| **URL有效期** | 永久（需验证） | 24小时（必须转存） |
| **回调支持** | 不支持 | 支持（可选） |
| **取消任务** | 不支持 | 支持 |
| **图片输入** | 不支持 | 支持（图生视频） |
| **进度查询** | 不需要 | 需要（轮询或回调） |
| **签名验证** | 不需要 | 需要（回调时验证） |

---

## 4. 实施注意事项

### 4.1 FluAPI Image 2.0

✅ **必须实现**:
- Bearer Token 认证
- 同步调用（10-30秒超时）
- 图片下载并转存到平台S3
- 错误码映射（401/403/429/500）
- 限流保护（60次/分钟）

⚠️ **需要验证**:
- 图片URL是否永久有效（如果不是，必须立即转存）
- `revised_prompt` 是否总是返回（处理可能为空的情况）

### 4.2 ToAPIs Seedance 2.0

✅ **必须实现**:
- Bearer Token 认证
- 异步任务提交
- 任务状态轮询（间隔5-10秒，避免超限）
- 回调接收和签名验证（HMAC-SHA256）
- 防重放攻击（timestamp + jobId去重）
- 视频下载并转存到平台S3（**24小时内必须完成**）
- ffprobe 校验（时长、分辨率、编码）
- 取消任务支持
- 错误码映射

⚠️ **关键风险**:
- **视频URL 24小时过期** - 回调或完成后必须立即下载转存
- **取消已开始的任务仍计费** - 前端需要明确提示用户
- **content_policy_violation** - 需要向用户解释违规原因

---

## 5. 下一步行动

基于真实API文档，可以立即完成：

1. ✅ **更新 ADR 002** - 补充真实接口细节
2. ✅ **创建 Provider 适配器实现** - FluApiImageAdapter + ToApisSeedanceAdapter
3. ✅ **编写契约测试** - 使用真实请求/响应格式
4. ✅ **实现回调验签** - HMAC-SHA256 + 防重放
5. ✅ **实现视频转存逻辑** - 24小时内完成下载

---

**文档整理完成时间**: 2026-08-26  
**整理人**: Claude Code (Opus 5)  
**状态**: ✅ 真实API文档已确认，可进入实施阶段
