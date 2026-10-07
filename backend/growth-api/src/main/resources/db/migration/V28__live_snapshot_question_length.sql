-- 商品问答支持 1000 字问题；直播快照必须完整保留，不能在开播时截断或失败。
ALTER TABLE live_session_knowledge_snapshots
    ALTER COLUMN question TYPE VARCHAR(1000);
