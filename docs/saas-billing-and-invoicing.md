# SaaS 订单、支付与发票边界

2026-10-12：会员与烟火收款的正式实现已落在 `growth-api` 的 `billing_*` 独立表及微信 Native 支付流程，当前业务规则与配置以 [会员与烟火计费](yanhuo-billing.md) 为准。下面保留早期契约背景；发票和自动续费仍未接入。

SaaS 使用独立实体命名：`saas_quote`、`saas_contract`、`saas_order`、`saas_payment`、`saas_refund`、`invoice_request`。不得复用 Phase 3 消费者 `order/payment/refund`。

早期契约建议商家主动续费和对公转账核销。当前线上收款代码已接入微信支付 API v3，正式商户配置与实付联调待完成；电子签和数电发票未接入。

