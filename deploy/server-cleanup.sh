#!/usr/bin/env bash
# 一方志正式服务器清理清单：默认只读，只列出可以删除的路径和大小；加 APPLY=1 才真正删除。
#
# 规则：
#   - 前端、管理后台、后端各保留「当前版」和「上一版」，其余旧版本列出
#   - 前端当前目录里，当前版和上一版都不引用的旧资源文件列出
#   - ~/deploy-staging 保留最近 2 个（里面有发布前的数据库备份）
#   - /etc/wuyao 的配置备份保留最近 2 份
#   - 被软链接、systemd 单元、nginx 配置引用的路径一律跳过
#
# 用法（在本机执行，脚本通过 ssh 标准输入传过去，服务器上不留文件）：
#   ssh -i <密钥> ubuntu@<服务器> 'bash -s' < deploy/server-cleanup.sh
#   列出每一个无用前端资源文件：把 'bash -s' 换成 'VERBOSE=1 bash -s'
#   按清单删除：把 'bash -s' 换成 'APPLY=1 bash -s'。删除前会拿发布锁；当前版或上一版
#   认不出来时整体退出；脚本开始后才变动过的路径一律跳过（防止误删同时发布的新版本）。
set -uo pipefail

APPLY=${APPLY:-0}
START=$(date +%s)
H=/home/ubuntu
WEB=/var/www/wuyao/releases
ADMIN=/var/www/wuyao-admin/releases
total=0
PROTECTED=()

real() { sudo -n readlink -f -- "$1" 2>/dev/null; }
section() { printf '\n== %s\n' "$1"; }

# ---- 线上在用、不能删的路径 ----
for link in "$H/wuyao-current" /var/www/wuyao/current /var/www/wuyao-admin/current; do
  PROTECTED+=("$(real "$link")")
done
WEB_PREV=$(real "$(cat /var/www/wuyao/current/PREVIOUS 2>/dev/null)")
[ -n "$WEB_PREV" ] && PROTECTED+=("$WEB_PREV")
while read -r p; do
  [ -e "$p" ] && PROTECTED+=("$(real "$p")")
done < <({ systemctl cat 'wuyao*' 2>/dev/null; sudo -n cat /etc/nginx/sites-enabled/* /etc/nginx/conf.d/*.conf 2>/dev/null; } \
  | grep -oE "(/home/ubuntu|/var/www)/[^ ;\"']+" | sort -u)

if [ "$APPLY" = 1 ]; then
  exec 9>>"$H/.wuyao-deploy.lock"
  flock -n 9 || { echo "发布锁被占用，可能正在发布，未删除任何东西"; exit 1; }
  echo "!! APPLY=1：以下列出的路径会被删除"
fi

is_protected() {
  local c p; c=$(real "$1")
  [ -z "$c" ] && return 1
  for p in "${PROTECTED[@]}"; do
    [ -z "$p" ] && continue
    [[ "$p" == "$c" || "$p" == "$c"/* ]] && return 0
  done
  return 1
}

# item 路径 说明：存在就列出大小并计入合计；线上在用的标出跳过。
item() {
  local path=$1 why=$2 bytes
  # /etc/wuyao/backups 只有 root 能进，存在性也要用 sudo 判断。
  sudo -n test -e "$path" || sudo -n test -L "$path" || return 0
  if ! sudo -n test -L "$path" && is_protected "$path"; then
    printf '  %8s  %s  （跳过：线上在用）\n' "-" "$path"
    return 0
  fi
  bytes=$(sudo -n du -sb -- "$path" 2>/dev/null | cut -f1)
  if [ "$APPLY" = 1 ] && [ -n "$(sudo -n find "$path" -maxdepth 0 -newermt "@$START" 2>/dev/null)" ]; then
    printf '  %8s  %s  （跳过：脚本开始后有变动）\n' "-" "$path"
    return 0
  fi
  total=$((total + ${bytes:-0}))
  printf '  %8s  %s  # %s\n' "$(numfmt --to=iec "${bytes:-0}")" "$path" "$why"
  [ "$APPLY" = 1 ] && sudo -n rm -rf -- "$path"
}

# old_versions 目录 当前版 上一版 说明：两版之外的全部列出。
old_versions() {
  local dir=$1 cur=$2 prev=$3 why=$4 name
  if [ -z "$cur" ] || [ -z "$prev" ] || [ ! -d "$cur" ] || [ ! -d "$prev" ]; then
    echo "  认不出 $dir 的当前版或上一版，停止（之前的部分已按清单处理）"
    exit 1
  fi
  printf '  保留：%s（当前版）\n  保留：%s（上一版）\n' "$cur" "${prev:-无}"
  while read -r name; do
    [[ "$dir/$name" == "$cur" || "$dir/$name" == "$prev" ]] && continue
    item "$dir/$name" "$why"
  done < <(ls -1t "$dir")
}

# newest_other 目录 排除路径：除排除路径外修改时间最新的一项。
newest_other() {
  local name
  while read -r name; do
    [ "$1/$name" != "$2" ] && { echo "$1/$name"; return; }
  done < <(ls -1t "$1")
}

# ---- 1. 旧版本 ----
section "后端旧版本 $H/wuyao-releases"
cur=$(real "$H/wuyao-current")
old_versions "$H/wuyao-releases" "$cur" "$(newest_other "$H/wuyao-releases" "$cur")" "旧后端版本"

section "前端旧版本 $WEB"
old_versions "$WEB" "$(real /var/www/wuyao/current)" "$WEB_PREV" "旧前端版本"

section "管理后台旧版本 $ADMIN"
cur=$(real /var/www/wuyao-admin/current)
old_versions "$ADMIN" "$cur" "$(newest_other "$ADMIN" "$cur")" "旧管理后台版本"

# ---- 2. 前端当前目录里的旧资源 ----
section "前端当前目录里的旧资源（当前版和上一版都不引用）"
out=$(python3 - "$(real /var/www/wuyao/current)" "$WEB_PREV" "${VERBOSE:-0}" "$APPLY" "$START" <<'PY'
import os, re, sys
cur, prev, verbose, apply, start = sys.argv[1], sys.argv[2], sys.argv[3] == '1', sys.argv[4] == '1', int(sys.argv[5])
if apply and not (prev and os.path.isfile(os.path.join(prev, 'index.html'))):
    sys.exit('  认不出上一版前端，旧资源未删除')
pat = re.compile(r'([A-Za-z0-9_\-.~]+\.(?:js|css|woff2?|ttf|png|jpe?g|webp|svg|gif|mp4|wasm|json))')
def reachable(root):
    assets = os.path.join(root, 'assets')
    files = set(os.listdir(assets)) if os.path.isdir(assets) else set()
    seen = set()
    todo = [m for m in pat.findall(open(os.path.join(root, 'index.html'), encoding='utf-8').read()) if m in files]
    while todo:
        f = todo.pop()
        if f in seen:
            continue
        seen.add(f)
        if f.endswith(('.js', '.css')):
            text = open(os.path.join(assets, f), encoding='utf-8', errors='ignore').read()
            todo += [m for m in pat.findall(text) if m in files and m not in seen]
    return seen
used = reachable(cur) | (reachable(prev) if prev and os.path.isdir(prev) else set())
assets = os.path.join(cur, 'assets')
dead = sorted(f for f in os.listdir(assets) if f not in used)
size = sum(os.path.getsize(os.path.join(assets, f)) for f in dead)
print(f"  {len(dead)} 个文件，共 {size / 2**20:.0f}M，位于 {assets}/（被引用的 {len(used)} 个保留）")
for f in (dead if verbose else dead[:5]):
    print('    ' + os.path.join(assets, f))
if not verbose and len(dead) > 5:
    print('    ……完整列表加 VERBOSE=1 查看')
if apply:
    for f in dead:
        p = os.path.join(assets, f)
        if os.path.getmtime(p) < start:
            os.remove(p)
print(f"__BYTES__{size}")
PY
)
grep -v '^__BYTES__' <<<"$out"
total=$((total + $(grep -o '__BYTES__[0-9]*' <<<"$out" | cut -c10-)))

# ---- 3. 发布暂存目录 ----
section "发布暂存 $H/deploy-staging（保留最近 2 个，其余含旧的数据库备份）"
mapfile -t staged < <(ls -1t "$H/deploy-staging")
printf '  保留：%s\n' "${staged[@]:0:2}"
for name in "${staged[@]:2}"; do item "$H/deploy-staging/$name" "旧发布暂存"; done

# ---- 4. home 目录里的旧代码、旧构建和临时文件 ----
section "home 目录杂物"
item "$H/1"                                   "9 月的旧代码目录（含 node_modules、legacy、误建的 \\ 目录）"
item "$H/builds"                              "旧构建目录"
item "$H/wuyao-hotfix-work-20260930"          "9 月 30 日热修工作目录"
item "$H/wuyao-hotfix-20260930"               "指向旧后端版本的软链接"
item "$H/wuyao-frontend-staging-4ea5064"      "旧前端暂存"
item "$H/wechat-stage-20261009T074552Z-wechat-5ef5d86" "旧微信登录暂存"
item "$H/guest-deploy-20261014T061745Z-guest-6c59779"  "旧暂存"
item "$H/frontend-release-202609281633"       "旧前端发布目录"
item "$H/wuyao-runtime"                       "10 月初的旧版本、部署日志和临时文件"
item "$H/wuyao-staging"                       "旧预发布目录（现用 deploy-staging）"
item "$H/deploy-stage"                        "旧预发布目录（现用 deploy-staging）"
item "$H/wuyao-deploy"                        "10 月 14 日的旧 jar"
item "$H/release-6a0d508.tar.gz"              "旧发布包"
item "$H/wuyao-frontend-hide-376b007.tar.gz"  "旧前端发布包"
item "$H/wuyao-frontend-hide-376b007.bundle"  "旧 git bundle"
item "$H/deploy-all.bundle"                   "旧 git bundle"
item "$H/package-lock.json.before-deploy-20260922124144"      "手工备份"
item "$H/package-lock.json.before-reviews-fix-20260922125929" "手工备份"
item "$H/wuyao-before-update-20260918-130022.patch" "手工补丁"
item "$H/wuyao-ip.conf.tmp"                   "nginx 临时配置"
item "$H/MANIFEST.sha256"                     "旧前端哈希清单"
item "$H/ "                                   "文件名只有一个空格的空文件（root 误建）"

# ---- 5. /var/www 和 /etc/wuyao ----
section "/var/www"
item /var/www/html          "nginx 默认欢迎页，没有站点使用"
item /var/www/wuyao-current "指向 10 月 4 日旧版本的软链接，nginx 未使用"

section "/etc/wuyao 配置备份（各保留最近 2 份）"
mapfile -t envs < <(sudo -n find /etc/wuyao -maxdepth 1 -name 'growth-api.env.*' -printf '%T@ %p\n' | sort -rn | cut -d' ' -f2-)
printf '  保留：%s\n' "${envs[@]:0:2}"
for p in "${envs[@]:2}"; do item "$p" "旧 env 备份（含密钥）"; done
mapfile -t baks < <(sudo -n find /etc/wuyao/backups -mindepth 1 -maxdepth 1 -printf '%T@ %p\n' | sort -rn | cut -d' ' -f2-)
printf '  保留：%s\n' "${baks[@]:0:2}"
for p in "${baks[@]:2}"; do item "$p" "旧配置备份"; done

# ---- 汇总 ----
section "合计"
printf '  可释放约 %s\n' "$(numfmt --to=iec "$total")"
df -h / | tail -1 | awk '{print "  当前磁盘：已用 "$3" / "$2"（"$5"），剩余 "$4}'

section "以下不在清单里，需要人工判断"
for p in "$H/wuyao-backups" "$H/billing-operations" "$H/deploy-backups" /var/www/wuyao/downloads; do
  printf '  %8s  %s\n' "$(sudo -n du -sh -- "$p" 2>/dev/null | cut -f1)" "$p"
done
echo "  PM2 守护进程空转（没有管理任何应用），停掉后才能删 $H/.pm2"
echo "  /var/www/wuyao/current/COMMIT 和 PREVIOUS 外网可访问，但发布脚本要读，建议在 nginx 屏蔽而不是删除"
