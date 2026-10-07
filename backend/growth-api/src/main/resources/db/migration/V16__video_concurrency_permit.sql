-- V16 · 视频工作流并发许可
-- The permit is durable in the workflow row so terminal transitions release it
-- exactly once even when a task is retried or a worker is restarted.
ALTER TABLE video_workflows
    ADD COLUMN video_concurrency_permit_held BOOLEAN NOT NULL DEFAULT FALSE;
