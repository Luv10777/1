"""
FFmpeg 视频处理工具

用于视频合成、字幕添加、音轨混合
"""
import subprocess
import os
from typing import List, Optional
from loguru import logger


class FFmpegProcessor:
    """FFmpeg 视频处理器"""

    def __init__(self):
        self.ffmpeg_path = "ffmpeg"

    def compose_video(self, video_clips: List[str], audio_file: Optional[str],
                     output_file: str, subtitle_file: Optional[str] = None) -> bool:
        """
        合成视频

        Args:
            video_clips: 视频片段列表
            audio_file: 音频文件路径
            output_file: 输出文件路径
            subtitle_file: 字幕文件路径（可选）

        Returns:
            是否成功
        """
        try:
            logger.info(f"开始合成视频: {len(video_clips)} 个片段")

            # 创建临时文件列表
            concat_file = "concat_list.txt"
            with open(concat_file, "w") as f:
                for clip in video_clips:
                    f.write(f"file '{clip}'\n")

            # 构建 FFmpeg 命令
            cmd = [
                self.ffmpeg_path,
                "-f", "concat",
                "-safe", "0",
                "-i", concat_file,
            ]

            # 添加音频
            if audio_file:
                cmd.extend(["-i", audio_file])

            # 添加字幕
            if subtitle_file:
                cmd.extend([
                    "-vf", f"subtitles={subtitle_file}"
                ])

            # 输出设置
            cmd.extend([
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-y",  # 覆盖输出文件
                output_file
            ])

            logger.debug(f"FFmpeg 命令: {' '.join(cmd)}")

            # 执行命令
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=300  # 5 分钟超时
            )

            if result.returncode == 0:
                logger.info(f"视频合成成功: {output_file}")
                # 清理临时文件
                if os.path.exists(concat_file):
                    os.remove(concat_file)
                return True
            else:
                logger.error(f"视频合成失败: {result.stderr}")
                return False

        except subprocess.TimeoutExpired:
            logger.error("视频合成超时")
            return False
        except Exception as e:
            logger.error(f"视频合成失败: {e}")
            return False

    def add_watermark(self, input_file: str, watermark_image: str, output_file: str) -> bool:
        """添加水印"""
        try:
            cmd = [
                self.ffmpeg_path,
                "-i", input_file,
                "-i", watermark_image,
                "-filter_complex", "[1:v]scale=100:-1[wm];[0:v][wm]overlay=W-w-10:H-h-10",
                "-c:a", "copy",
                "-y",
                output_file
            ]

            result = subprocess.run(cmd, capture_output=True, timeout=120)

            if result.returncode == 0:
                logger.info(f"水印添加成功: {output_file}")
                return True
            else:
                logger.error(f"水印添加失败: {result.stderr}")
                return False

        except Exception as e:
            logger.error(f"水印添加失败: {e}")
            return False

    def extract_thumbnail(self, video_file: str, output_file: str, timestamp: str = "00:00:01") -> bool:
        """提取缩略图"""
        try:
            cmd = [
                self.ffmpeg_path,
                "-i", video_file,
                "-ss", timestamp,
                "-vframes", "1",
                "-y",
                output_file
            ]

            result = subprocess.run(cmd, capture_output=True, timeout=30)

            if result.returncode == 0:
                logger.info(f"缩略图提取成功: {output_file}")
                return True
            else:
                logger.error(f"缩略图提取失败: {result.stderr}")
                return False

        except Exception as e:
            logger.error(f"缩略图提取失败: {e}")
            return False

    def get_video_info(self, video_file: str) -> dict:
        """获取视频信息"""
        try:
            cmd = [
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                video_file
            ]

            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)

            if result.returncode == 0:
                import json
                info = json.loads(result.stdout)

                video_stream = next((s for s in info.get("streams", []) if s["codec_type"] == "video"), None)
                audio_stream = next((s for s in info.get("streams", []) if s["codec_type"] == "audio"), None)

                return {
                    "duration": float(info.get("format", {}).get("duration", 0)),
                    "width": video_stream.get("width", 0) if video_stream else 0,
                    "height": video_stream.get("height", 0) if video_stream else 0,
                    "has_audio": audio_stream is not None,
                    "file_size": int(info.get("format", {}).get("size", 0))
                }
            else:
                logger.error(f"获取视频信息失败: {result.stderr}")
                return {}

        except Exception as e:
            logger.error(f"获取视频信息失败: {e}")
            return {}
