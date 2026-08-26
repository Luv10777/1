# 阶段 1 本地验证快速指南

## 🚀 快速开始

### 方式 1：使用 PowerShell 脚本（推荐）

打开 **PowerShell**，执行：
```powershell
cd C:\Users\Administrator\梧曜AI
.\infra\scripts\verify-windows.ps1
```

### 方式 2：使用 批处理脚本

双击运行：
```
C:\Users\Administrator\梧曜AI\infra\scripts\verify-windows.bat
```

### 方式 3：手动逐步执行

如果脚本无法运行，请手动复制以下命令到 **PowerShell** 中：

```powershell
# 1. 检查 Docker
docker --version
docker-compose --version

# 2. 启动服务
cd C:\Users\Administrator\梧曜AI\infra\compose
docker-compose up -d

# 3. 等待30秒
Start-Sleep -Seconds 30

# 4. 检查状态
docker-compose ps

# 5. 验证 PostgreSQL 表
docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"

# 6. 验证 Redis
docker exec wuyao-redis redis-cli -a wuyao_redis_2026 PING

# 7. 验证 RabbitMQ
docker exec wuyao-rabbitmq rabbitmqctl list_queues -p wuyao

# 8. 运行验证脚本
cd C:\Users\Administrator\梧曜AI
node infra/scripts/verify-infrastructure.js
```

---

## 📊 预期结果

### 容器状态
应该看到 5 个容器：
- ✅ wuyao-postgres (Up, healthy)
- ✅ wuyao-redis (Up, healthy)
- ✅ wuyao-rabbitmq (Up, healthy)
- ✅ wuyao-minio (Up, healthy)
- ⚪ wuyao-minio-init (Exited - 正常，初始化后退出)

### PostgreSQL
- 表数量：40+ 张表
- workflow_definitions 表有 1 条记录（IDEA2VIDEO）

### Redis
- PING 命令返回：PONG

### RabbitMQ
- 应该看到至少 8 个队列

### Node.js 验证脚本
- 输出：✅ 所有检查通过

---

## 🌐 访问管理界面

### RabbitMQ 管理界面
- 地址：http://localhost:15672
- 账号：`wuyao_admin`
- 密码：`wuyao_rabbitmq_2026`
- 验证：点击 "Queues" 标签，应该看到 8 个工作流队列

### MinIO 控制台
- 地址：http://localhost:9001
- 账号：`wuyao_minio_admin`
- 密码：`wuyao_minio_2026`
- 验证：应该看到 3 个桶（wuyao-assets、wuyao-temp、wuyao-backups）

---

## 🔧 故障排查

### 问题 1：Docker 命令找不到
**解决**：确保 Docker Desktop 正在运行（系统托盘有 Docker 图标）

### 问题 2：端口被占用
**解决**：
```powershell
# 检查端口占用
netstat -ano | findstr "5432"
netstat -ano | findstr "6379"
netstat -ano | findstr "5672"
netstat -ano | findstr "9000"
```

### 问题 3：容器启动失败
**解决**：
```powershell
# 查看日志
docker-compose logs postgres
docker-compose logs redis
docker-compose logs rabbitmq
docker-compose logs minio
```

### 问题 4：清理重新开始
**解决**：
```powershell
cd C:\Users\Administrator\梧曜AI\infra\compose
docker-compose down -v
docker-compose up -d
```

---

## ✅ 验证完成后

如果所有检查通过，说明阶段 1 基础设施已成功部署！

下一步：
1. 将验证结果截图或日志保存
2. 准备进入阶段 2：商家快照与素材底座
3. 或者停止服务：`docker-compose down`

---

**创建时间**: 2026-08-26  
**维护者**: Claude Code (Opus 5)
