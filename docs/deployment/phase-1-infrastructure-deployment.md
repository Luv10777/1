# 阶段 1 完整部署文档

> **阶段 1：企业级基础设施**  
> **完成时间**: 2026-08-26  
> **状态**: 待验证

---

## 📋 已完成内容

### 1. 数据库表结构（PostgreSQL）

已创建 **40+ 张表**，分为以下模块：

#### 商家事实快照模块
- `merchant_facts` - 商家事实表（地址、价格、套餐等）
- `merchant_fact_snapshots` - 不可变快照表
- `merchant_fact_snapshot_items` - 快照明细表

#### 工作流核心模块
- `video_projects` - 视频项目表
- `workflow_definitions` - 工作流定义表
- `workflow_runs` - 工作流运行表（工作流实例）
- `workflow_steps` - 工作流步骤表
- `workflow_step_attempts` - 步骤尝试表（记录每次执行）

#### 创意、脚本、分镜模块
- `creative_variants` - 创意变体表
- `video_scripts` - 视频脚本表
- `storyboards` - 分镜表
- `shots` - 镜头表
- `shot_revisions` - 镜头版本表（支持单镜头重试）

#### Prompt 和生成任务模块
- `prompt_artifacts` - Prompt 制品表
- `generation_tasks` - 生成任务表（幂等键管理）
- `provider_jobs` - 供应商任务表（追踪 FluAPI/ToAPIs）

#### 质检和审核模块
- `quality_reports` - 质量报告表
- `review_records` - 人工审核记录表

#### 资产管理模块
- `assets` - 资产表（统一管理所有文件）
- `asset_authorizations` - 资产授权表（商家授权素材）

#### 作品库模块
- `work_assets` - 作品表（最终成品）

#### 成本和额度模块
- `cost_ledger` - 成本账本表（只追加，不修改）
- `quota_reservations` - 额度预占表

#### Transactional Outbox/Inbox 模块
- `outbox_events` - Outbox 事件表（确保数据库和消息队列一致性）
- `inbox_messages` - Inbox 消息表（消费者幂等去重）

#### 审计和知识库模块
- `audit_logs` - 审计日志表（只追加）
- `knowledge_documents` - 知识文档表
- `knowledge_chunks` - 知识块表（向量化）

#### 视图和触发器
- `v_workflow_run_summary` - 工作流运行摘要视图
- `v_cost_summary` - 成本统计视图
- `update_updated_at_column()` - 自动更新 updated_at 触发器

### 2. Docker Compose 完整配置

已配置 **5 个基础设施服务**：

```yaml
services:
  - postgres:14-alpine       # PostgreSQL 数据库
  - redis:7-alpine           # Redis 缓存和分布式锁
  - rabbitmq:3.13-management # RabbitMQ 消息队列
  - minio:latest             # MinIO 对象存储（S3 兼容）
  - minio-init               # MinIO 初始化（自动创建桶）
```

### 3. RabbitMQ 队列拓扑

已配置 **8 个业务队列 + 死信队列**：

| 队列名称 | 用途 | 死信配置 |
|---------|------|---------|
| `workflow.planning` | 创意/脚本/分镜规划 | ✅ |
| `workflow.reference-generation` | 参考图生成 | ✅ |
| `workflow.reference-selection` | 参考图筛选 | ✅ |
| `workflow.video-submission` | 视频任务提交 | ✅ |
| `workflow.video-monitoring` | 视频任务监控 | ✅ |
| `workflow.quality` | 质量检查 | ✅ |
| `workflow.composition` | 视频合成 | ✅ |
| `workflow.finalization` | 最终发布 | ✅ |

每个队列配置：
- ✅ Durable（持久化）
- ✅ Dead Letter Exchange（死信交换机）
- ✅ Message TTL（消息过期时间）
- ✅ Retry Queue（重试队列，60秒延迟）

---

## 🚀 本地部署步骤

### 前置条件

- Docker Desktop（Windows）或 Docker + Docker Compose（Linux）
- 至少 8GB 可用内存
- 至少 20GB 可用磁盘空间

### 步骤 1：配置环境变量

```bash
cd C:\Users\Administrator\梧曜AI\infra\compose
cp .env.example .env
```

编辑 `.env` 文件，填写密码：
```bash
POSTGRES_PASSWORD=your_strong_password_here
REDIS_PASSWORD=your_redis_password_here
RABBITMQ_PASSWORD=your_rabbitmq_password_here
MINIO_ROOT_PASSWORD=your_minio_password_here
JWT_SECRET=your_jwt_secret_min_64_chars_here
```

### 步骤 2：启动基础设施

```bash
cd C:\Users\Administrator\梧曜AI\infra\compose
docker-compose up -d
```

### 步骤 3：验证服务启动

```bash
# 检查所有服务状态
docker-compose ps

# 预期输出（所有服务都是 healthy 或 running）：
# NAME                 STATUS              PORTS
# wuyao-postgres       Up (healthy)        0.0.0.0:5432->5432/tcp
# wuyao-redis          Up (healthy)        0.0.0.0:6379->6379/tcp
# wuyao-rabbitmq       Up (healthy)        0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
# wuyao-minio          Up (healthy)        0.0.0.0:9000->9000/tcp, 0.0.0.0:9001->9001/tcp
```

### 步骤 4：访问管理界面

| 服务 | 管理地址 | 默认账号 |
|------|---------|---------|
| **PostgreSQL** | localhost:5432 | wuyao_user / (你的密码) |
| **Redis** | localhost:6379 | (你的密码) |
| **RabbitMQ 管理** | http://localhost:15672 | wuyao_admin / (你的密码) |
| **MinIO 控制台** | http://localhost:9001 | wuyao_minio_admin / (你的密码) |

### 步骤 5：验证数据库表

```bash
# 连接到 PostgreSQL
docker exec -it wuyao-postgres psql -U wuyao_user -d wuyao_nexus

# 查看所有表
\dt

# 验证工作流定义表
SELECT * FROM workflow_definitions;

# 退出
\q
```

### 步骤 6：验证 RabbitMQ 队列

1. 访问 http://localhost:15672
2. 登录（wuyao_admin / 你的密码）
3. 点击 "Queues" 标签
4. 确认看到 8 个工作流队列 + 死信队列

### 步骤 7：验证 MinIO 桶

1. 访问 http://localhost:9001
2. 登录（wuyao_minio_admin / 你的密码）
3. 确认看到以下桶：
   - `wuyao-assets` - 永久资产
   - `wuyao-temp` - 临时文件（7天自动清理）
   - `wuyao-backups` - 备份

---

## 🧪 验证测试

### 测试 1：数据库连接和表创建

```bash
cd C:\Users\Administrator\梧曜AI
# 运行验证脚本（下一步创建）
node infra/scripts/verify-database.js
```

### 测试 2：Redis 连接和限流

```bash
# 测试 Redis 连接
docker exec -it wuyao-redis redis-cli -a your_redis_password_here ping
# 预期输出：PONG

# 测试设置和获取
docker exec -it wuyao-redis redis-cli -a your_redis_password_here SET test_key "hello"
docker exec -it wuyao-redis redis-cli -a your_redis_password_here GET test_key
# 预期输出：hello
```

### 测试 3：RabbitMQ 消息发送

```bash
# 使用 Management API 测试
curl -u wuyao_admin:your_rabbitmq_password_here \
  -X POST http://localhost:15672/api/exchanges/wuyao/workflow.exchange/publish \
  -H "Content-Type: application/json" \
  -d '{
    "properties": {},
    "routing_key": "workflow.planning",
    "payload": "{\"test\": true}",
    "payload_encoding": "string"
  }'
```

### 测试 4：MinIO 上传文件

```bash
# 使用 MinIO Client
docker run --rm --network infra_wuyao-network \
  -v C:/Users/Administrator/Desktop:/data \
  minio/mc:latest \
  mc alias set wuyao http://minio:9000 wuyao_minio_admin your_minio_password_here

docker run --rm --network infra_wuyao-network \
  -v C:/Users/Administrator/Desktop:/data \
  minio/mc:latest \
  mc cp /data/test.txt wuyao/wuyao-temp/
```

---

## 📊 服务端口映射

| 服务 | 容器端口 | 主机端口 | 用途 |
|------|---------|---------|------|
| PostgreSQL | 5432 | 5432 | 数据库连接 |
| Redis | 6379 | 6379 | 缓存/锁连接 |
| RabbitMQ AMQP | 5672 | 5672 | 消息队列 |
| RabbitMQ Management | 15672 | 15672 | Web 管理界面 |
| MinIO S3 API | 9000 | 9000 | 对象存储 API |
| MinIO Console | 9001 | 9001 | Web 控制台 |

---

## 🔧 故障排查

### 问题 1：PostgreSQL 启动失败

**症状**: `wuyao-postgres` 容器不断重启

**解决**:
```bash
# 查看日志
docker logs wuyao-postgres

# 常见原因：
# 1. 数据卷损坏 - 删除并重建
docker-compose down -v
docker-compose up -d

# 2. 端口占用 - 修改 docker-compose.yml 中的端口映射
```

### 问题 2：RabbitMQ 队列未创建

**症状**: Management UI 中看不到队列

**解决**:
```bash
# 检查 definitions.json 是否正确挂载
docker exec -it wuyao-rabbitmq cat /etc/rabbitmq/definitions.json

# 手动触发加载
docker exec -it wuyao-rabbitmq rabbitmqctl import_definitions /etc/rabbitmq/definitions.json
```

### 问题 3：MinIO 桶未初始化

**症状**: `wuyao-assets` 桶不存在

**解决**:
```bash
# 重新运行初始化
docker-compose up minio-init
```

### 问题 4：网络连接问题

**症状**: 服务之间无法通信

**解决**:
```bash
# 检查 Docker 网络
docker network ls
docker network inspect infra_wuyao-network

# 重建网络
docker-compose down
docker-compose up -d
```

---

## 📝 下一步

完成本地验证后，进入**阶段 2：商家快照与素材底座**：
1. 商家事实 CRUD API
2. 商家事实快照生成逻辑
3. 素材上传和授权管理
4. 预签名 URL 生成

---

## 🗑️ 清理和重置

### 停止所有服务

```bash
docker-compose down
```

### 停止并删除数据卷（⚠️ 会删除所有数据）

```bash
docker-compose down -v
```

### 完全清理（包括镜像）

```bash
docker-compose down -v --rmi all
```

---

**部署文档完成时间**: 2026-08-26  
**维护者**: Claude Code (Opus 5)
