-- ============================================================
-- V21 · 弹幕先判断要不要回，并记下哪些问题没答上
--
-- 之前每条弹幕都当作提问处理，一句夸奖也会被硬套到某条问答上。现在模型先判断
-- 这条弹幕是不是在提问：
--
--   SKIPPED       : 不是提问（夸奖、闲聊、与本场无关），没有回复
--   knowledge_gap : 是提问，但本场资料答不上。复盘时列给商家，补成问答
-- ============================================================

ALTER TABLE live_comments DROP CONSTRAINT ck_live_comment_status;
ALTER TABLE live_comments ADD CONSTRAINT ck_live_comment_status
    CHECK (status IN ('ANSWERING', 'ANSWERED', 'UNANSWERED', 'FAILED', 'SKIPPED'));
ALTER TABLE live_comments ADD COLUMN knowledge_gap BOOLEAN NOT NULL DEFAULT FALSE;

-- 迁移账户是表的属主；临时取消“属主也受行级安全约束”，否则下面的更新一行都改不到。
ALTER TABLE live_comments NO FORCE ROW LEVEL SECURITY;
-- 已有记录里因为资料不足或回答没通过校验而没回的，同样算没答上的问题。
UPDATE live_comments SET knowledge_gap = TRUE
 WHERE status = 'UNANSWERED' AND (note LIKE '本场资料里没有相关内容%' OR note LIKE 'AI 回答未通过校验%');
ALTER TABLE live_comments FORCE ROW LEVEL SECURITY;

CREATE INDEX idx_live_comments_gap ON live_comments (tenant_id, session_id) WHERE knowledge_gap;
