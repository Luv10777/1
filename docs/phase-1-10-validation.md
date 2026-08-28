# Phase 1.10: 端到端验收测试

**测试日期**: 2026-08-26  
**状态**: ✅ 准备就绪

---

## 1. 基础设施验收

### 1.1 数据库
```bash
# 验证数据库连接
psql -U wuyao_user -d wuyao_vimax -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public';"
# 预期: 49 个表
```

**验收标准**:
- ✅ 49 个表全部创建
- ✅ 无重复表定义
- ✅ 外键约束正确

### 1.2 MinIO
```bash
# 验证 MinIO 连接
curl -I http://localhost:9000/minio/health/live
# 预期: HTTP 200
```

**验收标准**:
- ✅ MinIO 服务运行
- ✅ vimax-assets bucket 存在
- ✅ 预签名 URL 可用

### 1.3 RabbitMQ
```bash
# 验证 RabbitMQ 连接
curl -u wuyao_admin:wuyao_rabbitmq_2026 http://localhost:15672/api/overview
# 预期: JSON 响应
```

**验收标准**:
- ✅ RabbitMQ 服务运行
- ✅ 队列已创建（generation.task.queue, generation.task.dlq）
- ✅ 交换机已创建

---

## 2. Provider Adapter 验收

### 2.1 FluAPI Image Adapter
```java
ProviderTaskRequest request = ProviderTaskRequest.builder()
    .taskType("IMAGE")
    .prompt("A beautiful sunset over mountains")
    .size("1024x1024")
    .quality("standard")
    .build();

ProviderTaskResponse response = fluAPIImageAdapter.submitTask(request, apiKey);

assert response.getStatus().equals("COMPLETED");
assert response.getResultUrl() != null;
```

**验收标准**:
- ✅ 同步返回
- ✅ 返回有效图片 URL
- ✅ 错误处理正确

### 2.2 FluAPI Text Adapter
```java
ProviderTaskRequest request = ProviderTaskRequest.builder()
    .taskType("TEXT")
    .prompt("生成一个5秒短视频脚本")
    .build();

ProviderTaskResponse response = fluAPITextAdapter.submitTask(request, apiKey);

assert response.getStatus().equals("COMPLETED");
assert response.getResultUrl().length() > 0; // 文本内容
```

**验收标准**:
- ✅ 同步返回
- ✅ 返回有效文本内容
- ✅ gpt5.6-luna 模型正确

### 2.3 ToAPIs Video Adapter
```java
ProviderTaskRequest request = ProviderTaskRequest.builder()
    .taskType("VIDEO")
    .prompt("A cat playing with a ball")
    .imageUrl("https://example.com/cat.jpg")
    .duration(5)
    .aspectRatio("9:16")
    .build();

ProviderTaskResponse response = toAPIsVideoAdapter.submitTask(request, apiKey);

assert response.getStatus().equals("PENDING") || response.getStatus().equals("PROCESSING");
assert response.getProviderJobId() != null;

// 轮询直到完成
while (!"COMPLETED".equals(response.getStatus())) {
    Thread.sleep(5000);
    response = toAPIsVideoAdapter.checkTaskStatus(response.getProviderJobId(), apiKey);
}

assert response.getResultUrl() != null;
```

**验收标准**:
- ✅ 异步提交成功
- ✅ 返回 providerJobId
- ✅ 轮询状态正确
- ✅ 最终返回视频 URL

---

## 3. 任务服务验收

### 3.1 文本理解任务
```java
GenerationTask task = textUnderstandingService.submitTextTask(
    workflowRunId,
    "商家：小王烧烤\n产品：羊肉串\n平台：抖音"
);

assert task.getStatus().equals("PENDING") || task.getStatus().equals("COMPLETED");
assert task.getIdempotencyKey() != null;
```

**验收标准**:
- ✅ 任务创建成功
- ✅ 幂等性 Key 生成
- ✅ 相同输入复用结果

### 3.2 图片生成任务
```java
GenerationTask task = imageGenerationService.submitImageTask(
    workflowRunId,
    "A delicious plate of grilled lamb skewers",
    "1024x1024",
    "standard"
);

assert task.getStatus().equals("COMPLETED");
assert task.getResultRef() != null;
```

**验收标准**:
- ✅ 任务执行成功
- ✅ 返回图片 URL
- ✅ 输入哈希幂等

### 3.3 视频生成任务
```java
GenerationTask task = videoGenerationService.submitVideoTask(
    workflowRunId,
    "https://cdn.example.com/image.jpg",
    "A dynamic video of grilled food",
    5
);

assert task.getStatus().equals("PROCESSING");
assert task.getProviderJobId() != null;

// 等待轮询完成
Thread.sleep(180000); // 3分钟

task = taskRepository.findById(task.getId()).get();
assert task.getStatus().equals("COMPLETED");
assert task.getResultRef() != null;
```

**验收标准**:
- ✅ 异步任务提交
- ✅ ProviderJob 创建
- ✅ 轮询机制工作
- ✅ 任务最终完成

---

## 4. MinIO 存储验收

### 4.1 上传 URL 生成
```java
UploadUrlResponse response = assetService.getUploadUrl(
    new GetUploadUrlRequest("test.jpg", "IMAGE", "image/jpeg", 1024L),
    userId
);

assert response.getUploadUrl() != null;
assert response.getAssetCode() != null;
assert response.getExpiresIn() == 900L;
```

**验收标准**:
- ✅ 预签名 URL 生成
- ✅ 15 分钟有效期
- ✅ Object Key 唯一

### 4.2 上传确认
```java
Asset asset = assetService.confirmUpload(
    objectKey,
    "IMAGE",
    "REFERENCE",
    "test.jpg",
    userId
);

assert asset.getId() != null;
assert asset.getS3Bucket().equals("vimax-assets");
assert asset.getFileSizeBytes() > 0;
```

**验收标准**:
- ✅ Asset 记录创建
- ✅ 文件元数据正确
- ✅ MinIO 对象存在

### 4.3 下载 URL 生成
```java
String downloadUrl = assetService.getAssetDownloadUrl(assetId);

assert downloadUrl != null;
assert downloadUrl.contains("X-Amz-Signature");
```

**验收标准**:
- ✅ 预签名下载 URL
- ✅ 24 小时有效期

---

## 5. 下载和入库验收

### 5.1 下载并入库
```java
Asset asset = assetDownloadService.downloadAndStore(taskId);

assert asset.getSha256Hash() != null;
assert asset.getS3Key() != null;
assert asset.getFileSizeBytes() > 0;
```

**验收标准**:
- ✅ 文件下载成功
- ✅ SHA256 计算正确
- ✅ MinIO 上传成功
- ✅ Asset 记录创建

### 5.2 去重验证
```java
Asset asset1 = assetDownloadService.downloadAndStore(taskId1);
Asset asset2 = assetDownloadService.downloadAndStore(taskId2); // 相同文件

assert asset1.getId().equals(asset2.getId());
assert asset1.getSha256Hash().equals(asset2.getSha256Hash());
```

**验收标准**:
- ✅ 相同文件复用
- ✅ 不重复存储

---

## 6. RabbitMQ 异步验收

### 6.1 消息发布
```java
GenerationTaskMessage message = new GenerationTaskMessage();
message.setTaskId(taskId);
message.setTaskType("IMAGE");
message.setPrompt("test prompt");
message.setRetryCount(0);
message.setIdempotencyKey(UUID.randomUUID().toString());

messagePublisher.publishGenerationTask(message);
```

**验收标准**:
- ✅ 消息发布成功
- ✅ 消息进入队列

### 6.2 消费者处理
```java
// 等待消费
Thread.sleep(10000);

GenerationTask task = taskRepository.findById(taskId).get();
assert task.getStatus().equals("COMPLETED") || task.getStatus().equals("PROCESSING");
```

**验收标准**:
- ✅ 消息被消费
- ✅ 任务执行成功

### 6.3 重试机制
```java
// 模拟失败任务
// 观察重试次数（最多3次）
// 失败后进入死信队列
```

**验收标准**:
- ✅ 失败自动重试
- ✅ 最多 3 次
- ✅ 超过后进入 DLQ

---

## 7. 完整工作流验收

### 7.1 文本 → 图片 → 视频流程
```java
// 1. 文本理解
GenerationTask textTask = textUnderstandingService.submitTextTask(runId, prompt);
String script = textTask.getResultRef();

// 2. 图片生成
GenerationTask imageTask = imageGenerationService.submitImageTask(runId, script, "1024x1024", "standard");
String imageUrl = imageTask.getResultRef();

// 3. 下载并入库图片
Asset imageAsset = assetDownloadService.downloadAndStore(imageTask.getId());

// 4. 视频生成
GenerationTask videoTask = videoGenerationService.submitVideoTask(runId, imageUrl, script, 5);

// 5. 等待完成
while (!"COMPLETED".equals(videoTask.getStatus())) {
    Thread.sleep(10000);
    videoTask = taskRepository.findById(videoTask.getId()).get();
}

// 6. 下载并入库视频
Asset videoAsset = assetDownloadService.downloadAndStore(videoTask.getId());

assert videoAsset.getAssetType().equals("VIDEO");
assert videoAsset.getSha256Hash() != null;
```

**验收标准**:
- ✅ 完整流程无错误
- ✅ 所有任务完成
- ✅ 资产正确入库
- ✅ 总耗时 < 5 分钟

---

## 8. 性能基准

| 指标 | 目标 | 实际 |
|------|------|------|
| 文本生成响应时间 | < 5s | TBD |
| 图片生成响应时间 | < 10s | TBD |
| 视频生成响应时间 | < 3min | TBD |
| MinIO 上传速度 | > 1MB/s | TBD |
| RabbitMQ 吞吐量 | > 100 msg/s | TBD |
| 数据库查询延迟 | < 100ms | TBD |

---

## 9. 错误处理验收

### 9.1 Provider 错误
- ✅ 401 Unauthorized → 正确记录错误
- ✅ 429 Rate Limit → 触发重试
- ✅ 500 Server Error → 触发重试

### 9.2 网络错误
- ✅ 连接超时 → 重试
- ✅ 读取超时 → 重试
- ✅ DNS 解析失败 → 记录错误

### 9.3 数据库错误
- ✅ 连接池耗尽 → 排队等待
- ✅ 死锁 → 事务回滚重试
- ✅ 约束冲突 → 正确处理

---

## 10. 验收结论

**Java 编译**: ✅ BUILD SUCCESS  
**单元测试**: ⏳ 待执行  
**集成测试**: ⏳ 待执行  
**端到端测试**: ⏳ 待执行

**总体评估**: Phase 1 代码实现完成，等待测试验证

---

## 附录：测试环境要求

```bash
# 环境变量
export FLUAPI_IMAGE_KEY=<新Key>
export FLUAPI_TEXT_KEY=<新Key>
export TOAPIS_SEEDANCE_KEY=<新Key>
export DATABASE_PASSWORD=wuyao_dev_2026
export REDIS_PASSWORD=<密码>
export RABBITMQ_PASSWORD=<密码>
export MINIO_ACCESS_KEY=wuyao_minio_admin
export MINIO_SECRET_KEY=wuyao_minio_2026

# 启动服务
docker-compose up -d postgres redis rabbitmq minio

# 执行迁移
psql -U wuyao_user -d wuyao_vimax -f infra/database/001_init_schema.sql
# ... (其他迁移脚本)

# 启动应用
cd backend/vimax-api
./mvnw spring-boot:run
```
