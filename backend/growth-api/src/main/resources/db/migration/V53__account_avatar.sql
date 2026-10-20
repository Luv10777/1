-- users 是 IAM 系统表；头像只能通过 JWT 本人账号接口更新。
ALTER TABLE users
    ADD COLUMN avatar_key varchar(300),
    ADD COLUMN avatar_upload_key varchar(300),
    ADD COLUMN avatar_upload_expires_at timestamptz;
