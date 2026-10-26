-- V66 · 商城好物「入驻商户」栏：公开列出已入驻商户名下营业中的门店（含还没上架商品的门店）。
--
-- 公开的 stores 策略只放出有在售商品的门店；merchant_applications 里有联系人电话、
-- 信用代码，不能加公开读取策略。所以用 SECURITY DEFINER 函数，只交出门店编号和名称。
-- 有在售实物商品的门店排在前面，按最近一次商品更新倒序；total 是全部符合条件的门店数。
CREATE FUNCTION public_alliance_stores(p_limit INTEGER, p_offset BIGINT)
RETURNS TABLE (store_id BIGINT, store_name VARCHAR, total BIGINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT s.id, s.name, count(*) OVER ()
    FROM stores s
    LEFT JOIN LATERAL (
        SELECT max(p.updated_at) AS latest
        FROM products p
        WHERE p.store_id = s.id AND p.tenant_id = s.tenant_id
          AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
          AND p.online_status = 'PUBLISHED' AND p.type = 'PHYSICAL'
    ) listed ON true
    WHERE s.status = 'ACTIVE'
      AND EXISTS (
          SELECT 1 FROM merchant_applications a
          WHERE a.tenant_id = s.tenant_id AND a.status = 'APPROVED'
      )
    ORDER BY listed.latest DESC NULLS LAST, s.id DESC
    LIMIT p_limit OFFSET p_offset
$$;

REVOKE ALL ON FUNCTION public_alliance_stores(INTEGER, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public_alliance_stores(INTEGER, BIGINT) TO growth_app;
