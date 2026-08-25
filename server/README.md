# 梧曜星枢后端服务

Spring Boot 3 + Java 21 核心服务端

## 本地开发

### 前置条件

- Java 21
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+

### 数据库初始化

```bash
# 创建数据库
createdb wuyao_nexus

# 执行初始化脚本
psql -d wuyao_nexus -f ../infra/database/001_init_schema.sql
```

### 配置环境变量

复制 `.env.example` 为 `.env` 并填写配置：

```bash
cp .env.example .env
```

必须配置：
- `DATABASE_URL` - PostgreSQL连接地址
- `DATABASE_USERNAME` - 数据库用户名
- `DATABASE_PASSWORD` - 数据库密码
- `JWT_SECRET` - JWT密钥（至少64字符）

### 启动服务

```bash
mvn spring-boot:run
```

服务将在 http://localhost:8080 启动

## API文档

### 认证接口

**发送验证码**
```
POST /api/auth/send-code
Content-Type: application/json

{
  "phone": "13800138000"
}
```

**登录（登录即注册）**
```
POST /api/auth/login
Content-Type: application/json

{
  "phone": "13800138000",
  "code": "123456"
}
```

**刷新Token**
```
POST /api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "your_refresh_token"
}
```

**登出**
```
POST /api/auth/logout
Authorization: Bearer <access_token>
```

### 商家接口

所有商家接口需要携带 `Authorization: Bearer <access_token>`

**获取商家列表**
```
GET /api/merchants?page=0&size=20
```

**获取商家详情**
```
GET /api/merchants/{id}
```

**创建商家**
```
POST /api/merchants
Content-Type: application/json

{
  "name": "青岚茶事",
  "industry": "餐饮",
  "logoUrl": "https://example.com/logo.png",
  "contactName": "林知夏",
  "contactPhone": "13800138000",
  "contactEmail": "contact@example.com"
}
```

**更新商家**
```
PUT /api/merchants/{id}
Content-Type: application/json

{
  "name": "青岚茶事",
  ...
}
```

**删除商家（软删除）**
```
DELETE /api/merchants/{id}
```

**启停商家**
```
PUT /api/merchants/{id}/status
Content-Type: application/json

{
  "enabled": true
}
```

### 门店接口

**获取商家门店列表**
```
GET /api/merchants/{merchantId}/stores?page=0&size=20
```

**获取门店详情**
```
GET /api/stores/{id}
```

**创建门店**
```
POST /api/merchants/{merchantId}/stores
Content-Type: application/json

{
  "name": "杭州城西店",
  "address": "西湖区文三路",
  "city": "杭州",
  "province": "浙江",
  "latitude": 30.2741,
  "longitude": 120.1551,
  "contactPhone": "0571-88888888",
  "businessHours": "{\"monday\": \"09:00-22:00\"}"
}
```

**更新门店**
```
PUT /api/stores/{id}
```

**删除门店（软删除）**
```
DELETE /api/stores/{id}
```

**启停门店**
```
PUT /api/stores/{id}/status
Content-Type: application/json

{
  "enabled": true
}
```

## 架构说明

### 目录结构

```
src/main/java/com/wuyao/nexus/
├── NexusApplication.java     # 启动类
├── config/                   # 配置类
│   ├── SecurityConfig.java   # Spring Security配置
│   └── WebConfig.java        # CORS配置
├── controller/               # 控制器
│   ├── AuthController.java
│   ├── MerchantController.java
│   └── StoreController.java
├── dto/                      # 数据传输对象
├── entity/                   # 实体类
├── exception/                # 异常处理
├── repository/               # 数据访问层
├── security/                 # 安全组件
│   └── JwtAuthenticationFilter.java
├── service/                  # 服务接口
│   ├── impl/                 # 服务实现
│   └── SmsProvider.java
└── util/                     # 工具类
    └── JwtUtil.java
```

### 核心特性

✅ **手机验证码登录**
- 发送频率限流（单手机号、单IP）
- 验证码Redis缓存
- SHA-256安全哈希
- 5分钟过期

✅ **JWT Token认证**
- Access Token（1小时）
- Refresh Token（30天）
- Token轮换机制
- 设备绑定

✅ **多租户架构**
- 租户数据隔离
- 登录即创建租户

✅ **商家与门店管理**
- CRUD完整实现
- 软删除
- 状态管理
- 完整度计算

✅ **安全机制**
- Spring Security集成
- CORS配置
- 全局异常处理
- 参数校验

## 环境变量清单

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| DATABASE_URL | PostgreSQL连接地址 | - |
| DATABASE_USERNAME | 数据库用户名 | postgres |
| DATABASE_PASSWORD | 数据库密码 | - |
| REDIS_HOST | Redis地址 | localhost |
| REDIS_PORT | Redis端口 | 6379 |
| JWT_SECRET | JWT密钥 | - |
| SMS_PROVIDER | 短信供应商 | console |
| CORS_ALLOWED_ORIGINS | CORS允许的源 | http://localhost:4173 |

## 测试

```bash
mvn test
```

## 生产部署

```bash
mvn clean package
java -jar target/nexus-server-0.1.0.jar
```

## 许可

嘉兴市梧曜科技有限公司版权所有
