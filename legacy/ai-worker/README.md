# Python AI Worker

梧曜星枢 ViMax AI 视频工作流执行器

## 架构

```
FastAPI (健康检查 API)
    │
RabbitMQ Consumer (后台任务)
    │
WorkflowExecutor (工作流执行器)
    │
    ├─→ 生成创意
    ├─→ 生成脚本
    ├─→ 生成分镜
    ├─→ 生成参考图
    ├─→ 生成镜头视频
    └─→ 合成视频
```

## 环境要求

- Python 3.11+
- RabbitMQ
- FFmpeg (视频合成)

## 安装

```bash
cd backend/ai-worker
python -m venv venv

# Windows
venv\Scripts\activate

# Linux/Mac
source venv/bin/activate

pip install -r requirements.txt
```

## 配置

复制 `.env.example` 到 `.env` 并修改配置：

```bash
cp .env.example .env
```

## 运行

```bash
python main.py
```

服务将在 http://localhost:8001 启动

## API 端点

- `GET /` - 服务信息
- `GET /api/health` - 健康检查
- `GET /api/ready` - 就绪检查

## 工作流任务

### 任务类型

1. `GENERATE_CREATIVE` - 生成创意
2. `GENERATE_SCRIPT` - 生成脚本
3. `GENERATE_STORYBOARD` - 生成分镜
4. `GENERATE_REFERENCE_IMAGES` - 生成参考图
5. `GENERATE_SHOTS` - 生成镜头视频
6. `COMPOSE_VIDEO` - 合成视频

### 消息格式

```json
{
  "task_id": "123",
  "task_type": "GENERATE_CREATIVE",
  "merchant_id": 100,
  "payload": {
    "snapshot_id": 1
  }
}
```

## 开发

### 添加新的工作流活动

在 `app/services/workflow_executor.py` 中添加新方法：

```python
async def your_new_activity(self, task: Dict[str, Any]) -> Dict[str, Any]:
    # 实现逻辑
    return {"task_id": task.get("task_id"), "status": "COMPLETED"}
```

### 测试

```bash
# 安装测试依赖
pip install pytest pytest-asyncio httpx

# 运行测试
pytest
```

## 部署

### Docker

```bash
docker build -t vimax-ai-worker .
docker run -p 8001:8001 vimax-ai-worker
```

### 监控

- 日志输出到控制台
- 使用 loguru 进行结构化日志
- 可集成 Prometheus 指标

## 故障排查

### RabbitMQ 连接失败

检查配置：
- RABBITMQ_HOST
- RABBITMQ_PORT
- RABBITMQ_USER
- RABBITMQ_PASSWORD

### 任务执行超时

调整超时配置或优化任务逻辑

## 许可证

MIT License

---

生成时间: 2026-08-26  
版本: 1.0.0
