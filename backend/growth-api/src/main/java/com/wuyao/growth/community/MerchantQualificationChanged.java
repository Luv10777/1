package com.wuyao.growth.community;

/**
 * 平台改了某个商户的入驻审核结果后发出，与审核同一事务、在该商户的租户里同步处理。
 * approved=false 表示这个商户已经没有审核通过的入驻（例如被暂停），依赖入驻资格的模块据此撤下线上内容。
 * status / note 是这次审核把申请改成的状态（APPROVED / REJECTED / SUSPENDED）和平台意见，用于通知商户。
 */
public record MerchantQualificationChanged(Long tenantId, boolean approved, String status, String note) {}
