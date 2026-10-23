-- V60 · 知识库简化
-- 问答集只有「启用 / 归档」，问答只有「启用 / 停用」：两者都启用，开播时就会读取。
-- 去掉单独的发布步骤，以及从没被用上的商品专属问答、命中次数、修正次数和版本号。
-- 迁移只保证原来不生效的问答迁移后仍不生效。

-- 先去掉旧的取值限制，改完数据再加上新的。
ALTER TABLE knowledge_sets DROP CONSTRAINT ck_knowledge_sets_status;
ALTER TABLE knowledge_entries DROP CONSTRAINT ck_knowledge_entries_status;
ALTER TABLE knowledge_entries DROP CONSTRAINT ck_knowledge_entries_source;

-- 旧版的「删除」只是把问答标成 DISABLED，在页面上看不到。新版 DISABLED 表示「停用」，会显示出来，
-- 所以未归档集里的这些行按用户原意真正删除。已归档集里的 DISABLED 可能是归档时停掉的，保留为停用。
DELETE FROM knowledge_entries e
USING knowledge_sets s
WHERE e.knowledge_set_id = s.id AND s.status <> 'ARCHIVED' AND e.status = 'DISABLED';

-- 商品专属问答界面上写不进来；万一有通过接口写进来的，去掉商品后会变成全店通用，答案就错了。
-- 商品问答统一放在商品库。
DELETE FROM knowledge_entries WHERE scope = 'PRODUCT';

UPDATE knowledge_entries SET status = 'DISABLED' WHERE status = 'DRAFT';

-- 未发布或已停用的集里，问答原来不生效；集改为启用后，把里面的问答停用，保持原效果。
UPDATE knowledge_entries e SET status = 'DISABLED'
FROM knowledge_sets s
WHERE e.knowledge_set_id = s.id AND s.status IN ('DRAFT', 'DISABLED') AND e.status = 'ACTIVE';

UPDATE knowledge_sets SET status = 'ACTIVE' WHERE status IN ('DRAFT', 'PUBLISHED', 'DISABLED');

UPDATE knowledge_entries SET source = CASE source WHEN 'LIVE_DRAFT' THEN 'LIVE' ELSE 'MANUAL' END;

ALTER TABLE knowledge_sets ADD CONSTRAINT ck_knowledge_sets_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));
ALTER TABLE knowledge_sets ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE knowledge_sets DROP COLUMN current_version, DROP COLUMN published_at;

ALTER TABLE knowledge_entries ADD CONSTRAINT ck_knowledge_entries_status CHECK (status IN ('ACTIVE', 'DISABLED'));
ALTER TABLE knowledge_entries ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE knowledge_entries ADD CONSTRAINT ck_knowledge_entries_source CHECK (source IN ('MANUAL', 'LIVE'));
ALTER TABLE knowledge_entries DROP CONSTRAINT ck_knowledge_entries_product_scope;
ALTER TABLE knowledge_entries DROP CONSTRAINT ck_knowledge_entries_scope;
ALTER TABLE knowledge_entries
    DROP COLUMN product_id,
    DROP COLUMN scope,
    DROP COLUMN hits,
    DROP COLUMN manual_corrections,
    DROP COLUMN version;

-- 场次上记的「知识版本」取自集的发布版本，发布没有了，这个数也就没有意义了。
ALTER TABLE live_sessions DROP COLUMN knowledge_version;
