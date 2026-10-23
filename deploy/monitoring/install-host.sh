#!/usr/bin/env bash
# 在线上主机（Ubuntu，后端由 systemd 运行）安装或更新 Prometheus + Grafana，可重复执行。
#   sudo METRICS_TOKEN=... GRAFANA_ADMIN_PASSWORD=... bash deploy/monitoring/install-host.sh
# METRICS_TOKEN 必须和 /etc/wuyao/growth-api.env 里的相同。
# GRAFANA_ADMIN_PASSWORD 只在首次安装时必填；以后不填就保留原密码，填了就改成新密码。
# 两个服务都只监听 127.0.0.1，用 SSH 隧道访问，见同目录 README.md。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GRAFANA_VERSION=12.0.2
PROMETHEUS_PORT=19090
GRAFANA_PORT=13000

[ "$(id -u)" -eq 0 ] || { echo "请用 sudo 运行"; exit 1; }
[ -n "${METRICS_TOKEN:-}" ] || { echo "缺少 METRICS_TOKEN"; exit 1; }
if ! dpkg -s grafana >/dev/null 2>&1 && [ -z "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  echo "首次安装需要 GRAFANA_ADMIN_PASSWORD"; exit 1
fi

echo "== Prometheus"
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends prometheus >/dev/null
install -m 640 -o root -g prometheus /dev/null /etc/prometheus/metrics-token
printf %s "$METRICS_TOKEN" > /etc/prometheus/metrics-token
install -m 644 "$HERE/prometheus.host.yml" /etc/prometheus/prometheus.yml
promtool check config /etc/prometheus/prometheus.yml
cat > /etc/default/prometheus <<CONF
# 由 deploy/monitoring/install-host.sh 生成
ARGS="--web.listen-address=127.0.0.1:${PROMETHEUS_PORT} --storage.tsdb.retention.time=15d"
CONF
systemctl enable prometheus >/dev/null
systemctl restart prometheus

echo "== Grafana ${GRAFANA_VERSION}"
if [ "$(dpkg-query -W -f='${Version}' grafana 2>/dev/null || true)" != "$GRAFANA_VERSION" ]; then
  arch="$(dpkg --print-architecture)"
  deb="grafana_${GRAFANA_VERSION}_${arch}.deb"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/$deb" "https://dl.grafana.com/oss/release/$deb"
  curl -fsSL --retry 3 -o "$tmp/$deb.sha256" "https://dl.grafana.com/oss/release/$deb.sha256"
  echo "$(cat "$tmp/$deb.sha256")  $tmp/$deb" | sha256sum -c -
  DEBIAN_FRONTEND=noninteractive apt-get install -y "$tmp/$deb" >/dev/null
  rm -rf "$tmp"
fi
install -d -m 755 /etc/grafana/provisioning/datasources /etc/grafana/provisioning/dashboards
install -d -o grafana -g grafana /var/lib/grafana/dashboards
install -m 644 "$HERE/grafana/provisioning/datasources/prometheus.yml" /etc/grafana/provisioning/datasources/growth-prometheus.yml
install -m 644 "$HERE/grafana/provisioning/dashboards/dashboards.yml" /etc/grafana/provisioning/dashboards/growth.yml
install -m 644 -o grafana -g grafana "$HERE"/grafana/dashboards/*.json /var/lib/grafana/dashboards/
grep -v '^GF_\|^PROMETHEUS_URL=\|^# growth' /etc/default/grafana-server > /etc/default/grafana-server.new || true
cat >> /etc/default/grafana-server.new <<CONF
# growth：以下由 deploy/monitoring/install-host.sh 生成
PROMETHEUS_URL=http://127.0.0.1:${PROMETHEUS_PORT}
GF_SERVER_HTTP_ADDR=127.0.0.1
GF_SERVER_HTTP_PORT=${GRAFANA_PORT}
GF_USERS_ALLOW_SIGN_UP=false
GF_ANALYTICS_REPORTING_ENABLED=false
GF_ANALYTICS_CHECK_FOR_UPDATES=false
GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/var/lib/grafana/dashboards/growth-overview.json
GF_DEFAULT_LOCALE=zh-Hans
CONF
mv /etc/default/grafana-server.new /etc/default/grafana-server
systemctl enable grafana-server >/dev/null
systemctl restart grafana-server
if [ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]; then
  for _ in $(seq 1 30); do curl -fs "http://127.0.0.1:${GRAFANA_PORT}/api/health" >/dev/null && break; sleep 2; done
  sudo -u grafana /usr/share/grafana/bin/grafana cli --homepath /usr/share/grafana --config /etc/grafana/grafana.ini \
    --configOverrides "cfg:default.paths.data=/var/lib/grafana cfg:default.paths.logs=/var/log/grafana" \
    admin reset-admin-password "$GRAFANA_ADMIN_PASSWORD" >/dev/null 2>&1
fi

echo "== 检查"
for _ in $(seq 1 30); do curl -fs "http://127.0.0.1:${GRAFANA_PORT}/api/health" >/dev/null && break; sleep 2; done
curl -fs "http://127.0.0.1:${GRAFANA_PORT}/api/health"; echo
sleep 20
curl -fs "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets" | python3 -c '
import json, sys
for t in json.load(sys.stdin)["data"]["activeTargets"]:
    print(t["labels"]["service"], t["health"], t.get("lastError", ""))'
