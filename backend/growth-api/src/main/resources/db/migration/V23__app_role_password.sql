-- 保持已发布的 V1 不变，以增量迁移兼容现有数据库。
-- 口令由 DB_PASSWORD 注入，与应用数据源配置保持一致。
ALTER ROLE growth_app WITH PASSWORD '${app_db_password}';
