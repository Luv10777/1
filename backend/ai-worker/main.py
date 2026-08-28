import asyncio
import signal
from loguru import logger
from fastapi import FastAPI
from contextlib import asynccontextmanager

from app.config import settings
from app.consumers.workflow_consumer import WorkflowConsumer
from app.api import health

# 全局变量存储消费者
consumer_instance = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    global consumer_instance

    logger.info("AI Worker 启动中...")
    logger.info(f"环境: {settings.ENVIRONMENT}")
    logger.info(f"RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}")

    # 启动 RabbitMQ 消费者
    consumer_instance = WorkflowConsumer()
    consumer_task = asyncio.create_task(consumer_instance.start())

    logger.info("AI Worker 启动完成")

    yield

    # 关闭时清理
    logger.info("AI Worker 关闭中...")
    if consumer_instance:
        await consumer_instance.stop()

    logger.info("AI Worker 已关闭")


# 创建 FastAPI 应用
app = FastAPI(
    title="ViMax AI Worker",
    description="AI 视频工作流执行器",
    version="1.0.0",
    lifespan=lifespan
)

# 注册路由
app.include_router(health.router, prefix="/api", tags=["health"])


@app.get("/")
async def root():
    """根路径"""
    return {
        "service": "ViMax AI Worker",
        "version": "1.0.0",
        "status": "running"
    }


if __name__ == "__main__":
    import uvicorn

    logger.info("启动 AI Worker 服务...")

    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8001,
        reload=settings.ENVIRONMENT == "development"
    )
