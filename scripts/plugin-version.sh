#!/usr/bin/env bash
#
# 插件版本号工具：保证每次发布到 JetBrains Marketplace 的版本号都是「全新」的。
#
# JetBrains Marketplace 不允许同一个版本号重复上传（报错形如
# "The com.commitwise.idea plugin already contains version 0.5.0 in channel"），
# 因此发布前必须把候选版本号跟线上已发版本比对，必要时自动 patch 递增。
#
# 用法：
#   scripts/plugin-version.sh next <candidate>   结合线上已发布版本，算出下一个可用版本
#   scripts/plugin-version.sh bump <version>     纯 patch 自增（0.5.0 -> 0.5.1）
#   scripts/plugin-version.sh published          打印线上已发布版本（每行一个）
#
# 环境变量：
#   PLUGIN_XML_ID      插件 ID，默认 com.commitwise.idea
#   PLUGIN_NUMERIC_ID  插件在 Marketplace 的数字 ID（设置后可跳过搜索）
#   MARKETPLACE_URL    Marketplace 地址，默认 https://plugins.jetbrains.com
#   SKIP_REMOTE        true 时完全跳过网络查询（离线调试用）
#
# 约定：所有提示信息走 stderr，stdout 只输出干净的版本号，便于 $(...) 捕获。

set -uo pipefail

PLUGIN_XML_ID="${PLUGIN_XML_ID:-com.commitwise.idea}"
MARKETPLACE_URL="${MARKETPLACE_URL:-https://plugins.jetbrains.com}"
SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$'

info() { echo "[plugin-version] $*" >&2; }
warn() { echo "[plugin-version] WARN: $*" >&2; }

die() {
    echo "[plugin-version] ERROR: $*" >&2
    exit 1
}

require_semver() {
    [[ "$1" =~ $SEMVER_RE ]] || die "非法的版本号 '$1'（期望 SemVer，如 0.5.0）"
}

# 去掉预发布/构建元数据后的核心三段版本：0.5.0-eap -> 0.5.0
# 只保留数字前缀，避免把 0.5.0-eap.1 的 "eap.1" 误当成数字段。
core_version() {
    local v="${1%%[-+]*}"
    printf '%s\n' "$v"
}

# 把两个版本串起来排序取值，避免依赖 sort -V 以外的实现细节
sort_versions() {
    # shellcheck disable=SC2016
    printf '%s\n' "$@" | sed '/^$/d' | sort -V
}

# patch 自增：0.5.0 -> 0.5.1；带后缀时先剥离后缀再自增
bump_patch() {
    local raw="$1"
    require_semver "$raw"
    local core major minor patch
    core="$(core_version "$raw")"
    IFS='.' read -r major minor patch <<<"$core"
    [[ -n "${patch:-}" ]] || die "无法解析版本号 '$raw'"
    printf '%s.%s.%s\n' "$major" "$minor" "$((patch + 1))"
}

# 解析插件在 Marketplace 的数字 ID（公开 API 按 xmlId 搜索，一次最多返回 20 条）
resolve_plugin_id() {
    if [[ -n "${PLUGIN_NUMERIC_ID:-}" ]]; then
        printf '%s\n' "$PLUGIN_NUMERIC_ID"
        return 0
    fi
    command -v jq >/dev/null 2>&1 || return 1
    local json
    json="$(curl -fsS --max-time 20 \
        "${MARKETPLACE_URL}/api/searchPlugins?search=${PLUGIN_XML_ID}&max=20")" || return 1
    printf '%s\n' "$json" \
        | jq -r --arg id "$PLUGIN_XML_ID" '.plugins[]? | select(.xmlId == $id) | .id' \
        | head -1
}

# 拉取线上已发布的全部版本号。
# 注意：插件尚未通过审核/未公开时，公开 API 查不到，此时返回空列表（调用方需容错）。
fetch_published_versions() {
    command -v jq >/dev/null 2>&1 || { warn "未安装 jq，跳过线上版本查询"; return 1; }
    local pid
    pid="$(resolve_plugin_id)" || return 1
    [[ -n "$pid" ]] || return 1
    local json
    json="$(curl -fsS --max-time 30 \
        "${MARKETPLACE_URL}/api/plugins/${pid}/updates?size=200")" || return 1
    printf '%s\n' "$json" | jq -r '.[]? | .version // empty'
}

cmd_bump() {
    [[ $# -ge 1 ]] || die "用法: $0 bump <version>"
    bump_patch "$1"
}

cmd_published() {
    local versions
    versions="$(fetch_published_versions)" || die "无法获取线上已发布版本"
    printf '%s\n' "$versions" | sed '/^$/d'
}

cmd_next() {
    [[ $# -ge 1 ]] || die "用法: $0 next <candidate>"
    local candidate="$1"
    require_semver "$candidate"

    if [[ "${SKIP_REMOTE:-false}" == "true" ]]; then
        warn "SKIP_REMOTE=true，直接使用候选版本 $candidate"
        printf '%s\n' "$candidate"
        return 0
    fi

    local published
    if ! published="$(fetch_published_versions)"; then
        warn "无法从 Marketplace 读取已发布版本（网络异常，或插件尚未公开/未过审），保留候选版本 $candidate"
        printf '%s\n' "$candidate"
        return 0
    fi

    published="$(printf '%s\n' "$published" | grep -E "$SEMVER_RE" | sort -u -V)"
    if [[ -z "$published" ]]; then
        info "Marketplace 上还没有任何已发布版本，使用 $candidate"
        printf '%s\n' "$candidate"
        return 0
    fi

    local highest
    highest="$(printf '%s\n' "$published" | sort -V | tail -1)"
    info "线上最高版本：$highest（共 $(printf '%s\n' "$published" | wc -l | tr -d ' ') 个版本）"

    local target="$candidate" next
    # 目标版本低于线上最高版本时直接跳到「最高版本 + 1」：
    # Marketplace 不接受比现有版本更低的上传，逐个递增只会白白刷屏。
    if [[ "$target" != "$highest" ]] \
        && [[ "$(sort_versions "$target" "$highest" | tail -1)" == "$highest" ]]; then
        next="$(bump_patch "$highest")"
        info "目标版本 $target 低于线上最高版本 $highest，自动递增为 $next"
        target="$next"
    fi

    # 已被占用的版本逐个递增，直到找到空位
    while printf '%s\n' "$published" | grep -qxF -- "$target"; do
        next="$(bump_patch "$target")"
        info "版本 $target 已被占用，自动递增为 $next"
        target="$next"
    done

    printf '%s\n' "$target"
}

main() {
    local cmd="${1:-}"
    shift || true
    case "$cmd" in
        next) cmd_next "$@" ;;
        bump) cmd_bump "$@" ;;
        published) cmd_published "$@" ;;
        *)
            die "未知子命令 '$cmd'（可用：next | bump | published）"
            ;;
    esac
}

main "$@"
