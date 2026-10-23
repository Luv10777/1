# 监控：Prometheus + Grafana

- **Prometheus** 每 15 秒向每个后端进程要一次运行数据（`/actuator/prometheus`），存 15 天。
- **Grafana** 把这些数据画成看板「一方志 · 运行总览」，打开就是首页。

```
后端进程 ──/actuator/prometheus（带口令）──▶ Prometheus ──▶ Grafana ──SSH 隧道──▶ 你的浏览器
```

两者都只监听服务器本机，外网访问不到。看的时候用 SSH 隧道把端口接到自己电脑上。

## 看板上有什么

| 区域 | 内容 | 什么时候该担心 |
|---|---|---|
| 一眼看全局 | 掉线进程、每分钟请求、出错比例、慢请求（P95）、卡住的视频、图片排队 | 任何一格变黄或变红 |
| 接口 | 请求量、最慢的 8 个接口、出错的接口 | 「出错的接口」平时应为空 |
| 后台任务 | 图片队列、视频队列、视频并发占用 | 排队数持续上涨不回落 |
| 机器和进程 | 每个进程的内存、CPU、数据库连接，以及进程在线 | 内存贴上限，数据库连接有等待 |

每个面板标题旁的 ⓘ 有一句话说明。

## 后端要做的配置

`/actuator/prometheus` 默认不公开。所有要被抓取的进程都需要：

```properties
MANAGEMENT_EXPOSURE_INCLUDE=health,prometheus
METRICS_TOKEN=<openssl rand -hex 32 生成的随机串>
```

口令为空时该地址一律拒绝。Prometheus 带同一个口令来读，别人（包括登录用户）读不到。

## 线上（systemd 部署）

线上主机没有 Docker，后端进程由 systemd 运行，所以 Prometheus 用 Ubuntu 自带的软件包、
Grafana 用官方 .deb（校验 sha256）直接装成系统服务，由 `install-host.sh` 完成。
Prometheus 抓本机 `8080`（api）、`8090`（worker）、`8091-8093`（图片）、`8094`（视频）、`8095`（媒体），
新增或改端口时同步改 `prometheus.host.yml` 并重跑脚本。

1. 后端新版本上线后，在 `/etc/wuyao/growth-api.env` 里加上面两行，重启全部后端进程。
2. 把本目录传到服务器，执行（可重复执行，用来更新配置和看板）：

   ```bash
   sudo METRICS_TOKEN=<同第 1 步> GRAFANA_ADMIN_PASSWORD=<新密码> bash install-host.sh
   ```

   以后再跑可以不带 `GRAFANA_ADMIN_PASSWORD`，密码保持不变。脚本最后打印每个进程的抓取状态，应全部为 `up`。

Prometheus 在 `127.0.0.1:19090`，Grafana 在 `127.0.0.1:13000`。
占用：Prometheus 约 200 MB 内存，Grafana 约 100 MB，磁盘 15 天约 1 GB 以内。

## Docker Compose 测试栈

`deploy/.env` 填 `METRICS_TOKEN` 和 `GRAFANA_ADMIN_PASSWORD`，然后：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env --profile monitoring up -d
```

Grafana 在服务器本机 `127.0.0.1:3000`（`GRAFANA_PORT` 可改）。

## 怎么看

在自己电脑上开一个终端，保持不关：

```bash
ssh -N -L 13000:127.0.0.1:13000 ubuntu@<服务器IP>
```

浏览器打开 <http://localhost:13000>，用户名 `admin`，密码是 `GRAFANA_ADMIN_PASSWORD`。
测试栈把两处 `13000` 换成 `3000`。

## 改看板

看板文件是 `grafana/dashboards/growth-overview.json`，Grafana 里设为只读。
要改就在 Grafana 里另存一份调好，导出 JSON 覆盖这个文件后提交；线上重跑 `install-host.sh`，
测试栈重启 Grafana 生效。

## 验证记录

2026-10-23 在本机验证：后端带口令启动，Prometheus 抓取成功；不带口令、口令错误、
普通用户登录访问 `/actuator/prometheus` 分别返回 401、401、403（集成测试
`prometheusMetricsNeedTheScrapeTokenAndAUserLoginIsNotEnough`）；Grafana 自动加载数据源和看板，
各面板有数据，没配口令的进程显示为掉线。`install-host.sh` 尚未在线上执行过。
