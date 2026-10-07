-- ============================================================
-- V22 · 需要人工处理的弹幕
--
-- 投诉、说吃了不舒服、要求退款、问主播是不是真人——这些不该由 AI 回答，
-- 但也不能像闲聊一样悄悄跳过。单独记一种状态，工作台上标出来让人处理。
-- ============================================================

ALTER TABLE live_comments DROP CONSTRAINT ck_live_comment_status;
ALTER TABLE live_comments ADD CONSTRAINT ck_live_comment_status
    CHECK (status IN ('ANSWERING', 'ANSWERED', 'UNANSWERED', 'FAILED', 'SKIPPED', 'ATTENTION'));
