-- IAM 密码账号：不需要伪造手机号；实际账号由管理员单独创建。
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE users ADD COLUMN username VARCHAR(64);
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN password_failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN password_locked_until TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);
