# 数据库迁移脚本

## 执行顺序

**重要**: 必须按照以下顺序执行 SQL 脚本：

### 1. 初始化数据库
```bash
psql -U postgres -f 00_init_database.sql
```

### 2. 执行迁移脚本（按顺序）
```bash
psql -U wuyao_user -d wuyao_vimax -f 001_init_schema.sql
psql -U wuyao_user -d wuyao_vimax -f 002_resource_libraries.sql
psql -U wuyao_user -d wuyao_vimax -f 003_ai_gateway_tasks.sql
psql -U wuyao_user -d wuyao_vimax -f 004_video_workflow_core.sql
psql -U wuyao_user -d wuyao_vimax -f 005_video_workflow_support.sql
psql -U wuyao_user -d wuyao_vimax -f 006_insert_provider_configs.sql
```

### 3. 一键执行（推荐）
```bash
# Linux/Mac
./migrate.sh

# Windows
migrate.bat
```

## 表结构统计

| 脚本 | 表数量 | 说明 |
|-----|-------|------|
| `001_init_schema.sql` | 10 | 租户、用户、商家、门店、权限 |
| `002_resource_libraries.sql` | 4 | 品牌库、知识库、作品库 |
| `003_ai_gateway_tasks.sql` | 7 | AI提供商、模型别名、任务 |
| `004_video_workflow_core.sql` | 16 | 视频工作流核心表 |
| `005_video_workflow_support.sql` | 12 | 质检、审核、成本、Outbox、Assets |
| `006_insert_provider_configs.sql` | 0 | Provider配置初始数据 |
| **总计** | **49** | |

## 重要说明

### 已修复的问题
- ✅ 删除了 `audit_logs` 在 `001_init_schema.sql` 中的重复定义
- ✅ 删除了 `assets` 在 `002_resource_libraries.sql` 中的重复定义
- ✅ 删除了 `knowledge_chunks` 在 `002_resource_libraries.sql` 中的重复定义
- ✅ 统一在 `005_video_workflow_support.sql` 中定义这些表

### 外键依赖
- `002` 依赖 `001` (tenants, users, merchants, stores)
- `003` 依赖 `001` (tenants)
- `004` 依赖 `001`, `002` (tenants, merchants, users)
- `005` 依赖 `001`, `004` (tenants, users, workflow_runs)
- `006` 依赖 `003` (需要 provider_configs 表存在，但该表在003中未定义)

### ⚠️ 待修复问题
`006_insert_provider_configs.sql` 引用了 `provider_configs` 表，但该表在 `003_ai_gateway_tasks.sql` 中未定义。
实际定义的表是 `ai_providers`。需要修正。

## 环境变量

确保在执行前设置：
```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/wuyao_vimax
export DATABASE_USER=wuyao_user
export DATABASE_PASSWORD=wuyao_dev_2026
```

## 回滚

如需重新初始化：
```bash
psql -U postgres -c "DROP DATABASE IF EXISTS wuyao_vimax;"
psql -U postgres -c "DROP USER IF EXISTS wuyao_user;"
```
