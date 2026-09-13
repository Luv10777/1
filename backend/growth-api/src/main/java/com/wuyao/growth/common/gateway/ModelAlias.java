package com.wuyao.growth.common.gateway;

/**
 * 契约 8：业务代码只认能力别名，永远不出现供应商名字。
 *
 * 换供应商、比价、A/B、某家挂了自动切——全在网关一层解决，
 * 业务模块一行都不用改。
 *
 * 这套别名沿用前端 src/domain/creative.js 里已经定好的那套，保持一致。
 */
public enum ModelAlias {
    TEXT_PLANNER,     // 意图理解、方案规划
    TEXT_WRITER,      // 文案撰写、仿写、重写
    TEXT_REVIEWER,    // 内容审核、事实核对
    VISION_ANALYZER,  // 视频反推、图片理解
    IMAGE_PRIMARY,    // 海报、产品套图
    VIDEO_DRAFT,      // 低成本预览视频
    VIDEO_PRIMARY,    // 成片视频
    TTS_PRIMARY,      // 文本转语音
    VOICE_CLONE,      // 声音克隆
    AVATAR_PRIMARY    // 数字人形象
}
