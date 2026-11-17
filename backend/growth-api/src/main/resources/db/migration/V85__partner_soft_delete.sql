-- V85 · 平台删除服务商（软删除）
--
-- 删除后服务商不再出现在管理后台、所有登录账号移出（不能再进服务商后台，可以加入别家）、名下商户解除归属、
-- 没兑换的激活码作废；发放、分配、跟进记录和激活码都保留，只是不再显示。团队账户就是负责人自己的一方志商户，余额不动。
ALTER TABLE partners DROP CONSTRAINT partners_status_check;
ALTER TABLE partners ADD CONSTRAINT partners_status_check CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED'));
