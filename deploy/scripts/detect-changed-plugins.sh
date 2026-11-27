#!/usr/bin/env bash
# 输出「上一个 release-v* tag → HEAD」之间有源码改动的插件单元。
# 供 release.yml 的 plan（决定构建范围）与 publish（版本守卫）共用：
#   plan    → 只构建 apiserver + 有改动的插件，其余从上一版 Release 继承
#   publish → 只对本次构建的插件做版本守卫
#
# 用法：detect-changed-plugins.sh [当前 tag]
# 输出：prev=<tag> / changed=<构建 id,逗号分隔> / guard=<守卫 id,逗号分隔>
#   changed 用 units.json 的构建 id（danmu），guard 用 check-plugin-versions.ps1 的
#   守卫 id（danmu-api）—— 两套命名不同，不要合并。
set -euo pipefail

CUR="${1:-${GITHUB_REF_NAME:-}}"
git fetch --tags --force --quiet origin 2>/dev/null || true

PREV=""
while read -r t; do
  [ -z "$t" ] && continue
  [ "$t" = "$CUR" ] && continue
  if git merge-base --is-ancestor "$t" HEAD 2>/dev/null; then PREV="$t"; break; fi
done < <(git tag -l 'release-v*' --sort=-v:refname)

CHANGED=""
add() { CHANGED="${CHANGED:+$CHANGED,}$1"; }
if [ -n "$PREV" ]; then
  # 用命令替换判空，不要 `git diff | grep -q`（SIGPIPE + pipefail，踩过）
  [ -n "$(git diff --name-only "$PREV"..HEAD -- server/drpy server/plugin-manifests/drpy.manifest.json)" ] && add drpy
  [ -n "$(git diff --name-only "$PREV"..HEAD -- client/apps/tv/app/src/main/assets/node-payload-danmu.zip)" ] && add danmu
  [ -n "$(git diff --name-only "$PREV"..HEAD -- server/build-plugin-ffmpeg.ps1)" ] && add ffmpeg
  [ -n "$(git diff --name-only "$PREV"..HEAD -- server/pyrt server/build-plugin-pyrt.ps1 server/plugin-manifests/pyrt.manifest.json)" ] && add pyrt
  [ -n "$(git diff --name-only "$PREV"..HEAD -- client/apps/tv/runtime-node client/apps/tv/app/src/main/assets/node-payload-danmu.zip)" ] && add tv-node
  [ -n "$(git diff --name-only "$PREV"..HEAD -- client/apps/tv/runtime-python)" ] && add tv-python
fi

# 构建 id → 守卫 id（只有 danmu 命名不同）
guard_id() {
  case "$1" in
    danmu) echo danmu-api ;;
    *)     echo "$1" ;;
  esac
}
GUARD=""
for id in $(echo "$CHANGED" | tr ',' ' '); do
  GUARD="${GUARD:+$GUARD,}$(guard_id "$id")"
done

echo "prev=$PREV"
echo "changed=$CHANGED"
echo "guard=$GUARD"
