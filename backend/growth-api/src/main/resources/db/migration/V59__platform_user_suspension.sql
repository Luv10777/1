-- 平台停用账号（SUSPENDED）：和商户自己停用店员（DISABLED）分开，商户不能撤销。
-- 解除停用时回到停用前的状态，所以停用前是 DISABLED 的店员解除后仍是 DISABLED。
ALTER TABLE users ADD COLUMN status_before_suspension VARCHAR(20);
ALTER TABLE users ADD COLUMN suspended_at TIMESTAMPTZ;

-- 平台停用和解除账号写入平台管理审计表。
ALTER TABLE platform_management_audit DROP CONSTRAINT IF EXISTS platform_management_audit_target_type_check;
ALTER TABLE platform_management_audit ADD CONSTRAINT platform_management_audit_target_type_check
    CHECK (target_type IN ('INFORMATION', 'MERCHANT', 'IDENTITY', 'SETTLEMENT_ACCOUNT', 'USER'));
