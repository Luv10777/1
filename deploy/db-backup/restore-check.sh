#!/usr/bin/env bash
# 每周恢复演练：把本机最新的加密备份恢复到临时库，核对关键表，完成后删除临时库。
# 由 wuyao-db-restore-check.timer 每周日 04:30 以 root 运行；只读正式库，不改正式数据。
set -euo pipefail
umask 077

DB=wuyao_growth
CHECK_DB=wuyao_restore_check
DIR=/var/backups/wuyao-db
STATE=/var/lib/wuyao-db-backup
PASSPHRASE=/etc/wuyao/backup-passphrase
export GNUPGHOME="$STATE/gnupg"

latest=$(find "$DIR" -maxdepth 1 -name "$DB-*.dump.gpg" -printf '%T@ %p\n' | sort -n | tail -1 | cut -d' ' -f2-)
[ -n "$latest" ] || { echo "no local backup to check" >&2; exit 1; }
work=$(mktemp -d)
psql_as() { PGOPTIONS='-c client_min_messages=warning' runuser -u postgres -- psql -X -At -v ON_ERROR_STOP=1 "$@"; }
cleanup() { psql_as -d postgres -c "DROP DATABASE IF EXISTS $CHECK_DB" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASSPHRASE" -o "$work/backup.dump" -d "$latest"
chown -R postgres "$work"

psql_as -d postgres -c "DROP DATABASE IF EXISTS $CHECK_DB" >/dev/null
psql_as -d postgres -c "CREATE DATABASE $CHECK_DB" >/dev/null
runuser -u postgres -- pg_restore --no-owner --no-privileges --exit-on-error -d "$CHECK_DB" "$work/backup.dump"

# The restored copy must hold data, and an append-only ledger can never be longer than the live one.
fail=0
for table in tenants users billing_accounts billing_ledger flyway_schema_history; do
    restored=$(psql_as -d "$CHECK_DB" -c "SELECT count(*) FROM $table")
    live=$(psql_as -d "$DB" -c "SELECT count(*) FROM $table")
    echo "$table: backup=$restored live=$live"
    if [ "$restored" -eq 0 ] || { [ "$table" = billing_ledger ] && [ "$restored" -gt "$live" ]; }; then fail=1; fi
done
version=$(psql_as -d "$CHECK_DB" -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
[ "$fail" -eq 0 ] || { echo "restore check failed for $(basename "$latest")" >&2; exit 1; }
printf '%s %s schema=V%s\n' "$(date --iso-8601=seconds)" "$(basename "$latest")" "$version" > "$STATE/last-restore-check"
echo "restore check ok: $(basename "$latest") (schema V$version)"
