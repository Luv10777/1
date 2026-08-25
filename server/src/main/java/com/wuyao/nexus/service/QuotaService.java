package com.wuyao.nexus.service;

import java.math.BigDecimal;

public interface QuotaService {
    /**
     * 检查并预留配额
     */
    void checkAndReserve(Long tenantId, BigDecimal amount);

    /**
     * 扣除实际成本
     */
    void charge(Long tenantId, BigDecimal amount);

    /**
     * 释放预留配额
     */
    void release(Long tenantId, BigDecimal amount);

    /**
     * 充值
     */
    void recharge(Long tenantId, BigDecimal amount);

    /**
     * 获取配额信息
     */
    Object getQuotaInfo(Long tenantId);
}
