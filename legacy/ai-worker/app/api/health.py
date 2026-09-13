from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "UP",
        "service": "ai-worker",
        "version": "1.0.0"
    }


@router.get("/ready")
async def readiness_check():
    """就绪检查"""
    # TODO: 检查 RabbitMQ 连接状态
    return {
        "status": "READY",
        "rabbitmq": "connected"
    }
