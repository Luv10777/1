# ============================================================================
# 梧曜星枢 - 阶段1基础设施验证脚本 (PowerShell)
# ============================================================================

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "阶段 1 基础设施验证开始" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# 步骤 1: 检查 Docker
Write-Host "[1/8] 检查 Docker 安装..." -ForegroundColor Yellow
try {
    docker --version
    docker-compose --version
    Write-Host "✓ Docker 可用" -ForegroundColor Green
    Write-Host ""
} catch {
    Write-Host "✗ Docker 未安装或未启动" -ForegroundColor Red
    Write-Host "请确保 Docker Desktop 正在运行" -ForegroundColor Red
    Write-Host ""
    exit 1
}

# 步骤 2: 进入目录
Write-Host "[2/8] 进入 compose 目录..." -ForegroundColor Yellow
Set-Location "C:\Users\Administrator\梧曜AI\infra\compose"
Write-Host "当前目录: $(Get-Location)" -ForegroundColor Gray
Write-Host ""

# 步骤 3: 启动服务
Write-Host "[3/8] 启动基础设施服务..." -ForegroundColor Yellow
docker-compose up -d
Write-Host "等待服务启动 (30秒)..." -ForegroundColor Gray
Write-Host ""
Start-Sleep -Seconds 30

# 步骤 4: 检查容器状态
Write-Host "[4/8] 检查容器状态..." -ForegroundColor Yellow
docker-compose ps
Write-Host ""
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
Write-Host ""

# 步骤 5: 验证 PostgreSQL
Write-Host "[5/8] 验证 PostgreSQL 数据库..." -ForegroundColor Yellow
docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -c "SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema = 'public'"
Write-Host ""
docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -c "SELECT workflow_type, version FROM workflow_definitions"
Write-Host ""

# 步骤 6: 验证 Redis
Write-Host "[6/8] 验证 Redis..." -ForegroundColor Yellow
docker exec wuyao-redis redis-cli -a wuyao_redis_2026 PING
Write-Host ""

# 步骤 7: 验证 RabbitMQ
Write-Host "[7/8] 验证 RabbitMQ 队列..." -ForegroundColor Yellow
docker exec wuyao-rabbitmq rabbitmqctl list_queues -p wuyao --quiet
Write-Host ""

# 步骤 8: 运行验证脚本
Write-Host "[8/8] 运行 Node.js 验证脚本..." -ForegroundColor Yellow
Set-Location "C:\Users\Administrator\梧曜AI"
node infra/scripts/verify-infrastructure.js
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "验证完成！" -ForegroundColor Green
Write-Host "========================================`n" -ForegroundColor Cyan

Write-Host "管理界面访问：" -ForegroundColor Yellow
Write-Host "  RabbitMQ: http://localhost:15672" -ForegroundColor White
Write-Host "    账号: wuyao_admin" -ForegroundColor Gray
Write-Host "    密码: wuyao_rabbitmq_2026" -ForegroundColor Gray
Write-Host ""
Write-Host "  MinIO: http://localhost:9001" -ForegroundColor White
Write-Host "    账号: wuyao_minio_admin" -ForegroundColor Gray
Write-Host "    密码: wuyao_minio_2026" -ForegroundColor Gray
Write-Host ""
