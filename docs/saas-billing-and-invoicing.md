# SaaS 订单、支付与发票边界

SaaS 使用独立实体命名：`saas_quote`、`saas_contract`、`saas_order`、`saas_payment`、`saas_refund`、`invoice_request`。不得复用 Phase 3 消费者 `order/payment/refund`。

首批建议支持商家主动续费和对公转账人工核销；在线支付、自动续费、电子签和数电发票均需真实主体、官方能力、财务/税务口径和审计后再接入。当前仅有 SaaS 草稿订单契约，没有真实收款或开票。

