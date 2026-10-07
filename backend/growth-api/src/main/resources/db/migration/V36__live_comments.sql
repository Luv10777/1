-- ============================================================
-- V36 · 直播弹幕与回复记录
--
-- 之前弹幕只在请求里过一遍：命中知识库就合成，没命中就丢掉，工作台的
-- “弹幕流与 AI 回复”没有任何数据可展示，未命中的问题也无从交给模型回答。
--
-- 现在每条弹幕落一行：
--   api    : 文字完全一致的问答直接回复（ANSWERED）；其余写入 ANSWERING，同事务提交任务
--   worker : 文案模型在本场问答里找同义问题，或依据商品资料作答 → ANSWERED / UNANSWERED / FAILED
--
-- 目前唯一的来源是 MOCK（控制台手动输入）。真实抖音弹幕尚未接入。
-- ============================================================

CREATE TABLE live_comments (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES tenants(id),
    session_id        BIGINT       NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,

    provider          VARCHAR(20)  NOT NULL,
    -- 来源侧弹幕 ID 的 SHA-256；同一条弹幕重复送达只处理一次
    external_id       VARCHAR(64)  NOT NULL,
    text              VARCHAR(500) NOT NULL,

    status            VARCHAR(20)  NOT NULL,
    answer            TEXT,
    -- 回答出自哪里：SESSION / PRODUCT_FAQ / PRODUCT / STORE（商家写好的原话），或 AI（模型依据资料组织）
    source            VARCHAR(30),
    -- 是否交给过文案模型；用于限制每场的模型调用频率
    model_used        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- 未回答或失败的原因
    note              VARCHAR(500),
    -- sample:<声音样本 ID> 或 builtin:<系统音色>
    voice             VARCHAR(140) NOT NULL,

    created_by        BIGINT       REFERENCES users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    answered_at       TIMESTAMPTZ,

    CONSTRAINT uk_live_comment_source UNIQUE (session_id, provider, external_id),
    CONSTRAINT ck_live_comment_status CHECK (status IN ('ANSWERING', 'ANSWERED', 'UNANSWERED', 'FAILED'))
);
CREATE INDEX idx_live_comments_feed ON live_comments (tenant_id, session_id, id);

ALTER TABLE live_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE live_comments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON live_comments
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

GRANT SELECT, INSERT, UPDATE, DELETE ON live_comments TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE live_comments_id_seq TO growth_app;
