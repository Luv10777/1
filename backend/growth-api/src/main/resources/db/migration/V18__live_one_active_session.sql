-- ============================================================
-- V18 · 一个门店同一时间只有一场进行中的直播
--
-- 之前开始新的一场不会结束旧的，同一门店能同时挂着好几场“运行中”，
-- 页面也分不清该打开哪一场。从这里起由数据库保证最多一场。
--
-- 已有数据里可能已经有多场：每个门店保留最近开始的一场，其余记为已结束，
-- 并停掉它们的自动讲解、作废还没播的内容。
-- ============================================================

-- 迁移账户是表的属主；临时取消“属主也受行级安全约束”，否则下面的更新一行都改不到。
ALTER TABLE live_sessions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE live_script_states NO FORCE ROW LEVEL SECURITY;
ALTER TABLE live_speech_items NO FORCE ROW LEVEL SECURITY;

WITH keep AS (
    SELECT DISTINCT ON (store_id) id
      FROM live_sessions
     WHERE status IN ('LIVE', 'PAUSED')
     ORDER BY store_id, started_at DESC NULLS LAST, id DESC
), ended AS (
    UPDATE live_sessions
       SET status = 'ENDED', ended_at = now(), updated_at = now()
     WHERE status IN ('LIVE', 'PAUSED') AND id NOT IN (SELECT id FROM keep)
    RETURNING id
), stopped AS (
    UPDATE live_script_states
       SET enabled = FALSE, updated_at = now()
     WHERE session_id IN (SELECT id FROM ended)
    RETURNING session_id
)
UPDATE live_speech_items
   SET status = 'DISCARDED', finished_at = now()
 WHERE session_id IN (SELECT id FROM ended)
   AND status IN ('GENERATING', 'PENDING', 'READY');

ALTER TABLE live_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE live_script_states FORCE ROW LEVEL SECURITY;
ALTER TABLE live_speech_items FORCE ROW LEVEL SECURITY;

CREATE UNIQUE INDEX uk_live_sessions_one_active
    ON live_sessions (store_id) WHERE status IN ('LIVE', 'PAUSED');
