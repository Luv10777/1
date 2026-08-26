@echo off
REM ============================================================================
REM 梧曜星枢 - 阶段1基础设施验证脚本 (Windows)
REM 请在 PowerShell 或 命令提示符 中运行此脚本
REM ============================================================================

echo ========================================
echo 阶段 1 基础设施验证开始
echo ========================================
echo.

REM 步骤 1: 检查 Docker
echo [1/8] 检查 Docker 安装...
docker --version
if %ERRORLEVEL% NEQ 0 (
    echo [错误] Docker 未安装或未启动
    echo 请确保 Docker Desktop 正在运行
    pause
    exit /b 1
)
docker-compose --version
echo.

REM 步骤 2: 进入目录
echo [2/8] 进入 compose 目录...
cd /d C:\Users\Administrator\梧曜AI\infra\compose
echo 当前目录: %CD%
echo.

REM 步骤 3: 启动服务
echo [3/8] 启动基础设施服务...
docker-compose up -d
echo 等待服务启动 (30秒)...
timeout /t 30 /nobreak
echo.

REM 步骤 4: 检查容器状态
echo [4/8] 检查容器状态...
docker-compose ps
echo.
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo.

REM 步骤 5: 验证 PostgreSQL
echo [5/8] 验证 PostgreSQL 数据库...
docker exec -it wuyao-postgres psql -U wuyao_user -d wuyao_nexus -c "SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema = 'public'"
echo.
docker exec -it wuyao-postgres psql -U wuyao_user -d wuyao_nexus -c "SELECT workflow_type, version FROM workflow_definitions"
echo.

REM 步骤 6: 验证 Redis
echo [6/8] 验证 Redis...
docker exec -it wuyao-redis redis-cli -a wuyao_redis_2026 PING
echo.

REM 步骤 7: 验证 RabbitMQ
echo [7/8] 验证 RabbitMQ 队列...
docker exec -it wuyao-rabbitmq rabbitmqctl list_queues -p wuyao --quiet
echo.

REM 步骤 8: 运行验证脚本
echo [8/8] 运行 Node.js 验证脚本...
cd /d C:\Users\Administrator\梧曜AI
node infra/scripts/verify-infrastructure.js
echo.

echo ========================================
echo 验证完成！
echo ========================================
echo.
echo 管理界面访问：
echo   RabbitMQ: http://localhost:15672
echo     账号: wuyao_admin
echo     密码: wuyao_rabbitmq_2026
echo.
echo   MinIO: http://localhost:9001
echo     账号: wuyao_minio_admin
echo     密码: wuyao_minio_2026
echo.

pause
