"""
Platform API 客户端

用于调用 Spring Boot Platform API
"""
import httpx
from typing import Dict, Any, Optional
from loguru import logger
from app.config import settings


class PlatformAPIClient:
    """Platform API 客户端"""

    def __init__(self):
        self.base_url = settings.PLATFORM_API_URL
        self.timeout = 30.0

    async def get_merchant_snapshot(self, snapshot_id: int) -> Optional[Dict[str, Any]]:
        """获取商家快照"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(
                    f"{self.base_url}/merchants/snapshots/{snapshot_id}"
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    logger.info(f"获取商家快照成功: snapshotId={snapshot_id}")
                    return data.get("data")
                else:
                    logger.warning(f"获取商家快照失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 Platform API 失败: {e}")
            return None

    async def get_authorized_assets(self, merchant_id: int) -> list:
        """获取商家授权的资产列表"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.get(
                    f"{self.base_url}/assets",
                    params={"merchantId": merchant_id}
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    assets = data.get("data", [])
                    logger.info(f"获取授权资产成功: merchantId={merchant_id}, count={len(assets)}")
                    return assets
                else:
                    logger.warning(f"获取授权资产失败: {data.get('message')}")
                    return []

        except Exception as e:
            logger.error(f"调用 Platform API 失败: {e}")
            return []

    async def create_asset(self, asset_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """创建资产记录"""
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                response = await client.post(
                    f"{self.base_url}/assets",
                    json=asset_data
                )
                response.raise_for_status()

                data = response.json()
                if data.get("code") == 200:
                    logger.info("创建资产记录成功")
                    return data.get("data")
                else:
                    logger.warning(f"创建资产记录失败: {data.get('message')}")
                    return None

        except Exception as e:
            logger.error(f"调用 Platform API 失败: {e}")
            return None
