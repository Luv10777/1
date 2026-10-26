-- V67 · 商城好物「入驻商户」栏改为按入驻商户列出，和管理后台「已入驻商家」一一对应。
--
-- 每个入驻审核通过的商户一行，名称取最近一次审核通过的入驻申请里的商户名。
-- merchant_applications 有联系人电话、信用代码，仍不加公开读取策略，只交出
-- 申请编号、租户编号（服务端取商品用，不对外返回）和商户名。
-- 有在售实物商品的商户排在前面，按最近一次商品更新倒序；total 是全部入驻商户数。
-- V66 的 public_alliance_stores 暂不删除：发布失败回滚到上一版时它还要用。
CREATE FUNCTION public_alliance_merchants(p_limit INTEGER, p_offset BIGINT)
RETURNS TABLE (application_id BIGINT, tenant_id BIGINT, merchant_name VARCHAR, total BIGINT)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT approved.id, approved.tenant_id, approved.merchant_name, count(*) OVER ()
    FROM (
        SELECT DISTINCT ON (a.tenant_id) a.id, a.tenant_id, a.merchant_name
        FROM merchant_applications a
        WHERE a.status = 'APPROVED'
        ORDER BY a.tenant_id, a.created_at DESC, a.id DESC
    ) approved
    LEFT JOIN LATERAL (
        SELECT max(p.updated_at) AS latest
        FROM products p
        JOIN stores s ON s.id = p.store_id AND s.tenant_id = p.tenant_id AND s.status = 'ACTIVE'
        WHERE p.tenant_id = approved.tenant_id
          AND p.status = 'ACTIVE' AND p.deleted_at IS NULL
          AND p.online_status = 'PUBLISHED' AND p.type = 'PHYSICAL'
    ) listed ON true
    ORDER BY listed.latest DESC NULLS LAST, approved.id DESC
    LIMIT p_limit OFFSET p_offset
$$;

REVOKE ALL ON FUNCTION public_alliance_merchants(INTEGER, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public_alliance_merchants(INTEGER, BIGINT) TO growth_app;
