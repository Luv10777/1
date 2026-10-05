-- V17 · 固定视频供应商首次提交请求
-- 预签名素材 URL 会随生成时间变化；同一幂等键重试时必须复用首次请求体。
ALTER TABLE video_workflows
    ADD COLUMN provider_submit_request JSONB;
