"""
视频质量检查服务
"""
from typing import Dict, Any, List
from loguru import logger
from app.services.ffmpeg_processor import FFmpegProcessor


class QualityChecker:
    """视频质量检查器"""

    def __init__(self):
        self.ffmpeg = FFmpegProcessor()
        self.min_duration = 3.0  # 最小时长 3 秒
        self.max_duration = 60.0  # 最大时长 60 秒
        self.min_resolution = (640, 480)  # 最小分辨率

    def check_video_quality(self, video_path: str) -> Dict[str, Any]:
        """
        检查视频质量

        Returns:
            {
                "passed": bool,
                "issues": List[str],
                "metrics": dict
            }
        """
        issues = []

        # 获取视频信息
        info = self.ffmpeg.get_video_info(video_path)

        if not info:
            return {
                "passed": False,
                "issues": ["无法读取视频信息"],
                "metrics": {}
            }

        # 检查时长
        duration = info.get("duration", 0)
        if duration < self.min_duration:
            issues.append(f"视频时长过短: {duration:.1f}s < {self.min_duration}s")
        if duration > self.max_duration:
            issues.append(f"视频时长过长: {duration:.1f}s > {self.max_duration}s")

        # 检查分辨率
        width = info.get("width", 0)
        height = info.get("height", 0)
        if width < self.min_resolution[0] or height < self.min_resolution[1]:
            issues.append(f"分辨率过低: {width}x{height}")

        # 检查是否有音频
        if not info.get("has_audio"):
            issues.append("视频缺少音轨")

        # 检查文件大小
        file_size_mb = info.get("file_size", 0) / 1024 / 1024
        if file_size_mb > 100:
            issues.append(f"文件过大: {file_size_mb:.1f}MB")

        passed = len(issues) == 0

        logger.info(f"视频质检 {'通过' if passed else '未通过'}: {video_path}")
        if issues:
            logger.warning(f"质检问题: {', '.join(issues)}")

        return {
            "passed": passed,
            "issues": issues,
            "metrics": {
                "duration": duration,
                "width": width,
                "height": height,
                "has_audio": info.get("has_audio"),
                "file_size_mb": file_size_mb
            }
        }

    def check_image_quality(self, image_path: str) -> Dict[str, Any]:
        """检查图片质量"""
        from PIL import Image
        import os

        issues = []

        try:
            # 打开图片
            img = Image.open(image_path)
            width, height = img.size
            file_size_mb = os.path.getsize(image_path) / 1024 / 1024

            # 检查分辨率
            if width < 512 or height < 512:
                issues.append(f"分辨率过低: {width}x{height}")

            # 检查文件大小
            if file_size_mb > 10:
                issues.append(f"文件过大: {file_size_mb:.1f}MB")

            # 检查格式
            if img.format not in ['JPEG', 'PNG', 'WEBP']:
                issues.append(f"不支持的格式: {img.format}")

            passed = len(issues) == 0

            return {
                "passed": passed,
                "issues": issues,
                "metrics": {
                    "width": width,
                    "height": height,
                    "format": img.format,
                    "file_size_mb": file_size_mb
                }
            }

        except Exception as e:
            logger.error(f"图片质检失败: {e}")
            return {
                "passed": False,
                "issues": [f"无法读取图片: {str(e)}"],
                "metrics": {}
            }
