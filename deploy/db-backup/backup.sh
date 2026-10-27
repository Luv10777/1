#!/usr/bin/env bash
# 正式服每日数据库备份：整库导出 → 校验可读 → 加密 → 上传 COS → 清理本机 7 天前的备份。
# 由 wuyao-db-backup.timer 每天 03:30 以 root 运行；任何一步失败都以非零退出，记入 systemd 日志。
set -euo pipefail
umask 077

DB=wuyao_growth
LIB=/usr/local/lib/wuyao-db-backup
DIR=/var/backups/wuyao-db
STATE=/var/lib/wuyao-db-backup
ENV_FILE=/etc/wuyao/growth-api.env
PASSPHRASE=/etc/wuyao/backup-passphrase
KEEP_DAYS=7

stamp=$(date +%Y%m%d-%H%M)
name="$DB-$stamp.dump.gpg"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GNUPGHOME="$STATE/gnupg"
mkdir -p "$DIR" "$GNUPGHOME"

# pg_dump runs as postgres; root writes the file, so it never lands anywhere the database user can read.
runuser -u postgres -- pg_dump -Fc "$DB" > "$work/backup.dump"
[ -s "$work/backup.dump" ] || { echo "empty dump" >&2; exit 1; }
tables=$(pg_restore --list "$work/backup.dump" | grep -c " TABLE DATA " || true)
[ "$tables" -gt 0 ] || { echo "dump lists no table data" >&2; exit 1; }

gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$PASSPHRASE" \
    --symmetric --cipher-algo AES256 -o "$work/$name" "$work/backup.dump"

keys=("db-backups/daily/$name")
# The first backup of each month is also kept under monthly/, which COS keeps for a year instead of 30 days.
[ "$(date +%d)" = "01" ] && keys+=("db-backups/monthly/$name")
python3 -I "$LIB/upload.py" "$ENV_FILE" "$work/$name" "${keys[@]}"

mv "$work/$name" "$DIR/$name"
find "$DIR" -maxdepth 1 -name "$DB-*.dump.gpg" -mtime +$((KEEP_DAYS - 1)) -delete
size=$(stat -c %s "$DIR/$name")
printf '%s %s %s tables=%s\n' "$(date --iso-8601=seconds)" "$name" "$size" "$tables" > "$STATE/last-success"
echo "backup ok: $name ($size bytes, $tables tables, uploaded to ${#keys[@]} location(s))"
