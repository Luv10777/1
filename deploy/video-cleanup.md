# 视频失败对象与历史孤儿对象清理

更新日期：2026-10-06。常规清理由 workflow 的失败、取消和迟到的导入回写触发。
历史孤儿对象使用 `cleanup-video-orphans.py` 手动处理；脚本默认只预览，不注册定时任务。

QA 成功时保存 `output_published_at`，后续状态变化不能清除交付证明。
历史行的 `progress=100` 也视为交付证据，因为只有 QA 成功会设置该值；V20 回填这些行的标记。
常规清理只处理该租户、该 workflow 的视频对象键，并保留 `READY` Asset 引用的对象。
导入使用独立的 `import-{UUID}/output.mp4`，写入前把键登记在 `pending_cleanup_keys`，
写入失败或执行过期时清理该键；删除失败记录警告并保留键。

## 安装与执行

需要 Python 3.9+、可连接 PostgreSQL 和 MinIO 的维护环境，以及已经迁移至 V22 的数据库。
V20 提供交付保护标记，V22 将历史取消状态订正为 `CANCELLED`，与脚本的终态判断一致。
建立独立虚拟环境，避免改变 API 或 worker 的运行环境。

```bash
python3 -m venv /home/ubuntu/wuyao-video-cleanup-venv
/home/ubuntu/wuyao-video-cleanup-venv/bin/pip install \
  -r /home/ubuntu/wuyao-current/deploy/requirements-video-cleanup.txt
```

脚本沿用 `DB_URL`、`DB_USER`、`DB_PASSWORD`、`DB_MIGRATE_USER`、`DB_MIGRATE_PASSWORD`、
`MINIO_ENDPOINT`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 和 `MINIO_BUCKET`。
维护数据库角色必须具有 `BYPASSRLS` 或超级用户只用于这次维护的权限，因为判断
“没有任何 Asset 引用”需要查询所有租户；普通受 RLS 限制的应用角色会被脚本拒绝。
脚本同时按对象键中的 tenant 和 workflow ID 复查目标行，不写数据库或清理 Flyway 数据。

通过临时 systemd 作业加载生产环境文件，命令参数不包含密钥。
下面示例租户 ID 为 `123`，执行时替换为实际待检查租户。

```bash
sudo systemd-run --pipe --wait --collect --uid=ubuntu \
  -p EnvironmentFile=/etc/wuyao/growth-api.env \
  /home/ubuntu/wuyao-video-cleanup-venv/bin/python \
  /home/ubuntu/wuyao-current/deploy/cleanup-video-orphans.py --tenant-id 123
```

预览输出为 JSON Lines，`would_delete` 是候选，`skip` 包含跳过原因。
审核后在同一条命令末尾追加 `--apply` 执行删除；每个对象都会重新验证条件，
不会直接重用预览清单。默认只处理超过 24 小时的对象，可用 `--min-age-hours` 调整，最小一小时。

## 删除边界与并发

历史脚本只识别该租户 `generated-video` 下的标准输出键，包括恢复轮次和独立下载子目录。
仅当 workflow 为 `FAILED` 或 `CANCELLED`、没有交付标记、没有待执行或运行中视频任务，
且全库没有任何 Asset 引用、没有已交付 workflow 引用时，才允许删除。
`SUCCEEDED` workflow、未知 workflow、未知路径、较新对象、任何 Asset 引用都保留。

脚本逐对象持有 workflow 行锁及 `assets` 的 SHARE 表锁，在删除前再次查询并检查对象时间。
表锁会短暂阻塞 Asset 写入，建议在维护窗口执行；锁等待超时会跳过并以非零退出码报告。
对象存储和 PostgreSQL 没有跨系统原子提交，脚本不修改 Asset 或 workflow 记录。
外部程序直接改库、绕过应用直接覆盖对象、版本化桶的旧版本和未完成 multipart 上传，
不属于脚本的删除范围。

常规清理会将未交付的悬空 Asset 记录保留为 `INVALID`，保留排查和历史关联。
已交付对象或其记录缺失时保留元数据，不借清理删除作品或掩盖数据丢失。
历史脚本也保留带 `INVALID` Asset 引用的对象；这些对象应由 workflow 的已登记清理键处理。

## 验证

Java 测试覆盖正常终态清理、失败保留键、交付保护和导入异常分支。
脚本的判定和删除调用有独立的 mock 测试，可从仓库根目录运行：

```bash
python3 -m unittest discover -s deploy/tests -p 'test_video_orphans.py' -v
```

实际测试输出见本次任务报告。这里不声明已经在生产桶执行过预览或删除。
