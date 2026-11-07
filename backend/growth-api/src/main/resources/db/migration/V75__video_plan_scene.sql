-- 视频方案记下出方案时的创作场景（空即自由创作），「换一批」沿用、灵感记录恢复时带回。
-- 视频生成记录不加列：场景和方案编号随请求 JSON 存在 video_workflows.request 里。
ALTER TABLE video_plans ADD COLUMN scene_key VARCHAR(40);
