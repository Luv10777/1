package com.wuyao.growth.notice;

import com.wuyao.growth.community.MerchantQualificationChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 入驻审核结果通知商户：线上卖货、发布信息、提供联盟券都要求入驻通过，商户需要知道什么时候能开始。 */
@Component
@RequiredArgsConstructor
class MerchantQualificationNotices {
    private final NoticeService notices;

    @EventListener
    public void onChanged(MerchantQualificationChanged event) {
        String key = "merchant-qualification:" + event.status() + ":" + System.currentTimeMillis();
        switch (event.status()) {
            case "APPROVED" -> notices.notifyOwners("MERCHANT", "商家入驻审核通过",
                    "现在可以把商品上架线上售卖、发布商家信息、提供联盟券。", "/merchant-alliance/join", key);
            case "REJECTED" -> notices.notifyOwners("MERCHANT", "商家入驻需要补充资料",
                    "平台意见：" + event.note() + "。修改后可以重新提交。", "/merchant-alliance/join", key);
            case "SUSPENDED" -> notices.notifyOwners("MERCHANT", "商家入驻已被平台暂停",
                    "平台意见：" + event.note() + "。在线商品已撤回草稿，已公开的信息已下线，没被领走的联盟券已撤回；已领出的联盟券仍需在门店兑现。",
                    "/merchant-alliance/join", key);
            default -> { }
        }
    }
}
