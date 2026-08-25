# 套餐与权益模型（首批）

层级为：`Plan → PlanVersion → Price → EntitlementDefinition → Subscription → EffectiveEntitlementSnapshot`。

- 已售 `PlanVersion` 不可原地修改；调价或改权益创建新版本。
- 订阅保存购买时的价格与权益快照，后台后续改价不追溯改变历史订单。
- Feature Flag 控制研发灰度；Entitlement 控制购买权益；Permission 控制租户内员工操作，三者不能合并为一个布尔值。
- 到期/暂停状态阻止新的付费 AI 任务，但保留续费、导出和客服入口。
- 临时授权必须有原因、操作人、时间范围和审批引用。

当前实现状态：浏览器内存 Mock；未有数据库约束、服务端校验或真实套餐售卖。

