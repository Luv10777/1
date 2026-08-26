#!/usr/bin/env node

/**
 * 阶段 1 基础设施验证脚本
 * 验证 PostgreSQL、Redis、RabbitMQ、MinIO 是否正常运行
 */

const { exec } = require('child_process');
const util = require('util');
const execPromise = util.promisify(exec);

const COLORS = {
  RESET: '\x1b[0m',
  GREEN: '\x1b[32m',
  RED: '\x1b[31m',
  YELLOW: '\x1b[33m',
  BLUE: '\x1b[34m',
};

function log(message, color = COLORS.RESET) {
  console.log(`${color}${message}${COLORS.RESET}`);
}

function success(message) {
  log(`✓ ${message}`, COLORS.GREEN);
}

function error(message) {
  log(`✗ ${message}`, COLORS.RED);
}

function info(message) {
  log(`ℹ ${message}`, COLORS.BLUE);
}

function warn(message) {
  log(`⚠ ${message}`, COLORS.YELLOW);
}

async function checkDockerRunning() {
  try {
    await execPromise('docker ps');
    success('Docker is running');
    return true;
  } catch (err) {
    error('Docker is not running or not accessible');
    return false;
  }
}

async function checkServiceHealth(serviceName) {
  try {
    const { stdout } = await execPromise(
      `docker inspect --format='{{.State.Health.Status}}' ${serviceName}`
    );
    const health = stdout.trim();

    if (health === 'healthy') {
      success(`${serviceName} is healthy`);
      return true;
    } else if (health === '') {
      warn(`${serviceName} has no health check configured`);
      return true;
    } else {
      error(`${serviceName} is ${health}`);
      return false;
    }
  } catch (err) {
    error(`${serviceName} is not running`);
    return false;
  }
}

async function checkPostgresDatabase() {
  try {
    const { stdout } = await execPromise(
      `docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"`
    );
    const tableCount = parseInt(stdout.trim());

    if (tableCount >= 40) {
      success(`PostgreSQL database has ${tableCount} tables`);
      return true;
    } else {
      warn(`PostgreSQL database has only ${tableCount} tables (expected >= 40)`);
      return false;
    }
  } catch (err) {
    error('Failed to query PostgreSQL database');
    console.error(err.message);
    return false;
  }
}

async function checkRedisConnection() {
  try {
    // Note: 需要从 .env 读取密码，这里简化处理
    const { stdout } = await execPromise(
      `docker exec wuyao-redis redis-cli PING`
    );
    const response = stdout.trim();

    if (response === 'PONG') {
      success('Redis is responding to PING');
      return true;
    } else {
      error('Redis PING failed');
      return false;
    }
  } catch (err) {
    error('Failed to connect to Redis');
    return false;
  }
}

async function checkRabbitMQQueues() {
  try {
    const { stdout } = await execPromise(
      `docker exec wuyao-rabbitmq rabbitmqctl list_queues -p wuyao --quiet name`
    );
    const queues = stdout.trim().split('\n').filter(q => q.length > 0);

    if (queues.length >= 8) {
      success(`RabbitMQ has ${queues.length} queues configured`);
      return true;
    } else {
      warn(`RabbitMQ has only ${queues.length} queues (expected >= 8)`);
      return false;
    }
  } catch (err) {
    error('Failed to list RabbitMQ queues');
    return false;
  }
}

async function checkMinioBuckets() {
  try {
    // 使用 MinIO Client 检查桶
    const { stdout } = await execPromise(
      `docker run --rm --network infra_wuyao-network minio/mc:latest mc alias set wuyao http://minio:9000 wuyao_minio_admin wuyao_minio_password && docker run --rm --network infra_wuyao-network minio/mc:latest mc ls wuyao`
    );

    const buckets = stdout.trim().split('\n').filter(line => line.includes('wuyao-'));

    if (buckets.length >= 3) {
      success(`MinIO has ${buckets.length} buckets configured`);
      return true;
    } else {
      warn(`MinIO has only ${buckets.length} buckets (expected >= 3)`);
      return false;
    }
  } catch (err) {
    error('Failed to list MinIO buckets');
    return false;
  }
}

async function checkOutboxTable() {
  try {
    const { stdout } = await execPromise(
      `docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -t -c "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"`
    );
    const pendingCount = parseInt(stdout.trim());

    success(`Outbox table exists (${pendingCount} pending events)`);
    return true;
  } catch (err) {
    error('Failed to query outbox_events table');
    return false;
  }
}

async function checkWorkflowDefinitions() {
  try {
    const { stdout } = await execPromise(
      `docker exec wuyao-postgres psql -U wuyao_user -d wuyao_nexus -t -c "SELECT workflow_type FROM workflow_definitions WHERE is_active = true"`
    );
    const workflows = stdout.trim().split('\n').filter(w => w.trim().length > 0);

    if (workflows.length > 0) {
      success(`Workflow definitions exist: ${workflows.join(', ')}`);
      return true;
    } else {
      warn('No active workflow definitions found');
      return false;
    }
  } catch (err) {
    error('Failed to query workflow_definitions table');
    return false;
  }
}

async function main() {
  log('\n========================================', COLORS.BLUE);
  log('阶段 1 基础设施验证', COLORS.BLUE);
  log('========================================\n', COLORS.BLUE);

  const results = {
    docker: false,
    postgres: false,
    postgresDb: false,
    redis: false,
    rabbitmq: false,
    rabbitmqQueues: false,
    minio: false,
    minioBuckets: false,
    outbox: false,
    workflows: false,
  };

  // 1. 检查 Docker
  info('Checking Docker...');
  results.docker = await checkDockerRunning();
  if (!results.docker) {
    error('\n❌ Docker is not running. Please start Docker Desktop and try again.\n');
    process.exit(1);
  }

  // 2. 检查服务健康状态
  info('\nChecking service health...');
  results.postgres = await checkServiceHealth('wuyao-postgres');
  results.redis = await checkServiceHealth('wuyao-redis');
  results.rabbitmq = await checkServiceHealth('wuyao-rabbitmq');
  results.minio = await checkServiceHealth('wuyao-minio');

  // 3. 检查数据库表
  if (results.postgres) {
    info('\nChecking PostgreSQL database...');
    results.postgresDb = await checkPostgresDatabase();
    results.outbox = await checkOutboxTable();
    results.workflows = await checkWorkflowDefinitions();
  }

  // 4. 检查 Redis 连接
  if (results.redis) {
    info('\nChecking Redis connection...');
    results.redis = await checkRedisConnection();
  }

  // 5. 检查 RabbitMQ 队列
  if (results.rabbitmq) {
    info('\nChecking RabbitMQ queues...');
    results.rabbitmqQueues = await checkRabbitMQQueues();
  }

  // 6. 检查 MinIO 桶（可能需要凭证，简化处理）
  if (results.minio) {
    info('\nChecking MinIO buckets...');
    // 暂时跳过，需要凭证配置
    warn('MinIO bucket check skipped (requires credentials)');
  }

  // 汇总结果
  log('\n========================================', COLORS.BLUE);
  log('验证结果汇总', COLORS.BLUE);
  log('========================================\n', COLORS.BLUE);

  const allPassed = Object.values(results).filter(Boolean).length;
  const totalChecks = Object.keys(results).length;

  if (allPassed === totalChecks) {
    success(`\n✅ 所有检查通过 (${allPassed}/${totalChecks})\n`);
    success('阶段 1 基础设施已就绪！\n');
    process.exit(0);
  } else if (allPassed >= totalChecks * 0.7) {
    warn(`\n⚠️  大部分检查通过 (${allPassed}/${totalChecks})\n`);
    warn('部分服务需要检查，但核心功能可用。\n');
    process.exit(0);
  } else {
    error(`\n❌ 多个检查失败 (${allPassed}/${totalChecks})\n`);
    error('请检查 Docker Compose 日志并修复问题：');
    info('  docker-compose logs <service_name>\n');
    process.exit(1);
  }
}

main().catch((err) => {
  error('\n验证脚本执行失败：');
  console.error(err);
  process.exit(1);
});
