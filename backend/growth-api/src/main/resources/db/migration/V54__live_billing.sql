-- 直播按时长计费：套餐开放直播权限，场次记录累计的直播时长和本场的烟火预占。
-- 已有套餐默认不含直播；会员账户里的套餐快照缺少该字段时同样视为不含直播。
ALTER TABLE billing_plans
    ADD COLUMN live_enabled boolean NOT NULL DEFAULT false;

-- live_seconds 是已结束的「直播中」时段之和，暂停不计；live_segment_started_at 是当前时段的开始时间。
-- billed_hours 是本场已预占的整小时数，每小时预占一次，结束时按分钟结算并退回余量。
ALTER TABLE live_sessions
    ADD COLUMN live_seconds bigint NOT NULL DEFAULT 0 CHECK (live_seconds >= 0),
    ADD COLUMN live_segment_started_at timestamptz,
    ADD COLUMN billing_reservation_id varchar(36),
    ADD COLUMN billed_hours int NOT NULL DEFAULT 0 CHECK (billed_hours >= 0);
