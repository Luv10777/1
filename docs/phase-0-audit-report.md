# Phase 0 审计报告与整改结果

**执行时间**: 2026-08-26  
**执行者**: Claude Code (Opus 5)  
**文档版本**: v1.0

---

## 1. 审计发现的关键问题

### 🚨 严重安全问题

#### 1.1 API Key 已泄露到 Git 历史

**位置**:
- `backend/vimax-api/src/main/java/com/wuyao/vimax/config/APIKeyConfig.java`
- `infra/database/006_insert_provider_configs.sql`

**泄露的 Key**:
```
FluAPI Image:  sk-1HRjy1gDrU9wF3XoKvKTx9uaEsbniwy1gLbIRnJvp11UFwH4
FluAPI Text:   sk-S0XMVxl441sA70FuHJxUdckCKIxAEZzKtl4ZDW1mmqJbNMlJ
ToAPIs:        sk-BxRSKVjxvH18Bm8aoLqcqBMBTnhYyvWivEDZxuzIR1LnVv4B
```

**提交记录**: 
- Commit: `3182f375a5c27ac23ad0192aabb2ccd519e4a8f5`
- 分支: `feat/phase-2-merchant-snapshot-and-assets`
- Commit message 直接暴露了完整 Key

**影响**: 
- 真实 API Key 已公开到 Git 历史
- 任何有仓库访问权限的人都可以看到
- 供应商账户可能被滥用

**整改状态**: ✅ 已完成代码清理，**用户必须立即撤销这3个Key并重新生成**

---

### ⚠️ Java 编译错误

#### 1.2 OutboxEventService 类型不匹配

**问题**:
- `OutboxEvent.aggregateId` 是 `String` 类型
- `OutboxEventService.createEvent()` 传入 `Long` 类型
- `OutboxEvent` 缺少 `status` 和 `maxRetries` 字段

**整改状态**: ✅ 已修复

#### 1.3 AIGatewayService 静态调用错误

**问题**:
- `APIKeyConfig` 改为 Spring Bean 后，方法不再是 static
- `AIGatewayService` 仍在静态上下文调用

**整改状态**: ✅ 已修复，通过依赖注入 `APIKeyConfig`

---

### 📊 数据库契约问题

#### 1.4 表重复定义

**重复的表**:
1. `audit_logs`: 在 `001_init_schema.sql:171` 和 `005_video_workflow_support.sql:232`
2. `knowledge_chunks`: 在 `002_resource_libraries.sql:107` 和 `005_video_workflow_support.sql:279`
3. `assets`: 在 `002_resource_libraries.sql:33` 和 `005_video_workflow_support.sql:53`

**影响**:
- SQL 迁移脚本按顺序执行会失败
- 无法从空数据库初始化
- 可能导致字段定义不一致

**整改状态**: ⚠️ **待修复** (Phase 1 任务)

#### 1.5 数据库名称使用

**当前配置**:
- `application.yml`: `jdbc:postgresql://localhost:5432/wuyao_vimax`
- 用户名: `wuyao_user`
- 密码: `wuyao_dev_2026` (明文，待改为环境变量)

**状态**: ⚠️ 需要在 Phase 1 统一配置并迁移到环境变量

---

## 2. 已完成的整改

### ✅ 2.1 移除硬编码 API Key

**修改文件**:
1. `backend/vimax-api/src/main/java/com/wuyao/vimax/config/APIKeyConfig.java`
   - 删除硬编码的 Map
   - 改为从 Spring `@Value` 读取环境变量
   - 转换为标准 Spring Bean

2. `infra/database/006_insert_provider_configs.sql`
   - 替换真实 Key 为 `PLACEHOLDER_FLUAPI_IMAGE_KEY` 等占位符
   - 添加注释说明需要从环境变量注入

3. `backend/vimax-api/src/main/resources/application.yml`
   - 新增 `provider.*` 配置段
   - 配置从环境变量读取：`${FLUAPI_IMAGE_KEY:}`

**环境变量清单**:
```bash
export FLUAPI_IMAGE_KEY=<新的图片生成Key>
export FLUAPI_TEXT_KEY=<新的文本生成Key>
export TOAPIS_SEEDANCE_KEY=<新的视频生成Key>
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=<MinIO用户名>
export MINIO_SECRET_KEY=<MinIO密码>
export MINIO_BUCKET=vimax-assets
```

---

### ✅ 2.2 修复 Java 编译错误

**修改文件**:
1. `backend/vimax-api/src/main/java/com/wuyao/vimax/entity/OutboxEvent.java`
   - 新增 `status` 字段 (VARCHAR(50))
   - 新增 `maxRetries` 字段 (INTEGER, 默认3)

2. `backend/vimax-api/src/main/java/com/wuyao/vimax/service/OutboxEventService.java`
   - 修复 `aggregateId` 类型转换：`String.valueOf(aggregateId)`

3. `backend/vimax-api/src/main/java/com/wuyao/vimax/service/gateway/AIGatewayService.java`
   - 注入 `APIKeyConfig` Bean
   - 替换所有静态调用为实例方法调用

**编译验证**:
```bash
cd backend/vimax-api
./mvnw clean compile
```

**结果**: ✅ BUILD SUCCESS

---

## 3. 数据库契约基线

### 3.1 迁移脚本清单

| 脚本文件 | 说明 | 状态 |
|---------|------|------|
| `001_init_schema.sql` | 租户、用户、商家、门店、权限基础表 | ✅ 可用 |
| `002_resource_libraries.sql` | 品牌库、素材库、知识库、作品库 | ⚠️ 有重复表 |
| `003_ai_gateway_tasks.sql` | AI提供商、模型别名、任务表 | ✅ 可用 |
| `004_video_workflow_core.sql` | 视频工作流核心表 | ✅ 可用 |
| `005_video_workflow_support.sql` | 质检、审核、成本、Outbox | ⚠️ 有重复表 |
| `006_insert_provider_configs.sql` | Provider配置初始数据 | ✅ 已清理Key |

### 3.2 核心表统计

**总表数**: 50个表

**分类**:
- 基础设施: 10个 (tenants, users, merchants, stores, roles, permissions, etc.)
- 资源库: 8个 (brands, assets, knowledge, works, etc.)
- AI网关: 6个 (ai_providers, model_aliases, ai_tasks, provider_jobs, etc.)
- 视频工作流: 12个 (video_projects, workflow_runs, workflow_steps, generation_tasks, etc.)
- 支撑系统: 14个 (audit_logs, cost_ledger, quality_reports, outbox_events, etc.)

### 3.3 关键实体映射

| 数据库表 | Java Entity | 状态 |
|---------|-------------|------|
| `video_projects` | `VideoProject` | ⚠️ 需验证字段一致性 |
| `workflow_runs` | `WorkflowRun` | ⚠️ 需验证 |
| `workflow_steps` | `WorkflowStep` | ⚠️ 需验证 |
| `generation_tasks` | `GenerationTask` | ⚠️ 需验证 |
| `provider_jobs` | `ProviderJob` | ✅ 已验证 |
| `assets` | `Asset` | ⚠️ 有重复定义 |
| `outbox_events` | `OutboxEvent` | ✅ 已修复 |

---

## 4. API 契约基线

### 4.1 视频项目 API

**端点**:
- `POST /api/v1/video-projects` - 创建视频项目
- `GET /api/v1/video-projects/{id}` - 查询项目详情
- `POST /api/v1/video-projects/{id}/runs` - 提交工作流运行

**状态**: ⚠️ Controller存在，实现待完善

### 4.2 素材上传 API

**端点**:
- `POST /api/v1/assets/upload-url` - 获取预签名上传URL
- `POST /api/v1/assets/{id}/complete` - 确认上传完成

**状态**: ⚠️ 部分实现，缺少完整校验

### 4.3 AI Gateway API

**端点**:
- `POST /api/v1/ai-gateway/text/generate` - 文本生成
- `POST /api/v1/ai-gateway/image/generate` - 图片生成
- `POST /api/v1/ai-gateway/video/generate` - 视频生成

**状态**: ⚠️ Service存在，Adapter为Mock

---

## 5. Phase 0 验收清单

| 任务 | 状态 | 备注 |
|-----|------|------|
| 撤销并轮换已暴露的 Provider Key | ⚠️ **用户操作必需** | 代码已清理 |
| 清理 Git 历史和日志中的敏感值 | ⚠️ **待执行** | 需要 git filter-repo |
| 确定唯一数据库和 Provider 配置方案 | ✅ | `wuyao_vimax` |
| 修复 Java 当前编译错误 | ✅ | BUILD SUCCESS |
| 删除重复依赖和明显的死代码 | ⚠️ 部分完成 | pom.xml有重复okhttp |
| 输出 API/数据库契约基线 | ✅ | 本文档 |

---

## 6. Phase 1 前置任务

在开始 Phase 1 之前，必须完成：

### 6.1 用户侧操作 (关键)

1. **立即撤销泄露的 API Key**:
   - 登录 FluAPI 控制台，撤销2个Key
   - 登录 ToAPIs 控制台，撤销1个Key

2. **重新生成新的 API Key**

3. **配置环境变量**:
   ```bash
   # 添加到 ~/.bashrc 或系统环境变量
   export FLUAPI_IMAGE_KEY=<新Key>
   export FLUAPI_TEXT_KEY=<新Key>
   export TOAPIS_SEEDANCE_KEY=<新Key>
   ```

### 6.2 代码清理任务

1. **清理 Git 历史** (可选但推荐):
   ```bash
   # 使用 git-filter-repo 清理敏感信息
   git filter-repo --path backend/vimax-api/src/main/java/com/wuyao/vimax/config/APIKeyConfig.java --invert-paths
   git filter-repo --replace-text <(echo "sk-1HRjy1gDrU9wF3XoKvKTx9uaEsbniwy1gLbIRnJvp11UFwH4==>REDACTED")
   # 需要force push到所有分支
   ```

2. **修复重复表定义**:
   - 删除 `002_resource_libraries.sql` 中的 `assets`, `knowledge_chunks`
   - 删除 `001_init_schema.sql` 中的 `audit_logs`
   - 或者合并为统一定义

3. **删除 pom.xml 重复依赖**:
   - `okhttp` 依赖出现2次 (line 70-73 和 79-82)

---

## 7. 安全建议

### 7.1 长期安全策略

1. **密钥管理**:
   - 生产环境使用 AWS Secrets Manager / HashiCorp Vault
   - 定期轮换 API Key (建议90天)
   - 为不同环境使用不同的 Key

2. **Git 防护**:
   - 添加 pre-commit hook 检测敏感信息
   - 使用 `.gitignore` 排除所有 `.env*` 文件
   - Code Review 必须检查敏感信息

3. **数据库密码**:
   - `application.yml` 中的数据库密码也应改为环境变量
   - Redis、RabbitMQ 密码同样处理

### 7.2 即时整改

```yaml
# backend/vimax-api/src/main/resources/application.yml
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/wuyao_vimax}
    username: ${DATABASE_USER:wuyao_user}
    password: ${DATABASE_PASSWORD:}  # 必须从环境变量读取
  
  data:
    redis:
      password: ${REDIS_PASSWORD:}
  
  rabbitmq:
    username: ${RABBITMQ_USER:wuyao_admin}
    password: ${RABBITMQ_PASSWORD:}
```

---

## 8. 下一步行动

### Phase 1: 基础设施可运行

**前置条件**: Phase 0 验收清单全部 ✅

**目标**:
- 修复数据库重复表定义
- 实现完整的 SQL 迁移和版本管理
- Docker Compose 启动完整环境
- Outbox + RabbitMQ + Worker 可运行
- 健康检查和基础指标

**预计工作量**: 2-3天

---

## 附录 A：完整环境变量模板

```bash
# 数据库
export DATABASE_URL=jdbc:postgresql://localhost:5432/wuyao_vimax
export DATABASE_USER=wuyao_user
export DATABASE_PASSWORD=<强密码>

# Redis
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=<强密码>

# RabbitMQ
export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USER=wuyao_admin
export RABBITMQ_PASSWORD=<强密码>

# MinIO
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=<访问密钥>
export MINIO_SECRET_KEY=<密钥>
export MINIO_BUCKET=vimax-assets

# AI Provider Keys (撤销旧Key后重新生成)
export FLUAPI_IMAGE_KEY=<新Key>
export FLUAPI_TEXT_KEY=<新Key>
export TOAPIS_SEEDANCE_KEY=<新Key>
```

---

**Phase 0 状态**: 🟡 部分完成，等待用户操作

**关键阻塞项**: 用户必须撤销泄露的 API Key 并提供新 Key
