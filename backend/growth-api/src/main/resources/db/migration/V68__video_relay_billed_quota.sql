-- V68 · 视频交付按中转站实扣结算：记录中转站任务记录里的最终扣费额度（中转站 quota 单位）。
--
-- 为空表示没查到实扣记录，那一单仍按 token 公式结算，对账时可以据此区分两种来源。
ALTER TABLE video_workflows ADD COLUMN provider_billed_quota BIGINT CHECK (provider_billed_quota > 0);
