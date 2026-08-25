# 交易状态机（Phase 3 首批）

订单和支付状态分离。当前浏览器预览只创建 `PENDING_PAYMENT` 订单草稿与库存预占，不执行真实支付。

```text
Order: DRAFT → PENDING_PAYMENT → PAID → FULFILLING → COMPLETED
       ↘ CANCELED / REFUNDING / PARTIALLY_REFUNDED / REFUNDED

Payment: INIT → PREPAY_CREATED → PROCESSING → SUCCESS
         ↘ CLOSED / FAILED / REFUNDING / PARTIALLY_REFUNDED / REFUNDED
```

服务端实现必须将价格、库存、门店、优惠和身份重新校验；支付回调、重复点击和消息重放使用幂等键收敛。金额使用整数最小货币单位。

