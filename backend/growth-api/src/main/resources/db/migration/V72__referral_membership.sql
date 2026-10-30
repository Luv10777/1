-- V72 · 被邀请商户开通会员也给邀请人返利，比例和充值分开设：充值默认 10%，开通会员默认 20%。
ALTER TABLE referral_rates ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'TOPUP' CHECK (kind IN ('TOPUP', 'MEMBERSHIP'));
ALTER TABLE referral_rates ALTER COLUMN kind DROP DEFAULT;
CREATE INDEX idx_referral_rates_kind ON referral_rates (kind, id DESC);
INSERT INTO referral_rates (rate_bp, note, kind) VALUES (2000, '开通会员默认 20%', 'MEMBERSHIP');

ALTER TABLE referral_commissions ADD COLUMN order_kind VARCHAR(16) NOT NULL DEFAULT 'TOPUP' CHECK (order_kind IN ('TOPUP', 'MEMBERSHIP'));
ALTER TABLE referral_commissions ALTER COLUMN order_kind DROP DEFAULT;
