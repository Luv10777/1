-- V82 · 服务商分配记录
--
-- 服务商负责人分烟火有两个来源：服务商的可分配烟火（平台发放，POOL）和负责人自己账号里的烟火（ACCOUNT，充值和赠送都能分）；
-- 分给名下商户（TENANT）或自己的 BD（BD，进 BD 自己的账号）。每一笔记一行，服务商后台「分配记录」看这张表。
-- 赠送烟火分出去还是赠送烟火，充值烟火还是充值烟火：gift_credits、purchased_credits 记下这一笔两种各多少。
-- 先扣来源、再给对方入账，两步各自按 request_key 去重；status 在对方入账后才是 DONE，PENDING 表示可以用同一个 request_key 补上。
-- 和其他 partner_* 表一样是平台级数据，不开行级隔离，只有服务层读写。
CREATE TABLE partner_distributions (
    id                  BIGSERIAL    PRIMARY KEY,
    partner_id          BIGINT       NOT NULL REFERENCES partners(id),
    request_key         VARCHAR(120) NOT NULL UNIQUE,
    source              VARCHAR(8)   NOT NULL CHECK (source IN ('POOL', 'ACCOUNT')),
    recipient_type      VARCHAR(8)   NOT NULL CHECK (recipient_type IN ('TENANT', 'BD')),
    recipient_tenant_id BIGINT       NOT NULL REFERENCES tenants(id),
    recipient_user_id   BIGINT       REFERENCES users(id),
    credits             BIGINT       NOT NULL CHECK (credits > 0),
    gift_credits        BIGINT       NOT NULL CHECK (gift_credits >= 0),
    purchased_credits   BIGINT       NOT NULL CHECK (purchased_credits >= 0),
    note                VARCHAR(200),
    actor_user_id       BIGINT       NOT NULL REFERENCES users(id),
    status              VARCHAR(8)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CHECK (gift_credits + purchased_credits = credits),
    CHECK ((recipient_type = 'BD') = (recipient_user_id IS NOT NULL))
);
CREATE INDEX idx_partner_distributions_partner ON partner_distributions (partner_id, id DESC);
CREATE INDEX idx_partner_distributions_recipient ON partner_distributions (partner_id, recipient_tenant_id, id DESC);

-- 补上 V82 之前从可分配烟火分给商户的每一笔（都记作已到账，request_key 沿用服务商流水的 event_key）。
INSERT INTO partner_distributions (partner_id, request_key, source, recipient_type, recipient_tenant_id, credits, gift_credits,
                                   purchased_credits, note, actor_user_id, status, created_at, completed_at)
SELECT partner_id, event_key, 'POOL', 'TENANT', tenant_id, -delta, 0, -delta, note, actor_user_id, 'DONE', created_at, created_at
FROM partner_credit_ledger WHERE type = 'TRANSFER' ORDER BY id;

GRANT SELECT, INSERT, UPDATE ON partner_distributions TO growth_app;
GRANT USAGE, SELECT ON SEQUENCE partner_distributions_id_seq TO growth_app;
