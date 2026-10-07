-- ============================================================
-- V42 · 关店后门店名可以再用
--
-- 门店名原来在整个商户内唯一，连已关闭的门店也算。关闭的门店不再出现在任何地方，
-- 也没有恢复的入口，名字却一直被它占着：再建同名门店只会得到一句"门店名称已存在"。
-- 改为只在营业中的门店之间唯一，和品牌名的规则一致。
-- ============================================================

ALTER TABLE stores DROP CONSTRAINT uk_stores_tenant_name;
CREATE UNIQUE INDEX uk_stores_tenant_name ON stores (tenant_id, name) WHERE status = 'ACTIVE';
