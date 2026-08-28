-- 初始化数据库脚本
-- 创建数据库和用户

-- 创建数据库
CREATE DATABASE wuyao_vimax
    WITH
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TEMPLATE = template0;

-- 创建用户
CREATE USER wuyao_user WITH PASSWORD 'wuyao_dev_2026';

-- 授予权限
GRANT ALL PRIVILEGES ON DATABASE wuyao_vimax TO wuyao_user;

-- 连接到数据库
\c wuyao_vimax

-- 授予schema权限
GRANT ALL ON SCHEMA public TO wuyao_user;

-- 启用必要的扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 注意：pgvector 扩展用于向量检索，可选安装
-- CREATE EXTENSION IF NOT EXISTS vector;
