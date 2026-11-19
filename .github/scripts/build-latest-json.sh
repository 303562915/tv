#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生成 Pages 站点数据：
#   1) 把最近几个 Release 的 APK 镜像到 docs/apk/；
#   2) 写出 docs/latest.json，供首页显示版本、体积、更新日期与 SHA-256。
#
# 用法：GITHUB_TOKEN=xxx bash .github/scripts/build-latest-json.sh docs
# ---------------------------------------------------------------------------
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-303562915/tv}"
API="https://api.github.com/repos/${REPO}"
SITE_DIR="${1:-docs}"
OUT="$SITE_DIR/latest.json"
MIRROR_DIR="$SITE_DIR/apk"
MIRROR_COUNT=3

auth=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
  auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

mkdir -p "$MIRROR_DIR"

# ---- 版本号：以 Gradle 声明的为准 ----
version_name=""
version_code="0"
gradle_file="tv_project/app/build.gradle.kts"
if [ -f "$gradle_file" ]; then
  version_name=$(grep -m1 -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]+"' "$gradle_file" | sed -E 's/.*"([^"]+)".*/\1/' || true)
  version_code=$(grep -m1 -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$gradle_file" | grep -oE '[0-9]+' || true)
fi
[ -n "$version_code" ] || version_code=0

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
releases="$tmpdir/releases.json"

if ! curl -sSL --fail "${auth[@]}" -H "Accept: application/vnd.github+json" \
      "${API}/releases?per_page=30" -o "$releases"; then
  echo "warning: 读取 releases 失败" >&2
  echo '[]' > "$releases"
fi

# 仅保留「已发布 + 有 apk 资产」的 release，latest 滚动构建排最前
jq -r '
  [ .[]
    | select(.draft == false)
    | . as $r
    | ([.assets[] | select(.name | test("\\.apk$"; "i"))][0]) as $a
    | select($a != null)
    | [$r.tag_name, $a.name, ($a.size | tostring), $a.browser_download_url,
       ($r.published_at // ""), (($r.body // "") | gsub("[\t\n\r]"; " "))] ]
  | sort_by(if .[0] == "latest" then 0 else 1 end)
  | .[]
  | @tsv' "$releases" > "$tmpdir/rows.tsv"

entries="$tmpdir/entries.jsonl"
: > "$entries"
sha=""
notes=""
file_rel=""
file_name=""
file_size="0"
published=""
count=0

while IFS=$'\t' read -r tag name size url published_at body; do
  [ -n "${tag:-}" ] || continue
  count=$((count + 1))

  case "$tag" in
    latest) label="最新构建" ;;
    v*)     label="${tag#v}" ;;
    *)      label="$tag" ;;
  esac

  rel_uri=""
  if [ "$count" -le "$MIRROR_COUNT" ]; then
    safe=$(printf '%s-%s' "$tag" "$name" | tr -c 'A-Za-z0-9._-' '-')
    if curl -sSL --fail "${auth[@]}" -o "$MIRROR_DIR/$safe" "$url"; then
      rel_uri="apk/$safe"
      if [ "$count" -eq 1 ]; then
        sha=$(sha256sum "$MIRROR_DIR/$safe" | cut -d' ' -f1)
      fi
    else
      echo "warning: 下载 $name 失败，回退到 Release 直链" >&2
      rm -f "$MIRROR_DIR/$safe"
    fi
  fi
  [ -n "$rel_uri" ] || rel_uri="$url"

  if [ "$count" -eq 1 ]; then
    file_rel="$rel_uri"
    file_name="$name"
    file_size="$size"
    published="$published_at"
    notes="$body"
  fi

  jq -cn --arg v "$label" --arg f "$rel_uri" --arg n "$name" \
        --argjson s "${size:-0}" --arg p "${published_at:-}" \
        '{version_name: $v, file: $f, file_name: $n, size: $s, published_at: $p}' >> "$entries"
done < "$tmpdir/rows.tsv"

history=$(jq -s '.[0:12]' "$entries" 2>/dev/null || echo '[]')
if [ -z "$history" ] || [ "$history" = "null" ]; then
  history='[]'
fi

jq -n \
  --arg version_name "$version_name" \
  --argjson version_code "$version_code" \
  --arg file "$file_rel" \
  --arg file_name "$file_name" \
  --argjson size "${file_size:-0}" \
  --arg published_at "$published" \
  --arg sha256 "$sha" \
  --arg notes "$notes" \
  --argjson history "$history" \
  '{
     version_name: $version_name,
     version_code: $version_code,
     file: $file,
     file_name: $file_name,
     size: $size,
     published_at: $published_at,
     sha256: $sha256,
     notes: $notes,
     history: $history,
     generated_at: (now | todate)
   }' > "$OUT"

echo "mirrored: $count release(s), written: $OUT"
