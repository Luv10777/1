#!/usr/bin/env bash
set -euo pipefail
backend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$backend_dir/.env"
if [[ -e "$env_file" ]]; then
  echo '.env 已存在，保留原配置。'
  exit 0
fi
umask 077
local_jwt_secret="$(openssl rand -base64 48 | tr -d '\n')"
sed "s|^JWT_SECRET=.*$|JWT_SECRET=$local_jwt_secret|" "$backend_dir/.env.example" > "$env_file"
echo '已创建本地 .env，并生成随机 JWT 密钥。'
