from pydantic_settings import BaseSettings
from typing import Optional


class Settings(BaseSettings):
    """应用配置"""

    # 环境
    ENVIRONMENT: str = "development"

    # 服务配置
    SERVICE_NAME: str = "ai-worker"
    SERVICE_PORT: int = 8001

    # RabbitMQ 配置
    RABBITMQ_HOST: str = "localhost"
    RABBITMQ_PORT: int = 5672
    RABBITMQ_USER: str = "wuyao_admin"
    RABBITMQ_PASSWORD: str = "wuyao_rabbitmq_2026"
    RABBITMQ_VHOST: str = "/"

    # 队列配置
    WORKFLOW_QUEUE: str = "vimax.workflow.tasks"
    WORKFLOW_RESULT_QUEUE: str = "vimax.workflow.results"

    # Platform API 配置
    PLATFORM_API_URL: str = "http://localhost:8080/api/v1"

    # AI Gateway 配置
    AI_GATEWAY_URL: str = "http://localhost:8080/api/v1/ai-gateway"

    # 日志配置
    LOG_LEVEL: str = "INFO"

    class Config:
        env_file = ".env"
        case_sensitive = True


settings = Settings()
