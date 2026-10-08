#!/usr/bin/env bash
set -euo pipefail
backend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_dir"
# Prefer the project's Java 21 over Homebrew Maven's newest Java dependency.
for brew_prefix in /opt/homebrew /usr/local; do
  if [[ -x "$brew_prefix/opt/openjdk@21/bin/java" ]]; then
    export JAVA_HOME="$brew_prefix/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    export PATH="$JAVA_HOME/bin:$brew_prefix/bin:$PATH"
    break
  fi
done
if ! command -v mvn >/dev/null 2>&1; then
  echo '未找到 Maven，请先安装 Java 21 和 Maven。' >&2
  exit 1
fi
exec mvn "$@"
