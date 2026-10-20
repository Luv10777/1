package com.wuyao.growth.community;

/** 测试用：直接写入一条审核通过的入驻申请。线上卖货、发布信息、提供联盟券都要求入驻通过。 */
public final class MerchantTestFixtures {
    private MerchantTestFixtures() {}

    public static void approveMerchant(org.springframework.jdbc.core.JdbcTemplate owner, Long tenantId, Long userId) {
        owner.update("""
                INSERT INTO merchant_applications(tenant_id,submitted_by,merchant_name,company_name,industry,region,
                    credit_code,contact_name,contact_phone,introduction,status)
                VALUES (?,?,'测试商家','测试商家有限公司','测试行业','测试地区','91330400MA2T' || lpad(?::text, 6, '0'),
                    '联系人','13800009999','测试用入驻资料','APPROVED')""", tenantId, userId, tenantId);
    }
}
