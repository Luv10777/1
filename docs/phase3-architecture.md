# 梧曜星枢第三阶段架构骨架

## 当前实现边界

第一版采用“现有 Vue 工作台 + 消费者预览页 + 可替换领域契约”的模块化单体形态。消费者预览通过 `src/domain/commerce.js` 运行本地 Mock，不代表生产 API、真实支付或微信 Handoff 已接通。

```text
统一消费者入口
  └─ Consumer Context（tenant / merchant / store / entry）
      ├─ Offering Catalog
      ├─ Cart / Booking Draft
      ├─ Order Draft + Inventory Reservation
      └─ Handoff Token → 预约/结算接力页
```

未来服务端边界保持为：

```text
Consumer BFF → Commerce Engine → Payment & Reconciliation
              ├→ CRM / Customer Data → Marketing Automation
              ├→ Attribution & Events
              └→ AI Concierge → allowlisted Skills
```

## 领域约束

- 所有消费者对象必须携带租户、商家和门店上下文；不能信任浏览器单独传入的商家选择。
- 金额使用整数最小货币单位，当前示例使用人民币分。
- 订单状态和支付状态分离；本次首个增量只创建待支付订单，不伪造支付成功。
- 库存/档期在创建草稿订单时重新校验，并生成带 TTL 的预占记录。
- Handoff 只传短期一次性引用；真实数据应由服务端按 Token 查询，不能把手机号、地址或支付凭证放入 query。
- AI 只负责解释、检索和创建草稿，不直接支付、退款、核销或修改库存。

## 设计决策记录

- **人**：从内容触达进入小程序的消费者；5 分钟前看到了活动内容，接下来希望快速判断“适不适合我、什么时候能用、怎么下单”。
- **动作**：确认商家上下文、查看可售对象、咨询、预约或创建订单草稿。
- **感受**：像门店的轻量服务台，温和、清楚、有品牌感，不像后台控制台。
- **领域词汇**：门店橱窗、到店时段、服务卡、接力单、增长回路、经营脉冲。
- **色彩世界**：米白纸张、炭黑墨色、茶叶青、陶土橙、夜空靛蓝；颜色只承担品牌、动作和状态含义。
- **签名交互**：AI 推荐卡不会直接“替用户付款”，而是生成一张带上下文的“接力单”，将用户送入确定性页面。
- **拒绝的默认**：不使用每商家一套小程序、不把 AI 聊天做成万能输入框、不用紫粉渐变冒充支付或生产能力。

