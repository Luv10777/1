-- 网站应用微信登录身份。登录时还不知道租户，因此和 users 一样不启用 RLS。
CREATE TABLE wechat_accounts (
    id            BIGSERIAL PRIMARY KEY,
    app_id        VARCHAR(64)  NOT NULL,
    open_id       VARCHAR(128) NOT NULL,
    union_id      VARCHAR(128),
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    nickname      VARCHAR(120),
    headimg_url   VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_wechat_app_open UNIQUE (app_id, open_id)
);

CREATE INDEX idx_wechat_user ON wechat_accounts(user_id);
CREATE UNIQUE INDEX uk_wechat_app_union
    ON wechat_accounts(app_id, union_id)
    WHERE union_id IS NOT NULL;
