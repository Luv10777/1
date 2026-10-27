#!/usr/bin/env bash
# 在正式服安装每日数据库备份。可重复执行：已有的加密口令不会被覆盖。
# 用法：把本目录上传到服务器后，在该目录执行 sudo bash install.sh
set -euo pipefail
cd "$(dirname "$0")"

LIB=/usr/local/lib/wuyao-db-backup
PASSPHRASE=/etc/wuyao/backup-passphrase

install -d -m 700 "$LIB" /var/backups/wuyao-db /var/lib/wuyao-db-backup
install -m 700 backup.sh restore-check.sh "$LIB/"
install -m 600 upload.py "$LIB/"
install -m 644 wuyao-db-backup.service wuyao-db-backup.timer \
    wuyao-db-restore-check.service wuyao-db-restore-check.timer /etc/systemd/system/

if [ ! -s "$PASSPHRASE" ]; then
    # The passphrase is never printed here; read it yourself and keep a copy off this server.
    openssl rand -base64 48 > "$PASSPHRASE"
    chmod 600 "$PASSPHRASE"
    echo "已生成新的加密口令：$PASSPHRASE。请自行执行 sudo cat $PASSPHRASE 并另存到服务器以外。"
fi

systemctl daemon-reload
systemctl enable --now wuyao-db-backup.timer wuyao-db-restore-check.timer
systemctl list-timers --no-pager 'wuyao-db-*'
