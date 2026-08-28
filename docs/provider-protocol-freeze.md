# Provider API 协议冻结文档

**版本**: v1.0  
**冻结时间**: 2026-08-26  
**状态**: ✅ 已冻结

---

## 1. FluAPI Image 2.0

### 1.1 基本信息
- **Base URL**: `https://api.fluapi.com`
- **模型**: `gpt-image-2`
- **认证**: `Authorization: Bearer {API_KEY}`
- **类型**: 同步返回

### 1.2 接口协议

**Endpoint**: `POST /v1/images/generations`

**请求**:
```json
{
  "model": "gpt-image-2",
  "prompt": "描述文字",
  "n": 1,
  "size": "1024x1024",
  "quality": "standard",
  "style": "vivid",
  "response_format": "url"
}
```

**响应**:
```json
{
  "created": 1234567890,
  "data": [
    {
      "url": "https://cdn.fluapi.com/images/xxx.png",
      "revised_prompt": "优化后的提示词"
    }
  ]
}
```

### 1.3 参数规范
- **size**: `1024x1024` | `1024x1792` | `1792x1024`
- **quality**: `standard` | `hd`
- **style**: `vivid` | `natural`

---

## 2. FluAPI Text (gpt5.6-luna)

### 2.1 基本信息
- **Base URL**: `https://api.fluapi.com`
- **模型**: `gpt5.6-luna`
- **认证**: `Authorization: Bearer {API_KEY}`
- **类型**: 同步返回
- **协议**: OpenAI-compatible

### 2.2 接口协议

**Endpoint**: `POST /v1/chat/completions`

**请求**:
```json
{
  "model": "gpt5.6-luna",
  "messages": [
    {"role": "user", "content": "生成视频脚本"}
  ],
  "temperature": 0.7,
  "max_tokens": 2000,
  "stream": false
}
```

**响应**:
```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "created": 1234567890,
  "model": "gpt5.6-luna",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "生成的文本内容"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 50,
    "total_tokens": 60
  }
}
```

---

## 3. ToAPIs Seedance 2.0

### 3.1 基本信息
- **Base URL**: `https://api.toapis.com`
- **模型**: `seedance-2`
- **认证**: `Authorization: Bearer {API_KEY}`
- **类型**: 异步返回（提交 + 轮询）

### 3.2 提交任务

**Endpoint**: `POST /v1/videos/seedance`

**请求**:
```json
{
  "prompt": "视频描述文字",
  "image_url": "https://example.com/ref.jpg",
  "duration": 5,
  "aspect_ratio": "9:16",
  "model": "seedance-2",
  "callback_url": null
}
```

**响应**:
```json
{
  "id": "job_abc123xyz",
  "status": "pending",
  "created_at": "2026-08-26T11:23:45Z",
  "estimated_duration": 180
}
```

### 3.3 查询状态

**Endpoint**: `GET /v1/videos/seedance/{job_id}`

**响应（处理中）**:
```json
{
  "id": "job_abc123xyz",
  "status": "processing",
  "progress": 45,
  "created_at": "2026-08-26T11:23:45Z",
  "updated_at": "2026-08-26T11:25:30Z"
}
```

**响应（完成）**:
```json
{
  "id": "job_abc123xyz",
  "status": "completed",
  "progress": 100,
  "video_url": "https://cdn.toapis.com/videos/xxx.mp4",
  "thumbnail_url": "https://cdn.toapis.com/thumbs/xxx.jpg",
  "duration": 5.2,
  "width": 1080,
  "height": 1920,
  "file_size": 8234567,
  "created_at": "2026-08-26T11:23:45Z",
  "completed_at": "2026-08-26T11:26:15Z"
}
```

### 3.4 参数规范
- **duration**: `3-10` 秒
- **aspect_ratio**: `16:9` | `9:16` | `1:1`

---

## 4. 统一 Adapter 接口

### 4.1 ProviderAdapter

```java
public interface ProviderAdapter {
    ProviderTaskResponse submitTask(ProviderTaskRequest request, String apiKey);
    ProviderTaskResponse checkTaskStatus(String providerJobId, String apiKey);
    boolean cancelTask(String providerJobId, String apiKey);
    String getProviderName();
    boolean isSynchronous();
}
```

### 4.2 实现类

| Adapter | Provider | 类型 | 状态 |
|---------|----------|------|------|
| `FluAPIImageAdapter` | FluAPI Image 2.0 | 同步 | ✅ 已实现 |
| `FluAPITextAdapter` | FluAPI gpt5.6-luna | 同步 | ✅ 已实现 |
| `ToAPIsVideoAdapter` | ToAPIs Seedance 2.0 | 异步 | ✅ 已实现 |

---

## 5. 错误处理

### 5.1 统一错误码映射

| Provider错误 | HTTP码 | 统一状态 |
|-------------|--------|---------|
| `invalid_request_error` | 400 | FAILED |
| `invalid_api_key` | 401 | FAILED |
| `rate_limit_exceeded` | 429 | FAILED |
| `server_error` | 500 | FAILED |
| `content_policy_violation` | 422 | FAILED |

### 5.2 重试策略
- **同步接口**: 遇到 429/500/503 立即重试，最多3次，指数退避
- **异步接口**: 轮询遇到网络错误重试，最多3次

---

## 6. 限流规则

| Provider | 每分钟 | 每天 | 并发 |
|----------|-------|------|------|
| FluAPI Image | 60 | 套餐 | 5 |
| FluAPI Text | 60 | 套餐 | 5 |
| ToAPIs Video | 30 | 套餐 | 3 |

---

## 7. 计费规则

| Provider | 规格 | 价格 |
|----------|------|------|
| FluAPI Image | 1024x1024 standard | $0.04/张 |
| FluAPI Image | 1024x1792 standard | $0.08/张 |
| FluAPI Image | HD质量 | 2x标准价格 |
| FluAPI Text | gpt5.6-luna | $0.002/1k tokens |
| ToAPIs Video | 5秒视频 | $0.20/视频 |

---

## 8. 协议版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-08-26 | 初始冻结版本 |

---

**协议冻结承诺**: 
- Adapter 接口在 Phase 1-10 内保持不变
- Provider API 变更通过配置适配，不修改 Adapter 代码
- 新增 Provider 通过实现 ProviderAdapter 接口扩展
