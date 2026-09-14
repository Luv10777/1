"""
AI Gateway 客户端

用于调用 AI Gateway 提交任务
"""
import httpx
from typing import Dict, Any, Optional
from loguru import logger
from app.config import settings


class AIGatewayClient:
    """AI Gateway 客户端"""

    def __init__(self):
        self.base_url = settings.AI_GATEWAY_URL
        self.timeout = 60.0

    async def submit_text_generation(self, generation_task_id: int, prompt: str,
                                     model_capability: str) -> Optional[Dict[str, Any]]:
        """提交文本生成任务"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    f"{self.base_url}/text-generation",
                    json={
                        "generationTaskId": generation_task_id,
                        "prompt": prompt,
                        "modelCapability": model_capability
                    }
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    logger.info(f"文本生成任务已提交: taskId={generation_task_id}")
                    return data.get("data")
                else:
                    logger.warning(f"文本生成提交失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 AI Gateway 失败: {e}")
            return None

    async def submit_image_generation(self, generation_task_id: int, prompt: str,
                                      model_capability: str) -> Optional[Dict[str, Any]]:
        """提交图片生成任务"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    f"{self.base_url}/image-generation",
                    json={
                        "generationTaskId": generation_task_id,
                        "prompt": prompt,
                        "modelCapability": model_capability
                    }
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    logger.info(f"图片生成任务已提交: taskId={generation_task_id}")
                    return data.get("data")
                else:
                    logger.warning(f"图片生成提交失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 AI Gateway 失败: {e}")
            return None

    async def submit_video_generation(self, generation_task_id: int, image_url: str,
                                      prompt: str, model_capability: str) -> Optional[Dict[str, Any]]:
        """提交视频生成任务"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    f"{self.base_url}/video-generation",
                    json={
                        "generationTaskId": generation_task_id,
                        "imageUrl": image_url,
                        "prompt": prompt,
                        "modelCapability": model_capability
                    }
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    logger.info(f"视频生成任务已提交: taskId={generation_task_id}")
                    return data.get("data")
                else:
                    logger.warning(f"视频生成提交失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 AI Gateway 失败: {e}")
            return None

    async def get_job_status(self, job_id: int) -> Optional[Dict[str, Any]]:
        """查询任务状态"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(
                    f"{self.base_url}/jobs/{job_id}"
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    return data.get("data")
                else:
                    logger.warning(f"查询任务状态失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 AI Gateway 失败: {e}")
            return None
