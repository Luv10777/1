# 内容到订单归因

统一事件契约覆盖 `CONTENT_CLICK → MINIPROGRAM_OPEN → AI_CONVERSATION_START → OFFERING_VIEW → ADD_TO_CART → ORDER_CREATED → PAYMENT_SUCCESS → VERIFICATION_SUCCESS`。事件携带租户、商家、门店、会话、Campaign、Content、入口场景和订单引用。

首批归因模型为最后一次非直接触点，并保留首次触点和辅助触点。无法建立关联时归入自然/未知来源；需要时间窗或概率推断时必须将结果标记为估算并保存模型版本。当前实现为内存领域函数，尚未接入事件总线和可重算数据仓库。

