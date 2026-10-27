# 正式服数据库备份

正式服（systemd 部署，不是本目录上级的 Docker Compose）每天自动备份 PostgreSQL 数据库 `wuyao_growth`。

| 项目 | 做法 |
|---|---|
| 时间 | 每天 03:30（服务器时区 Asia/Shanghai）；当时关机则开机后补做 |
| 内容 | `pg_dump -Fc` 整库；导出后用 `pg_restore --list` 确认可读 |
| 加密 | gpg 对称加密（AES-256），口令在 `/etc/wuyao/backup-passphrase`（仅 root 可读） |
| 本机 | `/var/backups/wuyao-db/`，只存加密文件，保留 7 天 |
| 异地 | COS 私有桶 `db-backups/daily/`（保留 30 天），每月 1 号另存 `db-backups/monthly/`（保留 12 个月） |
| 演练 | 每周日 04:30 把本机最新备份恢复到临时库 `wuyao_restore_check`，核对关键表后删除 |
| 凭证 | 沿用后端已有的 `COS_SECRET_ID` / `COS_SECRET_KEY` / `COS_BUCKET` / `COS_REGION` |

COS 的保留期由存储桶生命周期规则负责，脚本不删除 COS 上的文件。需要在 COS 控制台为该桶加两条规则：
前缀 `db-backups/daily/` 30 天后删除，前缀 `db-backups/monthly/` 365 天后删除。

**加密口令必须另存到服务器以外**（例如密码管理器）。服务器整个丢失时，没有口令就无法解开 COS 上的备份。

## 安装

把本目录上传到服务器，在该目录执行：

```bash
sudo bash install.sh
```

首次安装会生成口令（不会打印出来）。安装后手动跑一次备份和一次演练，确认都成功：

```bash
sudo systemctl start wuyao-db-backup.service
sudo systemctl start wuyao-db-restore-check.service
```

## 查看状态

```bash
systemctl list-timers --no-pager 'wuyao-db-*'
sudo cat /var/lib/wuyao-db-backup/last-success /var/lib/wuyao-db-backup/last-restore-check
sudo journalctl -u wuyao-db-backup -u wuyao-db-restore-check --since -7d --no-pager
```

目前没有失败告警：失败只记在 systemd 日志里，`last-success` 的时间会停止更新。

## 恢复

1. 取备份：本机 `/var/backups/wuyao-db/` 里的文件，或从 COS 下载 `db-backups/daily/` 下对应日期的文件。
2. 解密（口令文件不在时，用另存的口令写一个临时文件，用完删除）：

   ```bash
   sudo gpg --batch --pinentry-mode loopback --passphrase-file /etc/wuyao/backup-passphrase \
       -o /tmp/restore.dump -d wuyao_growth-YYYYMMDD-HHMM.dump.gpg
   ```

3. 先恢复到新库核对，不要直接覆盖正式库：

   ```bash
   sudo chown postgres /tmp/restore.dump
   sudo -u postgres createdb wuyao_growth_restored
   sudo -u postgres pg_restore -d wuyao_growth_restored /tmp/restore.dump
   ```

4. 核对数据无误后，停掉 7 个后端服务，把两个库改名互换，再启动服务。完成后删除 `/tmp/restore.dump`。

最多丢失两次备份之间（24 小时内）的数据；发布脚本在每次发布前另有一份备份，放在 `~/deploy-staging/<发布名>/`。
