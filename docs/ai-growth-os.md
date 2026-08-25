# 受控 AI Growth OS

Growth OS 采用 `事实 → 洞察 → 计划 → 模拟 → 审批 → 执行 → 停止 → 复盘`。Planner 只能生成草稿；Verifier 检查事实、Schema、预算和风险；Policy Engine 决定是否需要人工审批；Executor 只调用白名单 Tool。

本阶段自主等级：L0 仅草稿，L1 低风险动作需审批，L2 已批准的低风险 Tool 可执行，L3 未来仅灰度低风险动作，L4 关闭。支付、退款、密钥轮换、删数、发布和批量消息属于高风险 Tool，当前始终阻断或转人工。
