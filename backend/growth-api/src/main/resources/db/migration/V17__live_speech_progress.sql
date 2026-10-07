-- ============================================================
-- V17 · 播报条目的排队与处理时间
--
-- 一条播报迟迟没有声音，可能是没有 worker 在取任务，也可能是 worker 已经在处理、
-- 只是文案模型或语音合成很慢。只看创建时间分不清这两种情况，提示就会说错原因。
--
--   queued_at  : 最近一次开始等待 worker 的时间（创建时，以及话术写好、等待合成时）
--   started_at : worker 最近一次开始处理的时间；等待期间为空
-- ============================================================

ALTER TABLE live_speech_items
    ADD COLUMN queued_at  TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ;
