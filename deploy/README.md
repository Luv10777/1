# 测试服务器部署

一台机器跑全套：PostgreSQL、Redis、MinIO、api、worker、Nginx。
全部在 Docker 里，宿主机只需要装 Docker。

## 买什么

| 项 | 建议 | 说明 |
|---|---|---|
| 地域 | **境内** | AI 供应商和对象存储都在国内，买香港会让测试结果失真 |
| 规格 | 2核4G / 40G SSD | 要测视频合成就上 **4核8G**，FFmpeg 重编码 2 核会卡死 |
| 系统 | Ubuntu 22.04 LTS | |
| 带宽 | 3-5M 按量 | 测试期够用 |

**域名先不用买。** 备案要 15-20 个工作日，测试期直接用 `http://<公网IP>:8000` 访问。
境内服务器 80/443 端口对未备案域名会被拦，所以这里用 8000。

## 一、初始化服务器

```bash
# 建个非 root 用户
adduser deploy && usermod -aG sudo deploy
su - deploy

# 装 Docker（国内用阿里云脚本，比官方源快）
curl -fsSL https://get.docker.com | sudo sh -s -- --mirror Aliyun
sudo usermod -aG docker $USER
newgrp docker

# 配镜像加速，不然拉镜像会很慢甚至失败
sudo mkdir -p /etc/docker
echo '{"registry-mirrors":["https://docker.m.daocloud.io","https://dockerproxy.com"]}' | sudo tee /etc/docker/daemon.json
sudo systemctl restart docker

# 2G 内存的机器加 swap，不然 Maven 构建会 OOM
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**安全组只放行**：22（SSH）、8000（前端）、9000 和 9001（MinIO）。
数据库和 Redis 不映射到宿主机，外网碰不到。

## 二、拉代码、填配置

```bash
git clone https://github.com/Luv10777/1.git growth && cd growth
git checkout feat/growth-api-skeleton

cp deploy/.env.example deploy/.env
```

编辑 `deploy/.env`，四个口令用随机串：

```bash
openssl rand -base64 24   # DB_OWNER_PASSWORD
openssl rand -base64 24   # DB_APP_PASSWORD
openssl rand -base64 24   # REDIS_PASSWORD
openssl rand -base64 24   # MINIO_PASSWORD
openssl rand -base64 48   # JWT_SECRET  ← 换它所有人会被登出，定下来别动，备份好
```

**两个地方要填公网 IP**：

```ini
MINIO_PUBLIC_ENDPOINT=http://<公网IP>:9000
CORS_ORIGINS=http://<公网IP>:8000
```

`MINIO_PUBLIC_ENDPOINT` 特别容易错——预签名 URL 是发给**浏览器**直传用的，
写成 `http://minio:9000` 浏览器根本访问不到。

## 三、起服务

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

首次构建要拉 Maven 和 npm 依赖，2 核机器大约 5-10 分钟。

```bash
# 看状态
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps

# 看后端日志（验证码打在这里）
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f api
```

## 四、验一遍

```bash
IP=<公网IP>

# 1. 后端活着
curl http://$IP:8000/actuator/health

# 2. 鉴权生效（应返回 1401）
curl http://$IP:8000/api/assets

# 3. 发验证码
curl -X POST http://$IP:8000/api/auth/send-code \
  -H 'Content-Type: application/json' -d '{"phone":"13800138000"}'

# 4. 验证码在后端日志里，不真发短信
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs api | grep 验证码
```

浏览器打开 `http://<公网IP>:8000`，用上面的手机号和日志里的验证码登录。

MinIO 控制台在 `http://<公网IP>:9001`。

## 五、更新代码

```bash
git pull
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
```

只改前端就只重建 web，快很多：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build web
```

## 备份

测试服的数据不重要，但养成习惯：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env \
  exec -T postgres pg_dump -U growth_owner wuyao_growth | gzip > backup-$(date +%F).sql.gz
```

## 这套和生产环境的差别

测试服是"全都挤一台"，正式上线要改这几处：

| | 测试服 | 生产 |
|---|---|---|
| 数据库 | 容器里的 PostgreSQL | 云数据库 RDS，自动备份 + 主从 |
| 对象存储 | 容器里的 MinIO | 腾讯云 COS / 阿里云 OSS + CDN |
| 访问 | IP:8000，HTTP | 备案域名 + HTTPS |
| worker | 和 api 同机 | 按队列拆机器（IMAGE / VIDEO 分开） |
| 密钥 | `deploy/.env` 文件 | 云厂商密钥管理 |

## 已知问题

- **短信是假的**：`growth.sms.provider=console`，验证码打在后端日志。
  接真实短信要先申请签名和模板（1-2 天），再实现 `SmsSender`。
- **AI 供应商是占位的**：`EchoProviderAdapter` 不发任何网络请求，原样回显。
- **大部分业务模块后端还没写**：品牌、素材、知识、作品等前端仍是演示数据。
- MinIO 预签名上传这条链路**尚未实测过**，这台测试服正好用来验它。
