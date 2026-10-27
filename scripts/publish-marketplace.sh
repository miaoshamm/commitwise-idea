#!/usr/bin/env bash
#
# 发布到 JetBrains Marketplace，遇到「版本已存在」自动递增 patch 后重试。
#
# 背景：Marketplace 对同一版本号只允许上传一次，报错形如
#   "The com.commitwise.idea plugin already contains version 0.5.0 in channel"
# 一旦撞上，手动改版本号再推太麻烦，这里做成闭环：
#   publishPlugin 失败 → 命中版本冲突 → gradle.properties 版本 +1 → 再次 publishPlugin
#
# 环境变量：
#   MAX_ATTEMPTS  最多尝试次数，默认 4
#   GITHUB_OUTPUT 有值时写入 version=<最终发布版本>
#
# 退出码：0 表示发布成功；非 0 表示失败（版本冲突次数用尽 / 其它错误）。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MAX_ATTEMPTS="${MAX_ATTEMPTS:-4}"
PROPS="gradle.properties"
LOG_DIR="build"
LOG="${LOG_DIR}/publish-attempt.log"
mkdir -p "$LOG_DIR"

read_version() { grep '^pluginVersion=' "$PROPS" | cut -d= -f2 | tr -d ' \r'; }

write_version() {
    sed -i "s/^pluginVersion=.*/pluginVersion=$1/" "$PROPS"
}

for attempt in $(seq 1 "$MAX_ATTEMPTS"); do
    version="$(read_version)"
    echo "::group::publishPlugin 第 ${attempt}/${MAX_ATTEMPTS} 次（version ${version}）"
    ./gradlew publishPlugin --no-daemon -PuseLocalIde=false --stacktrace 2>&1 | tee "$LOG"
    status="${PIPESTATUS[0]}"
    echo "::endgroup::"

    if [[ "$status" -eq 0 ]]; then
        echo "[publish] 发布成功：$version"
        if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
            echo "version=$version" >>"$GITHUB_OUTPUT"
        fi
        exit 0
    fi

    if grep -qiE "already contains (the )?version|already exists in (the )?channel|conflict.*version" "$LOG"; then
        next="$(bash scripts/plugin-version.sh bump "$version")"
        echo "::warning::Marketplace 已存在版本 ${version}，自动递增为 ${next} 后重试"
        write_version "$next"
        continue
    fi

    echo "::error::publishPlugin 失败（非版本冲突），见上方日志"
    exit "$status"
done

echo "::error::连续 ${MAX_ATTEMPTS} 次遇到版本冲突，请检查 Marketplace 上已发布的版本"
exit 1
