# ADR 003: Transactional Outbox 用于 RabbitMQ

**状态**: 已批准  
**日期**: 2026-08-26  
**决策者**: 架构团队  
**影响范围**: 消息可靠性、数据一致性

---

## 背景

梧曜星枢 AI 视频工作流需要在数据库事务成功后可靠地发送 RabbitMQ 消息到 Python Worker。

**核心问题**：双写一致性
```java
// ❌ 错误做法：先提交数据库，再发送消息
workflowStepRepository.save(step);
dbTransaction.commit();                    // 数据库提交成功
rabbitTemplate.send(queue, message);        // 如果这里失败，消息丢失！
```

可能的失败场景：
1. 数据库提交成功，但消息发送前进程崩溃 → **消息丢失**
2. 数据库提交成功，但 RabbitMQ 不可用 → **消息丢失**
3. 消息发送成功，但数据库回滚 → **消息多余**

## 决策

**我们决定使用 Transactional Outbox 模式，确保数据库事务和消息发送的原子性。**

### 核心原理

```
1. 业务事务中，同时写入业务表 + outbox_events 表（同一事务）
2. 独立的 Outbox Publisher 轮询 outbox_events 表
3. 发送消息到 RabbitMQ
4. 标记 outbox_events 为已发送
```

**关键保证**：
- 数据库事务成功 = 消息一定会被发送（最终一致性）
- 消息不会在数据库回滚时发送
- 消息可能重复（需要消费者幂等处理）

---

## 架构设计

### Outbox 表结构

```sql
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,        -- 聚合类型：WORKFLOW_RUN, SHOT 等
    aggregate_id VARCHAR(100) NOT NULL,          -- 聚合ID
    event_type VARCHAR(100) NOT NULL,            -- 事件类型：STEP_READY, SHOT_GENERATED 等
    payload JSONB NOT NULL,                      -- 消息体
    routing_key VARCHAR(200) NOT NULL,           -- RabbitMQ routing key
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,                      -- 发布时间（NULL = 未发布）
    published_by VARCHAR(100),                   -- 发布者实例ID
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    version INT NOT NULL DEFAULT 1               -- 乐观锁
);

CREATE INDEX idx_outbox_events_unpublished ON outbox_events(created_at) 
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_events_tenant ON outbox_events(tenant_id);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
```

### Inbox 表结构（消费者幂等去重）

```sql
CREATE TABLE inbox_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id UUID UNIQUE NOT NULL,             -- 来自 outbox_events.event_id
    consumer_name VARCHAR(100) NOT NULL,         -- 消费者标识
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    processing_error TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    UNIQUE(message_id, consumer_name)
);

CREATE INDEX idx_inbox_messages_unprocessed ON inbox_messages(received_at)
    WHERE processed_at IS NULL;
```

---

## 实现

### 1. 业务代码（在事务中写 Outbox）

```java
@Service
@Transactional
public class WorkflowService {
    
    @Autowired
    private WorkflowStepRepository stepRepository;
    
    @Autowired
    private OutboxEventRepository outboxRepository;
    
    public void markStepReady(Long stepId) {
        // 1. 更新业务状态
        WorkflowStep step = stepRepository.findById(stepId)
            .orElseThrow(() -> new EntityNotFoundException("Step not found"));
        step.setStatus(StepStatus.READY);
        step.setUpdatedAt(Instant.now());
        stepRepository.save(step);
        
        // 2. 在同一事务中写入 Outbox
        OutboxEvent event = OutboxEvent.builder()
            .eventId(UUID.randomUUID())
            .tenantId(step.getTenantId())
            .aggregateType("WORKFLOW_STEP")
            .aggregateId(stepId.toString())
            .eventType("STEP_READY")
            .payload(buildStepReadyPayload(step))
            .routingKey("workflow.step.ready")
            .build();
        outboxRepository.save(event);
        
        // 3. 事务提交（原子性保证：要么都成功，要么都失败）
    }
    
    private JsonNode buildStepReadyPayload(WorkflowStep step) {
        return objectMapper.createObjectNode()
            .put("stepId", step.getId())
            .put("tenantId", step.getTenantId())
            .put("workflowRunId", step.getWorkflowRunId())
            .put("activityType", step.getActivityType())
            .put("inputRef", step.getInputRef());
    }
}
```

### 2. Outbox Publisher（独立线程轮询发布）

```java
@Component
public class OutboxEventPublisher {
    
    @Autowired
    private OutboxEventRepository outboxRepository;
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Autowired
    private TransactionTemplate transactionTemplate;
    
    private final String instanceId = UUID.randomUUID().toString();
    
    @Scheduled(fixedDelay = 1000)  // 每秒轮询
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository
            .findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        
        for (OutboxEvent event : events) {
            try {
                publishEvent(event);
            } catch (Exception e) {
                log.error("Failed to publish event {}: {}", event.getEventId(), e.getMessage());
                handlePublishFailure(event, e);
            }
        }
    }
    
    private void publishEvent(OutboxEvent event) {
        // 1. 发送到 RabbitMQ
        Message message = MessageBuilder
            .withBody(event.getPayload().toString().getBytes())
            .setHeader("event_id", event.getEventId().toString())
            .setHeader("tenant_id", event.getTenantId())
            .setHeader("event_type", event.getEventType())
            .setHeader("created_at", event.getCreatedAt().toEpochMilli())
            .build();
        
        rabbitTemplate.send(
            "workflow.exchange",
            event.getRoutingKey(),
            message
        );
        
        // 2. 标记为已发布（独立事务）
        transactionTemplate.execute(status -> {
            OutboxEvent fresh = outboxRepository.findById(event.getId())
                .orElseThrow();
            fresh.setPublishedAt(Instant.now());
            fresh.setPublishedBy(instanceId);
            return outboxRepository.save(fresh);
        });
        
        log.info("Published event {} to {}", event.getEventId(), event.getRoutingKey());
    }
    
    private void handlePublishFailure(OutboxEvent event, Exception e) {
        transactionTemplate.execute(status -> {
            OutboxEvent fresh = outboxRepository.findById(event.getId())
                .orElseThrow();
            fresh.setRetryCount(fresh.getRetryCount() + 1);
            fresh.setLastError(e.getMessage());
            
            // 指数退避：1分钟、5分钟、30分钟后重试
            if (fresh.getRetryCount() > 10) {
                log.error("Event {} exceeded max retries, marking as dead", event.getEventId());
                fresh.setPublishedAt(Instant.now());  // 标记为已处理，避免无限重试
            }
            return outboxRepository.save(fresh);
        });
    }
}
```

### 3. Worker 消费者（Inbox 幂等去重）

```python
# apps/ai-worker/consumer.py
import asyncio
import json
from datetime import datetime
from sqlalchemy import select, insert
from sqlalchemy.dialects.postgresql import insert as pg_insert

class WorkflowActivityConsumer:
    def __init__(self, db_session, activity_executor):
        self.db = db_session
        self.executor = activity_executor
        self.consumer_name = "ai-worker-instance-1"
    
    async def consume_message(self, message):
        """消费 RabbitMQ 消息"""
        message_id = message.headers.get("event_id")
        
        # 1. 幂等检查：是否已处理
        if await self.is_already_processed(message_id):
            log.info(f"Message {message_id} already processed, skipping")
            message.ack()
            return
        
        # 2. 写入 Inbox（去重）
        inbox_id = await self.save_to_inbox(message_id)
        if not inbox_id:
            log.warning(f"Message {message_id} duplicate, skipping")
            message.ack()
            return
        
        # 3. 执行业务逻辑
        try:
            payload = json.loads(message.body)
            await self.execute_activity(payload)
            
            # 4. 标记为已处理
            await self.mark_processed(inbox_id)
            message.ack()
            
        except Exception as e:
            log.error(f"Failed to process message {message_id}: {e}")
            await self.mark_failed(inbox_id, str(e))
            message.nack(requeue=True)  # 重新入队
    
    async def is_already_processed(self, message_id):
        """检查是否已处理"""
        result = await self.db.execute(
            select(inbox_messages.c.processed_at)
            .where(inbox_messages.c.message_id == message_id)
            .where(inbox_messages.c.consumer_name == self.consumer_name)
        )
        row = result.first()
        return row and row.processed_at is not None
    
    async def save_to_inbox(self, message_id):
        """写入 Inbox（处理并发重复）"""
        try:
            result = await self.db.execute(
                insert(inbox_messages)
                .values(
                    message_id=message_id,
                    consumer_name=self.consumer_name,
                    received_at=datetime.utcnow()
                )
                .returning(inbox_messages.c.id)
                .on_conflict_do_nothing()  # PostgreSQL 特性：重复则忽略
            )
            await self.db.commit()
            row = result.first()
            return row.id if row else None
        except Exception as e:
            await self.db.rollback()
            log.error(f"Failed to save inbox: {e}")
            return None
    
    async def mark_processed(self, inbox_id):
        """标记为已处理"""
        await self.db.execute(
            update(inbox_messages)
            .where(inbox_messages.c.id == inbox_id)
            .values(processed_at=datetime.utcnow())
        )
        await self.db.commit()
    
    async def mark_failed(self, inbox_id, error):
        """标记为失败"""
        await self.db.execute(
            update(inbox_messages)
            .where(inbox_messages.c.id == inbox_id)
            .values(
                processing_error=error,
                retry_count=inbox_messages.c.retry_count + 1
            )
        )
        await self.db.commit()
```

---

## 后果

### 优势

✅ **数据一致性保证**
- 数据库事务成功 = 消息一定会被发送（最终一致性）
- 不会出现"数据库成功但消息丢失"

✅ **消息不会丢失**
- Outbox 表持久化在数据库中
- 即使 RabbitMQ 暂时不可用，消息也不会丢失
- 进程崩溃后重启，未发送的消息会继续发送

✅ **消费者幂等**
- Inbox 表去重，同一消息多次接收只处理一次
- 支持消息重放（人工运维）

✅ **可观测性**
- 所有消息有完整生命周期追踪
- 可以查询哪些消息未发送、发送失败原因
- 可以查询哪些消息未被消费

### 劣势与缓解

⚠️ **延迟增加**
- Outbox 轮询间隔 1秒，最坏情况延迟 1秒
- **缓解**: 对于实时性要求极高的场景，可以缩短轮询间隔（500ms）

⚠️ **数据库负载**
- 每条消息多写一次 outbox_events 表
- **缓解**: outbox_events 索引优化，定期清理已发送的旧记录

⚠️ **消息可能重复**
- Outbox Publisher 发送成功但标记失败时，会重复发送
- **缓解**: 消费者必须幂等（Inbox 去重）

### 风险

🟡 **Outbox Publisher 单点**
- 如果 Publisher 线程卡死，消息积压
- **应对**: 监控 outbox_events 表中未发送消息数量，告警

🟡 **数据库磁盘空间**
- 高并发时 outbox_events 表快速增长
- **应对**: 定期清理已发送超过 7 天的记录

---

## 替代方案

### 方案A: 两阶段提交（2PC）（已拒绝）

**优势**: 强一致性  
**劣势**: 
- 性能差（需要协调者）
- RabbitMQ 不支持标准 2PC
- 实现复杂

**决策**: 拒绝，Outbox 模式更适合异步场景

### 方案B: 直接发送消息（已拒绝）

**劣势**: 
- 数据库提交成功但消息丢失风险
- 无法保证一致性

**决策**: 拒绝，不符合企业级可靠性要求

### 方案C: Change Data Capture (CDC)（已评估）

**优势**: 无需业务代码写 Outbox  
**劣势**: 
- 需要 Debezium 等组件
- 增加架构复杂度
- 需要解析数据库 WAL

**决策**: 延后，先用 Outbox 模式，后续可评估 CDC

---

## 相关决策

- [ADR 001: 采用 ViMax Core 并进行无状态改造](./001-adopt-vimax-core-with-stateless-adaptation.md)
- [ADR 002: 统一 AI Gateway](./002-unified-ai-gateway-for-fluapi-toapis.md)

---

**批准人**: 技术总监  
**生效日期**: 2026-08-26
