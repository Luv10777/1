import asyncio
import json
import pika
from loguru import logger
from typing import Dict, Any

from app.config import settings
from app.services.workflow_executor import WorkflowExecutor


class WorkflowConsumer:
    """RabbitMQ 工作流消费者"""

    def __init__(self):
        self.connection = None
        self.channel = None
        self.executor = WorkflowExecutor()
        self.is_running = False

    def connect(self):
        """连接到 RabbitMQ"""
        try:
            credentials = pika.PlainCredentials(
                settings.RABBITMQ_USER,
                settings.RABBITMQ_PASSWORD
            )

            parameters = pika.ConnectionParameters(
                host=settings.RABBITMQ_HOST,
                port=settings.RABBITMQ_PORT,
                virtual_host=settings.RABBITMQ_VHOST,
                credentials=credentials,
                heartbeat=600,
                blocked_connection_timeout=300
            )

            self.connection = pika.BlockingConnection(parameters)
            self.channel = self.connection.channel()

            # 声明队列
            self.channel.queue_declare(
                queue=settings.WORKFLOW_QUEUE,
                durable=True
            )

            logger.info(f"已连接到 RabbitMQ: {settings.RABBITMQ_HOST}:{settings.RABBITMQ_PORT}")
            logger.info(f"监听队列: {settings.WORKFLOW_QUEUE}")

        except Exception as e:
            logger.error(f"连接 RabbitMQ 失败: {e}")
            raise

    def callback(self, ch, method, properties, body):
        """消息回调处理"""
        try:
            # 解析消息
            message = json.loads(body.decode())
            logger.info(f"收到任务: {message.get('task_type')} - {message.get('task_id')}")

            # 执行任务
            result = asyncio.run(self.executor.execute(message))

            # 发送结果
            self.publish_result(result)

            # 确认消息
            ch.basic_ack(delivery_tag=method.delivery_tag)
            logger.info(f"任务完成: {message.get('task_id')}")

        except Exception as e:
            logger.error(f"处理任务失败: {e}")
            # 拒绝消息并重新入队
            ch.basic_nack(delivery_tag=method.delivery_tag, requeue=True)

    def publish_result(self, result: Dict[str, Any]):
        """发布任务结果"""
        try:
            self.channel.basic_publish(
                exchange='',
                routing_key=settings.WORKFLOW_RESULT_QUEUE,
                body=json.dumps(result),
                properties=pika.BasicProperties(
                    delivery_mode=2,  # 持久化
                )
            )
            logger.debug(f"结果已发布: {result.get('task_id')}")
        except Exception as e:
            logger.error(f"发布结果失败: {e}")

    async def start(self):
        """启动消费者"""
        self.is_running = True

        while self.is_running:
            try:
                if not self.connection or self.connection.is_closed:
                    self.connect()

                # 设置 QoS
                self.channel.basic_qos(prefetch_count=1)

                # 开始消费
                self.channel.basic_consume(
                    queue=settings.WORKFLOW_QUEUE,
                    on_message_callback=self.callback
                )

                logger.info("开始消费消息...")
                self.channel.start_consuming()

            except KeyboardInterrupt:
                logger.info("收到中断信号")
                break
            except Exception as e:
                logger.error(f"消费消息出错: {e}")
                await asyncio.sleep(5)  # 等待 5 秒后重试

    async def stop(self):
        """停止消费者"""
        self.is_running = False

        if self.channel and self.channel.is_open:
            self.channel.stop_consuming()
            self.channel.close()

        if self.connection and self.connection.is_open:
            self.connection.close()

        logger.info("消费者已停止")
